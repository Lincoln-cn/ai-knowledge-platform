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

## 六、指标抓取（Prometheus）与它的两条前提（`#73`）

应用把三条业务指标导出在 **`GET /actuator/prometheus`**（Micrometer → Prometheus 文本格式）：

| 指标 | 回答什么 | 建议阈值的出处 |
|---|---|---|
| `aiplatform_upstream_calls_total{outcome}` | 上游不可达率 | `08 §4.10.1` |
| `aiplatform_chat_first_token_seconds`（P50/P95/P99） | 首字延迟（`B4` ≤10s 从此是持续读数） | 同上 |
| `aiplatform_ingestion_failures_total{stage}` | 入库失败率（按解析/分段/向量化分） | 同上 |
| `aiplatform_segment_label_mismatch` | 标签冗余漂移条数（**应为 0**；`-1` = 这次查询没成功，不是「一致」） | `13 §6.4.1`（`#26` 判据 ④） |

★ 这个端点**和 `/actuator/health` 同档免认证** —— 理由是 Prometheus 的采集端只带 URL、不带令牌。
代价是"匿名可读"，所以**交付时必须满足下面两条，缺一条就该改走独立管理端口**：

1. **标签里不许出现高基数或账号维度**（应用名 / 用户 / 会话 / 模型 id）。代码里由 `BusinessMetrics`
   一处把关（它没有接受这类参数的入口），单测 `BusinessMetricsTest.noHighCardinalityTagsLeakIn` 钉住。
2. **生产必须靠网关或防火墙限制抓取来源** —— 例如只允许监控网段访问 `/actuator/prometheus`。
   若做不到，就把它挪到独立管理端口：
   ```yaml
   management:
     server:
       port: 9091          # 只绑管理网段
   ```
   挪端口后，`SecurityConfig` 里那条 permitAll 就只需保留 health 一条（两者是不同的端口，互不影响）。

★ 还有一条**不是运维问题、是埋点问题**：没被记录过的指标**不会出现在导出里**（不是记成 0）。
「查不到」与「一直是 0」在仪表盘上长得一样 ⇒ 如果某条曲线凭空消失了，先怀疑埋点被删/被跳过，
不要先怀疑"没有流量"。

---

## 七、备份与恢复（`#75` 判据 ①，数字是交付前提）

★ 这一节回答的是「**数据留得住、删得掉、恢复得了**」里的第三问。前两问（留存期、软删 vs 彻底删除）
的口径在 `02 §5.1.1`。

| 项 | 口径 | 为什么是这个数 |
|---|---|---|
| **全量备份频率** | 每日 1 次（`pg_dump -Fc`，业务低峰） | 全量是"兜底"，不是"恢复手段" —— 单靠它 RPO 会到 24 小时 |
| **增量（PITR）** | 开 **WAL 归档**（`archive_mode=on` + `archive_command` 推到独立存储） | 只有 WAL 能把 RPO 压到分钟级；`pgvector` 的向量也是普通表列，随 WAL 一起走 |
| **RPO（能丢多少）** | **≤ 15 分钟** | 与 WAL 归档的推送间隔对齐 |
| **RTO（多久能回来）** | **≤ 4 小时**（单机 + 离线交付的合理值） | 交付评审一定会问这两个数；给不出来等于没有备份方案 |
| **保留** | 日备 **14 天** · 周全备 **8 周** · 归档 WAL **≥ 7 天** | 覆盖"周五改坏、下周一才发现"这种最常见的事故窗口 |
| **存放** | 与应用主机**不同的介质**（NAS / 对象存储 / 内网备份机） | 备份和数据库在同一块盘上 = 没有备份 |

**留存期清理（`#75` 判据 ②：留存期的数字要能被执行）**：审计记录是只追加的（`13 §7.2` 第 3 条），
所以"到期清理"是一条**独立的维护动作**，不是普通的删除：

```bash
# ① 先数不删：会清掉多少条
curl -sS -X POST "$AIP/api/v1/audit-logs/purge" -H "Authorization: Bearer $ADMIN_TOKEN"   -H 'Content-Type: application/json' -d '{"beforeAt":"2025-10-31T00:00:00Z","dryRun":true}'
# ② 确认数量后再真删（这一步不可逆）
curl -sS -X POST "$AIP/api/v1/audit-logs/purge" -H "Authorization: Bearer $ADMIN_TOKEN"   -H 'Content-Type: application/json' -d '{"beforeAt":"2025-10-31T00:00:00Z","dryRun":false}'
```

★ 三条前提：① 截止时间不能为空、不能在未来（库函数自己校验）；② 每次清理都会写一条 `audit.purge`
（删了几行、清到什么时候），所以"清理"本身也在审计里；③ **先备份再清理** —— 清理不可逆，
而备份的保留期（日 14 天 / 周 8 周）比审计的留存期（默认 12 个月）短得多，两者不能互相替代。
**调度（`#75`，2026-10-31 落地）**：同一个动作也进了调度，默认**关闭** —— 删审计不可逆，而"客户规定多长留存期"
是客户事实、不是代码事实，由代码替客户拍一个天数然后开始删，比不删更危险。

| 变量 | 默认 | 说明 |
|---|---|---|
| `AUDIT_RETENTION_ENABLED` | `false` | 打开后每天 03:30 自动清理一次（排在 `pg_dump` 之后） |
| `AUDIT_RETENTION_DAYS` | `365` | 保留天数；**低于 1 会被抬到 1**（0 天等于删光，那不叫保留期） |
| `AUDIT_RETENTION_CRON` | `0 30 3 * * *` | 只在需要错峰时改 |

★ 调度删的那条 `audit.purge` 多一个 `trigger=retention`，报表里能分开"人点的"与"调度删的"。
★ 清理失败只写 `log.error`（关键字「留存期清理失败」），**不会**让应用起不来 —— 所以打开之后请确认监控里有这个关键字，
否则它失败了也不会有人知道。手动通道仍然保留：`dryRun` 先数后删，是给"马上要清一批"的场合用的。

**恢复演练（判据 ③）**：清单、执行步骤与**记录模板**在 [`restore-drill-record.md`](restore-drill-record.md)；
填完用 `python scripts/restore_drill.py check deploy/restore-drill-record.md` 验，检查器在快闸里（`--self-check`）。
★ 模板里留「待补」是刻意的：没填完一定红 —— 把不完整的记录判成合规，比没有记录更糟。
★ **没演练过的备份不算备份** —— 它只是一堆文件。演练记录要能回答：恢复到哪个时刻、用了多久、
恢复到新实例后应用能否直接起来（这一点比"文件能还原"更重要，因为它会暴露 Flyway 版本与备份时点的错位）。

```bash
# 全量（示例）
pg_dump -Fc -f /backup/aip-$(date +%F).dump "$DATABASE_URL"
# 恢复（示例：先建空库，再 restore，不要在跑着的库上覆盖）
pg_restore -d "$TARGET_URL" --clean --if-exists /backup/aip-2026-10-31.dump
```

★ **一条与"删得掉"直接相关的提醒**：`pg_dump` 的备份里包含**软删的数据**（`deleted_at` 有值的行）——
所以在实现彻底删除之前，"数据主体要求删除"这件事**连备份都过不去**。这不是遗漏，是 `#75` 第二段
（0.5.x）要解决的问题，写在这里是为了不让它被读成"已经有删除能力了"。

---

## 七之补、回收站超期清理（`#259`）：默认关，开了才会真删

删除文档之后内容只是**拿出检索面**（保留期内可「恢复」）。过了 `app.knowledge.restore-window-days`（默认 7 天）
恢复会被拒绝，但内容仍占空间 —— 真正腾空间的是这条作业：

| 键 | 默认 | 说明 |
|---|---|---|
| `KNOWLEDGE_CLEANUP_ENABLED` | `false` | **默认关闭**：装好就有一个定时任务在删数据，是这类功能最不该有的默认值 |
| `KNOWLEDGE_CLEANUP_RETAIN_DAYS` | `7` | 与 `KNOWLEDGE_RESTORE_WINDOW_DAYS` 同值即可：能恢复的窗口一过就该腾空间 |
| `KNOWLEDGE_CLEANUP_BATCH_SIZE` | `50` | 一次一批，别把整表搬进内存 |
| `KNOWLEDGE_CLEANUP_CRON` | `0 10 4 * * *` | 每天 04:10（审计留存清理是 03:30，错开一小时） |

★ 上面两个窗口（`RESTORE_WINDOW_DAYS` 与 `CLEANUP_RETAIN_DAYS`）是**两个键**：清理窗口短于恢复窗口时，启动自检会警告 —— 那意味着"还能点恢复"的文档内容已经被清理掉了（点下去 400/404）。两者同值即可。

★ 它删什么：**分段 + 向量 + 文档行**，顺序固定（先分段再文档行 —— 反过来会留孤儿分段，而孤儿分段照样命中），
每条写一条 `document.purge` 审计（`detail.trigger = retention`，谁都没调，所以 `actor_id` 为空）。
★ 它**不**删审计记录：那是 `app.audit.retention` 那条作业的事。两条留期分开配，
混成一条会让"业务数据的留存期"和"审计的留存期"互相拖住。

## 八、启动时看什么：配置自检的那两行日志（`#227` / `#65`）

后端起来时会跑一遍**配置自检**（`StartupSettingsWarningRunner`），只打日志、**不阻止启动**。
交付/排障时先看这两行：

```text
✔ 配置自检通过：向量化并发 4 · 连接池 10 · 上下文窗口 80 · 向量预算 1200 · 恢复窗口 7 天 · 清理窗口 7 天
⚠️ 配置自检发现 1 项会在运行时以难查的方式出问题（服务仍会启动）：
  · app.ingestion.embedding-concurrency=69 大于连接池上限 10：…（改法写在同一条里）
```

★ **"通过"那一行本身就是一份配置快照** —— 报障时先把它抄下来，比让人去翻环境变量快得多。

它检查的六项，都对应过一次**真实踩过的坑**：

| 项 | 配错的后果 | 出处 |
|---|---|---|
| `app.ingestion.embedding-concurrency` > 连接池 | 一批文档一起入库时出现**与内容无关**的"向量化失败"（`Failed to obtain JDBC Connection`），排查时人会先怀疑模型和语料 | `#227`（现场 69 份一起跑、10 条假失败） |
| `app.ingestion.embedding-concurrency` ≤ 0 | 文档永远停在"待向量化"，**没有任何报错** | `#227` 的闸门下限 |
| `app.segmentation.context-window` < 0 | 入库与预览都直接抛 `IllegalArgumentException` | `#65` |
| `context-window` > 0 但 `max-chars` 太小 | 窗口**看起来开着、实际一条都没进来**（预算让路） | `#65` |
| `app.knowledge.cleanup.retain-days` < `app.knowledge.restore-window-days` | 回收站里「恢复」按钮**还亮着**，点下去 400 —— 内容已被清理作业删掉 | `#259` |
| `app.knowledge.cleanup.retain-days` 配 0 或负 | **启动自检不报这一项**：清理作业自己按 1 天兜底。想关掉清理要改 `cleanup.enabled=false` —— 配 0 不等于关 | `#259` |

★ 为什么不 fail-fast：这几项属于"能起来但会以难查的方式坏"。让它起不来，会把一个
**可修复的配置问题**升级成"服务挂了"；真正的硬约束（负窗口）由领域层在运行时守住。

---

## 九、OCR 的配置项（交付时可能要改的）

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

## 十、开发机（Windows）怎么装

与镜像路线**等价**的本地安装（本机实测过）：

```powershell
scoop install tesseract                 # 5.5.0，shim 会进 PATH
# 语言包按需下两个 .traineddata（tesseract-languages 会装全部语言，用不着）
# 放进 scoop 的 persist 目录：~\scoop\persist\tesseract\tessdata\
```

scoop 会自动把 `TESSDATA_PREFIX` 指到上面那个目录 —— 所以开发机上不配 `app.ocr.tessdata-path`
也能用；但**交付时请显式配**（见第五节）。

---

## 十一、诚实记账：哪些已验、哪些还没

| 项 | 状态 |
|---|---|
| 基础镜像 + 三个 apt 包 + tessdata 路径 | ✅ 实测（`debian:bookworm-slim` 与 `eclipse-temurin:25-jre` 都验过） |
| 镜像构建成功（`.dockerignore` 裁剪后 490 MB 上下文） | ✅ 实测 |
| 镜像内 OCR 可用（`--list-langs` = chi_sim/eng） | ✅ 实测 |
| 应用在镜像内启动并对 OCR 报"可用"（启动日志 + health） | ✅ 实测（连的是宿主 PG） |
| 误配（`TESSDATA_PREFIX` 指向空目录）被探测并报 `DEGRADED` | ✅ 实测 |
| **镜像内跑完整 OCR 入库链**（上传 `scanned.pdf` → 分块 → 向量化） | ⏳ **未验** —— 需要把 PG 与桩模型服务一起拉进同一网络，随真栈闸门 `check_ocr_e2e.py` 落地时补 |
| 内网 `docker save/load` 全流程 | ⏳ 未验（机制标准，但没有内网机器可验） |
