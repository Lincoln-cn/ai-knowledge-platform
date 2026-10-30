# 部署与离线交付

> ★ 这一份讲**交付**（内网 / 单客户私有化，`ROADMAP §九`）。开发期形态在根目录的
> [`docker-compose.yml`](../docker-compose.yml)：它**只起 PG**，应用在本地跑 ——
> 两者关注点不同，混在一起会让"改代码不重建镜像"这条开发便利消失（`ROADMAP §4.6`）。

---

## 一、为什么这份文档里要专门写 OCR

**因为 OCR 引擎是系统级资产，不像 jar 那样跟着构建走。**

`tesseract` 与它的语言包（`chi_sim.traineddata` 这类文件）**不在 Maven 依赖里、不在 jar 里** ——
它们是操作系统包。这带来两个只在交付时才暴露的坑：

| 坑 | 症状 | 为什么难发现 |
|---|---|---|
| 忘了装引擎 | 扫描件全部"解析结果为空" | 界面只说「可能是扫描件」，**看不出是引擎没装**；用户会去反复重新导出那个本来就好的文件 |
| ★ 装了引擎、忘了拷语言包 | 中文退化成方块/乱码 | **日志里一句错都没有**；英文还能认，于是看起来"OCR 是好的" |

⇒ 第 9 周（0.3.1）为此做了一件基础设施性质的事：**把"这台机器能不能 OCR"变成一条显式事实** ——
启动日志一行 + `/actuator/health` 的 `components.ocr`。本文档的第三、四节就是**照着它做交付自检**。

---

## 二、PostgreSQL 镜像（pgvector + zhparser）—— 中文全文检索的前提（第 10 周 · 0.3.2 补记）

**为什么 PG 也要自制镜像**：设计册 `09 §10.2 #3` 定的中文全文检索是 `to_tsvector('chinese_zh', …)`，
而 `chinese_zh` 的 parser 只能是 **zhparser**。实测（本机真容器）`pgvector/pgvector:pg16` 的
`pg_available_extensions` 里**没有 zhparser**、PGDG / Debian 也没有现成包 ⇒ 只能自己编译。

```bash
# 构建（★ 构建上下文是仓库根）
docker build -f deploy/pg/Dockerfile -t ai-knowledge-platform-pg:dev .
# 自检（11 项：pgvector 真功能 + zhparser 真分词 + 无编译器残留）
python deploy/verify_image_pg.py
```

| 项 | 值（本机实测） |
|---|---|
| 基础镜像 | `pgvector/pgvector:pg16` = Debian 12 bookworm + PG **16.15**（Debian 包版） |
| 加进去的东西 | SCWS 1.2.3（`hightman/scws`）+ zhparser（`amutu/zhparser`），**版本都钉住** |
| 镜像大小 | **643 MB**（base 621 MB）★ 第一版 1.98 GB —— "先装后卸"跨 RUN **不会**让镜像变小 |
| 交付后要联网吗 | **不需要**（apt 与源码只在构建期用；运行期只有镜像内的 .so 与词典） |

### ★★ 两个必须知道的点

1. **它仍然是 pgvector 镜像**：两段式都拿 `pgvector/pgvector:pg16` 当 base ⇒ `vector` 原封不动。
   换成官方 `postgres` 镜像会让 V1 迁移直接失败（`CREATE EXTENSION vector`）。
2. **存量库不会重跑 initdb**：`deploy/pg/initdb/10-extensions.sql` 只在**首次初始化**时执行。
   换镜像时若数据目录已存在，必须自己补一次：
   ```sql
   create extension if not exists vector;   -- 通常已有
   create extension if not exists zhparser;
   ```
   ★ 漏了这一步的症状是「全文检索一条都搜不到」，而向量路照常 —— 混合模式看不出异常。

## 三、应用镜像

[`Dockerfile`](./Dockerfile) 就是交付形态。它把 OCR 引擎与语言包**烤进镜像层**，
于是交付后运行时不依赖互联网、也不需要再装任何东西（`12 §11.2` 的"零新服务、零新库"落在这里）。

```bash
# 1) 先出 jar（在仓库根执行）
backend\mvn-jdk25.cmd -B -f backend/pom.xml package -DskipTests

# 2) 构建镜像（★ 构建上下文是仓库根）
docker build -f deploy/Dockerfile -t ai-knowledge-platform:0.3.1 .

# 3) 交付给内网：导出 → 拷贝 → 导入（离线这一步不需要任何网络）
docker save ai-knowledge-platform:0.3.1 -o ai-knowledge-platform-0.3.1.tar
#   内网机器上：
docker load -i ai-knowledge-platform-0.3.1.tar
```

### ★★ 一个必须知道的构建坑：`.dockerignore` 不是"优化"，是"让构建能用"

本仓库根目录下有一个 **25 GB** 的 `_archive/`（旧系统资料，`.gitignore` 已忽略、不进版本库，
但**本机在**）。没有 [`.dockerignore`](../.dockerignore) 时，`docker build .` 会把这 25 GB
全部推给 Docker 守护进程 —— 表现是**构建卡住不动、长时间没有任何输出**，
很容易被误判成"网络慢"或"apt 卡住"（实测：>25 分钟无输出）。

裁剪后上下文从 25 GB 降到约 **490 MB**。

### ★★ 镜像内实测：完整 OCR 入库链（第 9 周 · 0.3.1 补记）

**为什么单独验这一条**："镜像里装了 tesseract" ≠ "扫描件能入库"。
后者还要经过 Flyway 迁移、DB、上游桩与**异步流水线** —— 装没装只是第一关。

```powershell
# 1) 起一个对着宿主 PG 与桩的容器（18080 避开本地 8080）
docker run -d --name aip-scan-verify -p 18080:8080 `
  -e DB_URL=jdbc:postgresql://host.docker.internal:15432/aiplatform `
  -e AI_BASE_URL=http://host.docker.internal:11434/v1 `
  ai-knowledge-platform:dev --app.security.captcha.enabled=false

# 2) 验（脚本自己建库、上传扫描件、轮询、清理）
python deploy/verify_image_ocr.py     # → 默认打 http://localhost:18080
```

**实测结果（9/9）**：

| 项 | 结果 |
|---|---|
| 容器启动日志 | `OCR 引擎：可用（语言 chi_sim+eng，tesseract (PATH)）` |
| 上传接口 | **336 ms** 返回（异步：不等 OCR 跑完） |
| 扫描件入库 | **16.7 s** 完成 → `PENDING_EMBEDDING` |
| 契约字段 | `ocrTotalPages = 3`（快扫得出的进度分母） |
| 分块落库 | `segmentCount = 1`，分段正文是**识别出来的中文** |

★ 注意 `tesseract (PATH)` 那一栏：镜像是**走 PATH** 找引擎（`OCR_TESSERACT_PATH` 留空），
而语言包目录来自镜像里的 `TESSDATA_PREFIX`。这两个来源都是**显式可见**的 ——
这正是第一节说的"把能不能 OCR 变成显式事实"的用处。

---

## 四、离线交付清单 · OCR

镜像里必须有下面两样。<u>下面每一项都是本机实测过的值</u>，不是照文档抄的。

### 3.1 引擎与语言包（镜像路线，推荐）

| 项 | 值 | 核实方式 |
|---|---|---|
| 基础镜像 | `eclipse-temurin:25-jre` | 实测 = Ubuntu 26.04.1 LTS + OpenJDK 25.0.4.1 |
| apt 包（三个都必需） | `tesseract-ocr` · `tesseract-ocr-chi-sim` · `tesseract-ocr-eng` | 实测可装，tesseract **5.5.0** |
| tessdata 目录 | `/usr/share/tesseract-ocr/5/tessdata/` | 实测存在，含 `chi_sim` / `eng` / `osd` |
| 环境变量 | `TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata` | Dockerfile 里已设 |

> ⚠️ **`tesseract-ocr` 官方包只带 `eng`** —— 中文包必须显式列 `tesseract-ocr-chi-sim`。
> 漏了它不会报错，只会让中文全成方块。

★ **3.1 与 3.2 是同一份模型**（实测 sha256 逐字一致）：

```
apt 装的 /usr/share/tesseract-ocr/5/tessdata/chi_sim.traineddata
  = a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730
tessdata_fast 下载的 chi_sim.traineddata
  = a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730   ← 同一个字节
```

Ubuntu 的 `tesseract-ocr-chi-sim` 装的**就是 `tessdata_fast`**（`12 §11.2` 定的档位）
⇒ **两条路线等价**，离线清单只需记一份；下面的 sha256 对 apt 路线同样成立，
**可以用来核对"内网那台机器上的语言包是不是这一份"**。

> ★ **不需要关心 tesseract 版本与中文产物形态的关系**：产物形态（TXT）与"要不要重建词间空格"
> 是 **jar 里 Tika 的配置**决定的（`OcrEngine` 里显式钉住），**与操作系统装的是哪个 tesseract 无关**。
> OS 侧的 tesseract 版本只影响**识别质量**，不影响"中文会不会被逐字打散"这件事。

### 3.2 如果内网连 apt 源都没有（纯离线投放语言包）

把两个 `.traineddata` 文件拷进 `TESSDATA_PREFIX` 指向的目录即可，不需要重装引擎。
本机实测的**文件名与校验**（`tessdata_fast`，`12 §11.2` 定的档位）：

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `chi_sim.traineddata` | 2411 KB | `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730` |
| `eng.traineddata` | 4017 KB | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |

来源：`https://github.com/tesseract-ocr/tessdata_fast`（Apache-2.0）。
★ `best` 档更准但 +27 MB，`fine-tune` 更大；**MVP 用 `fast`**（`12 §11.2`）。

投放后**一定要**按第四节自检 —— 只拷文件不看 health，等于把上面那个"静默的坑"留在现场。

---

## 五、★ 交付自检（三条，缺一不可）

### ① 镜像里引擎与语言包都在

```bash
docker run --rm --entrypoint tesseract ai-knowledge-platform:0.3.1 --list-langs
# 期望：列出 chi_sim、eng（只有 eng ⇒ 漏装了中文包）
```

### ② 应用自己认同这件事（★ 最关键的一条）

```bash
curl -s http://<host>:8080/actuator/health | jq '.components.ocr'
```

期望（**已在本机镜像里实测到**）：

```json
{
  "status": "UP",
  "details": {
    "available": true,
    "languages": ["chi_sim", "eng"],
    "tesseractPath": "(PATH)",
    "tessdataPath": "/usr/share/tesseract-ocr/5/tessdata",
    "dpi": 300
  }
}
```

**验收判据是 `components.ocr.details.available == true`，不是"health 整体是不是 UP"。**
理由：没装 OCR 不影响登录、文本型文档与检索，所以它**不会**把整体 health 拉成 DOWN；
它自己会是 `DEGRADED` 并在 `reason` / `hint` 里说清缺什么、下一步做什么。

实测的反向路径（把 `TESSDATA_PREFIX` 指到一个空目录）：

```
WARN  OCR 引擎：不可用：找到了 tesseract，但缺语言包：chi_sim.traineddata、eng.traineddata（目录 …）
health: { "status": "DEGRADED", "details": { "available": false, "reason": "…", "hint": "…" } }
```

> 部署验收还要看：`tessdataPath` 是不是你镜像里的那个目录、`languages` 里 `chi_sim` 在不在。
> 这两项是"看起来装了、其实没生效"最常出现的地方。

### ③ 端到端：传一份扫描件

用真栈闸门跑（见[根 README](../README.md) 的验证段）：

```bash
python frontend/check_ocr_e2e.py
```

它上传 `scanned.pdf`（3 页全无文本层）并断言：「已识别 3/3 页 → 入库成功」，
以及**文本型文档不受影响**。

---

## 六、OCR 的配置项（交付时可能要改的）

全部走环境变量覆盖（`application.yml` 的 `app.ocr.*`）：

| 环境变量 | 默认 | 什么时候改 |
|---|---|---|
| `OCR_ENABLED` | `true` | 明确不需要扫描件时关掉（关掉后扫描件的失败原因会说清是配置关的） |
| `OCR_TESSERACT_PATH` | 空（走 PATH） | ★ **生产建议显式给**：留空意味着"环境里恰好有"，而这正是本项目最不想要的偶然后果 |
| `OCR_TESSDATA_PATH` | 空（走 `TESSDATA_PREFIX`） | 语言包不在默认位置时给；★ `12 §11.2` 的坑 ① 要求这一步必须显式 |
| `OCR_LANGUAGES` | `chi_sim+eng` | 不需要中文时改 `eng`（更快更小） |
| `OCR_DPI` | `300` | **别调低** —— `12 §11.3/§11.4` 定的下限就是 300，调低明显掉识别率 |
| `OCR_TIMEOUT_SECONDS` | `120` | 单页超时；必须有上限，否则一张异常大的图会把后台任务挂死 |
| `OCR_PREPROCESS` | `true` | 图像预处理（deskew）；`12 §11.4` 要求必开 |

---

## 七、开发机（Windows）怎么装

与镜像路线**等价**的本地安装（本机实测过）：

```powershell
scoop install tesseract                 # 5.5.0，shim 会进 PATH
# 语言包按需下两个 .traineddata（tesseract-languages 会装全部语言，用不着）
# 放进 scoop 的 persist 目录：~\scoop\persist\tesseract\tessdata\
```

scoop 会自动把 `TESSDATA_PREFIX` 指到上面那个目录 —— 所以开发机上不配 `app.ocr.tessdata-path`
也能用；但**交付时请显式配**（见第五节）。

---

## 八、诚实记账：哪些已验、哪些还没

| 项 | 状态 |
|---|---|
| 基础镜像 + 三个 apt 包 + tessdata 路径 | ✅ 实测（`debian:bookworm-slim` 与 `eclipse-temurin:25-jre` 都验过） |
| 镜像构建成功（`.dockerignore` 裁剪后 490 MB 上下文） | ✅ 实测 |
| 镜像内 OCR 可用（`--list-langs` = chi_sim/eng） | ✅ 实测 |
| 应用在镜像内启动并对 OCR 报"可用"（启动日志 + health） | ✅ 实测（连的是宿主 PG） |
| 误配（`TESSDATA_PREFIX` 指向空目录）被探测并报 `DEGRADED` | ✅ 实测 |
| **镜像内跑完整 OCR 入库链**（上传 `scanned.pdf` → 分块 → 向量化） | ⏳ **未验** —— 需要把 PG 与桩模型服务一起拉进同一网络，随真栈闸门 `check_ocr_e2e.py` 落地时补 |
| 内网 `docker save/load` 全流程 | ⏳ 未验（机制标准，但没有内网机器可验） |
| 交付 compose（`deploy/docker-compose.yml`：应用 + PG + Ollama 一条命令起） | ✅ **`docker compose config` 实测**：给了 `JWT_SECRET` ⇒ 展开正确（`DB_URL` 指服务名 `postgres`、`AI_BASE_URL` 指 `ollama`）；不给 ⇒ **`exit 1` 并指名要哪个变量**。⏳ 还没在有 GPU 的机器上真跑过一整轮问答（缺的是"起得来"之外的读数，不是配置正确性） |

---

## 九、一条命令起（交付形态）

前面第三、四节讲的是"镜像怎么来"，这一节讲"拿到镜像之后怎么起"。文件是
[`docker-compose.yml`](./docker-compose.yml)（★ 与根目录那份**不是**一个东西：
根目录是开发期、只起 PG；这一份是交付期、应用也进容器）。

```bash
# 1) 起（JWT_SECRET 必填：交付形态不让它静默沿用开发默认值）
JWT_SECRET="$(openssl rand -base64 48)" docker compose -f deploy/docker-compose.yml up -d

# 2) 拉模型（否则界面会一路报"没有可用模型"；离线交付时模型随卷带走，跳过这步）
docker compose -f deploy/docker-compose.yml exec ollama ollama pull qwen2.5:3b
docker compose -f deploy/docker-compose.yml exec ollama ollama pull bge-m3

# 3) 验
curl -fsS http://127.0.0.1:8080/actuator/health
```

三个必须知道的点：

1. **容器里连的是服务名**（`postgres` / `ollama`），不是 `localhost` —— 这是"一条命令起"最容易错的一格，
   也是本地 `application.yml` 默认值（`localhost:15432` / `localhost:11434`）在容器里**一定**连不上的原因。
2. **`JWT_SECRET` 用 `:?` 强制**：不设就 `exit 1`，并打出"必须设置 JWT_SECRET"。理由在文件头 ——
   交付形态里静默沿用开发密钥 = 所有部署共用一把钥匙。
3. **模型不自动拉**：`ollama pull` 要网络，而离线交付恰恰没有网络。离线件 = 镜像 + `.ollama` 卷
   （`aip-ollama`），落地后挂上即可。

端口可用 `APP_PORT` 改（默认 8080）；PG 不对外暴露端口（只在 compose 网络内可达），
需要从宿主机连库做排查时，临时加一条 `ports: ["15432:5432"]` 即可，别把它留在交付件里。
