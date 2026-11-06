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
#    ★ 标签不要写死版本号（`#273`）：这里曾经写着 `:0.3.1`，而 pom 早已是 0.4.5 ——
#    同一个病在 `Dockerfile` 的 `COPY` 行上也犯过（那份镜像直接构建不出来）。
docker build -f deploy/Dockerfile -t ai-knowledge-platform:dev .

# 3) 交付给内网：导出 → 拷贝 → 导入（离线这一步不需要任何网络）
docker save ai-knowledge-platform:dev -o ai-knowledge-platform.tar
#   内网机器上：
docker load -i ai-knowledge-platform.tar
```

★ 「镜像里的 jar 名」与 `backend/pom.xml` 的 `artifactId-version` 对不对得上，由
`deploy/check_image_manifest.py` 守（快闸里跑两步：自检 + 实判）。它同时钉住
`ENTRYPOINT` 路径、`EXPOSE` 与 `application.yml` 的 `server.port` 默认值、以及「不以 root 跑」。

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

★ **「备份里还有」的口径（判据 ④，`#299` 2026-11）**：彻底删除（`DELETE /api/v1/documents/{id}/purge`、
`DELETE /api/v1/conversations/{id}/purge`）的真实语义是**从生产库与检索里消失**——
`pg_dump` 的备份里当然还有：日备保留 14 天、周全备 8 周 ⇒ **被彻底删除的数据在最坏情况下还能从备份里恢复出来，
最长 ≈ 8 周**。三句话把这个口径钉住：

1. **对数据主体的说法**：删除请求已执行——生产系统与检索里不再存在、不可再被引用；备份副本按备份轮转
   （14 天 / 8 周）自然过期，在此期间只有"从旧备份整库恢复"这一条路能把它带回来，而**恢复是演练级动作、
   不是查询动作**——没有人能"查一下备份里这条还在不在"。
2. **客户要求"备份也擦掉"（介质级擦除）时怎么做**：备份是**外部系统**（NAS / 对象存储 / 备份机），
   平台控制不了，**这条只记账不实现**（`#299` 处置）。流程口径：① 由备份管理员在备份系统侧**定位含该数据的
   备份文件**（按恢复到隔离实例 + 查询确认的方式定位，备份系统本身不提供行级查询）；② 对这些备份副本
   **单独安全擦除或直接销毁**（覆盖写 / 加密擦除 / 介质销毁，按客户保密制度）；③ 擦除动作**在客户的备份管理
   流程里留记录**（哪个备份、擦除时间、执行人）——平台的 `pf_audit_log` 只追加，**不假装它管得到备份**。
   ★ 话术边界：平台侧只能承诺"生产库与检索里已删除"；"备份里也已擦除"这句话**只能由备份管理员签字**，
   平台文档不代签。
3. **WAL 归档同理**：归档 WAL ≥ 7 天的窗口内同样可能含被删数据，随归档过期自然消失；
   要提前消除时走第 2 条同一流程（在 WAL 归档存储侧处理）。

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
✔ 配置自检通过：向量化并发 4 · 连接池 10 · 上下文窗口 80 · 向量预算 1200 · 恢复窗口 7 天 · 清理窗口 7 天 · 两个密钥都不是示例值
⚠️ 配置自检发现 1 项会在运行时以难查的方式出问题（服务仍会启动）：
  · app.ingestion.embedding-concurrency=69 大于连接池上限 10：…（改法写在同一条里）
```

★ **"通过"那一行本身就是一份配置快照** —— 报障时先把它抄下来，比让人去翻环境变量快得多。

它检查的**九项**，都对应过一次**真实踩过的坑**：

| 项 | 配错的后果 | 出处 |
|---|---|---|
| `app.ingestion.embedding-concurrency` > 连接池 | 一批文档一起入库时出现**与内容无关**的"向量化失败"（`Failed to obtain JDBC Connection`），排查时人会先怀疑模型和语料 | `#227`（现场 69 份一起跑、10 条假失败） |
| `app.ingestion.embedding-concurrency` ≤ 0 | 文档永远停在"待向量化"，**没有任何报错** | `#227` 的闸门下限 |
| `app.segmentation.context-window` < 0 | 入库与预览都直接抛 `IllegalArgumentException` | `#65` |
| `context-window` > 0 但 `max-chars` 太小 | 窗口**看起来开着、实际一条都没进来**（预算让路） | `#65` |
| `app.knowledge.cleanup.retain-days` < `app.knowledge.restore-window-days` | 回收站里「恢复」按钮**还亮着**，点下去 400 —— 内容已被清理作业删掉 | `#259` |
| `app.knowledge.cleanup.retain-days` 配 0 或负 | **启动自检不报这一项**：清理作业自己按 1 天兜底。想关掉清理要改 `cleanup.enabled=false` —— 配 0 不等于关 | `#259` |
| `app.security.jwt.secret` 还是仓库里的示例值 | 令牌**可以被任何人伪造** —— 这份值写在源码里，攻击者签出的令牌能过所有鉴权，而请求全都正常返回。生产必须设 `JWT_SECRET`（例如 `openssl rand -base64 32`），换完既有令牌全部失效、需要重新登录 | `#272` |
| `app.model.api-key-secret` 还是仓库里的示例值 | 模型供应商的 API Key 在库里是这条密钥加密后的密文，示例值公开 ⇒ **拿到库就能解回明文 Key**（等于明文存库）。生产必须设 `MODEL_API_KEY_SECRET`；★ 换完已存密文解不开，要在界面上把每个供应商的 Key 重新录一遍 | `#272` |
| `app.model.ollama.max-loaded-models` = 1 | 问答链路要**两个模型同时驻留**（向量模型算查询向量 + 对话模型生成答案）。槽位只有 1 时它们每轮互相卸载/加载 ⇒ ① 偶发「连接上游失败：`HTTP/1.1 header parser received no bytes`」（看起来像网络问题）② **每轮都付一遍冷启动**，首字延迟被抬到十几秒。改成 ≥2（1.5B + bge-m3 ≈ 2GB 显存） | `#282` |

★ 为什么不 fail-fast：这几项属于"能起来但会以难查的方式坏"。让它起不来，会把一个
**可修复的配置问题**升级成"服务挂了"；真正的硬约束（负窗口）由领域层在运行时守住。

---

## 八之补、用户说"偶发一次失败"时先看这三行（`#216`）

后端在**响应已提交之后**再抛异常时（SSE 最常见：写着写着炸了），框架自己也会炸一次（Tomcat 的
`MimeHeaders` 被置空后 `removeHeader` 的 NPE），而那个 NPE 会**替换掉原始异常** ——
用户看到"生成失败、没原因"，日志里也只剩"处理异常时又炸了一次"。

所以 `OriginalExceptionLoggerResolver`（最高优先级）会在链路更前面把原始异常落日志，只记不处理。
排障时按这个顺序 grep：

```bash
grep -c '请求处理抛异常（原始）' logs/ai-platform.log    # 原始异常被记下来的次数
grep -c 'MimeHeaders.removeHeader' logs/ai-platform.log  # 被 NPE 擦痕的次数（期望 0）
```

- 前者 > 0、后者 = 0 ⇒ **一次普通异常**，按它带的类型与堆栈查即可；
- 两者都 > 0 ⇒ 命中 `#216` 那条链路：原始异常在上一行，**别去看 NPE 那一行**（它只是遮罩）；
- 前者 = 0、后者 > 0 ⇒ 意味着日志里出现了"没有原始异常"的 NPE —— 那说明这条路径绕过了
  `DispatcherServlet`，属于要新开账的形状，不要就地改。

---

## 八之补二、`app.*` 其余配置一览：改了会发生什么（`#272`）

配置自检只覆盖数值与两个密钥（见上节）。下面这些不会被自检拦，但同样会改变行为 ——
它们的共同点是**配错之后系统照常起、照常答，只是答案或代价不对**。

表里的"默认"就是 `backend/src/main/resources/application.yml` 里的值；
括号里是环境变量名。本节与 yml 同步维护：改 yml 的默认值，改法见 `#272` 那条自检。

### 检索链路（`10 §6.2` 第 6 项）

| 键（ENV） | 默认 | 改了会怎样 |
|---|---|---|
| `app.retrieval.query-understanding-budget-ms`（`RETRIEVAL_QU_BUDGET_MS`） | 5000 | 单次"查询理解"调用的超时。CPU + 1.5B 上实测 4–5s，调小会在改写这一步就超时 |
| `app.retrieval.total-budget-ms`（`RETRIEVAL_TOTAL_BUDGET_MS`） | 5000 | 检索总预算，每路开始前查余量；耗尽后**用已拿到的结果继续**（不是报错） |
| `app.retrieval.hop-budget-ratio`（`RETRIEVAL_HOP_BUDGET_RATIO`） | 0.6 | 多跳的"该收手了"水位：用掉 60% 就不开新一轮 |
| `app.retrieval.query-understanding-enabled`（`RETRIEVAL_QU_ENABLED`） | true | 关掉 = **主动单路**，`degradeLevel` 保持 0（这是配置，不是降级 —— `#64` 判据 2） |
| `app.retrieval.query-understanding-retry-on-parse-failure`（`RETRIEVAL_QU_RETRY`） | true | 关掉可省一次模型调用（CPU 上 4–5s），代价是解析失败直接降级 |

### 分段与入库

| 键（ENV） | 默认 | 改了会怎样 |
|---|---|---|
| `app.segmentation.segment-length`（`SEGMENT_LENGTH`） | 500 | 分块目标长度。★ 必须与前端向导 `CreateWizard.vue` 的默认值一致，否则预览与入库不同 |
| `app.segmentation.overlap`（`SEGMENT_OVERLAP`） | 60 | 分块重叠，同上 |
| `app.segmentation.max-chars`（`SEGMENT_MAX_CHARS`） | 1200 | 向量模型字符上限的**回落值**（库里的模型有 `contextWindow` 时以它为准） |
| `app.segmentation.context-window`（`SEGMENT_CONTEXT_WINDOW`） | 80 | 索引侧上下文窗口，0 = 关。★ 它只进 `search_text` 不进 `content`；**换值（含 0↔非 0）要重新向量化存量文档** |
| `app.ingestion.embedding-concurrency`（`INGESTION_EMBEDDING_CONCURRENCY`） | 4 | 批量入库时同时跑的份数。配 0 或负数按 1 算（配 0 会让向量化永不执行且不报错）；调高到超过连接池只会排队 |
| `app.ingestion.parse-concurrency`（`INGESTION_PARSE_CONCURRENCY`） | 0（= 自动取 `min(逻辑核数, max(1, 连接池/2))`） | 同时解析的份数（`#303`）。★ 解析是 CPU 密集、且每份在解析期占一条连接 ⇒ 只压连接池不压它，会先炸解析（表现为「请确认文件可正常打开」这类与文件无关的失败）。配 0 = 按上面公式自动定，不建议手填大数 |
| `app.ingestion.segmenting-timeout-minutes`（`INGESTION_SEGMENTING_TIMEOUT_MINUTES`） | 10 | 「分段中」超期多久后被回收（`#303`）。判超期看 **`updatedAt` 停摆**（活着的任务每页回写进度），不是"创建得久"；且只回收**无主**的（`ParseRunner` 登记表里没有任务）⇒ 慢跑不会被误杀。★ 下限 1 分钟 |
| `app.ingestion.segmenting-reaper.enabled`（`INGESTION_SEGMENTING_REAPER_ENABLED`） | true | 自愈扫描开关。★ **与其它定时任务相反，默认开着** —— 关掉就等于回到「卡死永远没人管」。只有在排查"回收得太积极"时才临时关 |
| `app.ingestion.segmenting-reaper.interval-ms`（`INGESTION_SEGMENTING_REAPER_INTERVAL_MS`） | 60000 | 扫描间隔（`fixedDelay`：一轮扫完再计下一轮，不是固定频率） |
| `app.ingestion.pending-parse-timeout-minutes`（`INGESTION_PENDING_PARSE_TIMEOUT_MINUTES`） | 2 | 「待解析」超期多久后被回收（`#304`）。判超期看 **`parse_expected_at`（期望解析开始的时刻）**：只对"有字节、期望解析"的份生效，无字节元数据登记的文档永远停在「待解析」是正常的、回收器跳过。与 `segmenting-timeout-minutes` 的分工：那边是「任务已创建、中途死掉」，这边是「任务从未创建」。★ 下限 1 分钟 |
| `app.ingestion.pending-parse-reaper.enabled`（`INGESTION_PENDING_PARSE_REAPER_ENABLED`） | true | 自愈扫描开关（`#304`）。★ 与其它定时任务相反，**默认开着** —— 关掉就等于回到「解析任务没起来时永远没人管」，只在排查"回收得太积极"时才临时关 |
| `app.ingestion.pending-parse-reaper.interval-ms`（`INGESTION_PENDING_PARSE_REAPER_INTERVAL_MS`） | 60000 | 扫描间隔（`fixedDelay`：一轮扫完再计下一轮，不是固定频率） |
| `spring.datasource.hikari.maximum-pool-size`（`DB_POOL_SIZE`） | 10 | 连接池上限。★ 并发高于它时多出来的虚拟线程拿不到连接，表现为「与文件内容无关的向量化失败」（`#227`）；★ 调大前先看库端 `max_connections`：**实例数 × 这个数**才是库看到的连接数 |

### 出站与文件

| 键（ENV） | 默认 | 改了会怎样 |
|---|---|---|
| `app.egress.external-api-ceiling`（`EGRESS_EXTERNAL_API_CEILING`） | INTERNAL | **外发闸门**：外部 API 供应商的模型允许处理到的最高密级。`SECRET`/`CONFIDENTIAL` 默认一律不发；调高等于允许把机密内容送出网 |
| `app.files.root`（`FILE_STORE_ROOT`） | `data/files` | 附件字节的落盘根目录。★ 容器里请配绝对路径并挂持久卷 —— 相对目录会随重启消失，表现为"记录在、点开是空的" |
| `app.files.max-size-bytes`（`FILE_UPLOAD_MAX_BYTES`） | 20971520 | 单个附件上限，超了给可读的 400。★ 与 `spring.servlet.multipart.max-file-size` 是两道门，**都调大才真的放开** |

### 安全

| 键（ENV） | 默认 | 改了会怎样 |
|---|---|---|
| `app.security.jwt.secret`（`JWT_SECRET`） | 仓库里的示例值 | 拿到它就能伪造任意用户的令牌。★ 非本地环境必须覆盖，换完既有令牌全部失效（自检会提示） |
| `app.model.api-key-secret`（`MODEL_API_KEY_SECRET`） | 仓库里的示例值 | 模型 API Key 的加密密钥。★ 拿到它 + 拿到库就能解回明文；换完已存密文解不开，需要在界面重录 |
| `app.security.captcha.enabled`（`CAPTCHA_ENABLED`） | true | 等保要求登录带验证码；关掉只应出现在压测/自动化环境 |
| `app.security.login-failure.max-attempts`（`LOGIN_MAX_ATTEMPTS`） | 3 | 登录失败几次后冻结账号（等保测评项） |
| `app.security.public-rate-limit.capacity`（`PUBLIC_RATE_LIMIT_CAPACITY`） | 60 | 公开面（免登录）每分钟请求数。★ `enabled` 默认 true：安全开关漏配必须是"开着的" |
| `app.security.jwt.issuer` | `http://ai-knowledge-platform.local` | 令牌里的 `iss`。★ 必须是 **URL 形式**：裸串会让 `Jwt.getIssuer()` 在读 `iss` 时抛转换异常。改它不影响已在手的令牌（已签出的照旧有效） |
| `app.security.jwt.access-ttl` | `30m` | 访问令牌有效期（`08 §4.0` 的契约形状）。调大等于把"令牌泄露后的可利用窗口"一起调大 |
| `app.security.jwt.refresh-ttl` | `7d` | 刷新令牌有效期 —— 决定"一次登录能撑多久不用再登" |
| `app.security.captcha.ttl` | `2m` | 验证码有效期。它只需要"够人输入一次"，往大调只是让人工等待更久 |
| `app.security.login-failure.lock-duration`（`LOGIN_LOCK_DURATION`） | `30m` | 超过 `max-attempts` 之后账号冻结多久（等保测评项，与次数上限是一对） |
| `app.security.public-rate-limit.window-seconds`（`PUBLIC_RATE_LIMIT_WINDOW_SECONDS`） | `60` | 限流的统计窗口。与 `capacity` 一起才成立：默认就是 **60 次 / 60 秒** |
| `app.security.public-rate-limit.enabled`（`PUBLIC_RATE_LIMIT_ENABLED`） | `true` | 公开面限流总开关。★ 安全开关的默认值必须是"开着"的：漏配的环境不该静默退回无限流。★ 真栈读数见 `PublicRateLimitHttpTest`（它把 capacity 压到 3 来证"真的会 429"） |
| `app.embedding.local.model-path`（`LOCAL_ONNX_MODEL_PATH`） | `models/multilingual-e5-small/model_quantized.onnx` | 进程内向量化的模型文件。★ 路径不对时它不会"降级成安静地失败"：该模型要么连上游、要么报错，先看 `app.embedding.local.enabled` 与 `model-name` |
| `app.embedding.local.tokenizer-path`（`LOCAL_ONNX_TOKENIZER_PATH`） | `models/multilingual-e5-small/tokenizer.json` | 同上，分词器文件；两个文件必须成对存在 |

### 定时任务与示例数据

| 键（ENV） | 默认 | 改了会怎样 |
|---|---|---|
| `app.audit.retention.enabled`（`AUDIT_RETENTION_ENABLED`） | false | 审计留存期清理。★ 默认关：删审计不可逆，"客户规定的留存期"是客户事实 |
| `app.knowledge.cleanup.enabled`（`KNOWLEDGE_CLEANUP_ENABLED`） | false | 回收站超期清理，开启后过期的已删文档会被**真的**删掉（分段 + 向量 + 文档行） |
| `app.knowledge.restore-window-days`（`KNOWLEDGE_RESTORE_WINDOW_DAYS`） | 7 | 回收站"还能恢复"的窗口；`cleanup.retain-days` 与它同值即可 |
| `app.seed.sample-data`（`SEED_SAMPLE_DATA`） | false | 灌业务示例数据（迁移只跑一次）。★ 集成测试与它共用迁移，默认开着会让一批按条数断言的用例红 |
| `app.embedding.local.enabled`（`LOCAL_ONNX_ENABLED`） | true | 进程内 ONNX 向量化。★ `model-name` 是**路由判据**：知识库登记的 `baseModel` 必须与它一致，否则会去连上游 11434 |
| `app.embedding.local.model-name`（`LOCAL_ONNX_MODEL_NAME`） | `multilingual-e5-small` | ★★ **路由判据**：`RoutingEmbeddingAdapter` 按 `baseModel` 名分派，知识库里登记的名字必须与它一致，否则那条模型会去连上游 11434（而 11434 上可能根本没有这个模型） |
| `app.chat.advisors.enabled`（`CHAT_ADVISORS_ENABLED`） | true | Spring AI advisor 链总开关。★ 只用于排障 —— 关着上线等于安全闸门不生效 |
| `app.chat.guard.sensitive-words`（`CHAT_GUARD_SENSITIVE_WORDS`） | 空 | 问答输入/输出两侧的敏感词表（逗号分隔）。★ 默认空**不是**"没有这个功能"，而是"部署方按自己的合规要求填" —— 留着默认值上线等于这一层不设防 |
| `app.chat.suggestions.parallel`（`AIP_SUGGESTIONS_PARALLEL`） | false | 「猜你想问」的并行模式：开着时回答一开始生成就用「问题 + 检索上下文」猜，用户"看到完成"时 chips 已经在了；代价是**它没看到答案**，追问更容易偏，且每次都多一次模型调用、与主回答抢同一个模型槽位（`#285`）。★ 默认关 = 拿质量换给愿意换速度的人 |
| `app.chat.retrieval.neighbor-context.enabled`（`AIP_NEIGHBOR_CONTEXT_ENABLED`） | false | 查询侧的**上下文窗口**（`#65`）：把命中段的**邻座正文**一并拼进"给模型的上下文"，用于补上被硬分段边界劈开的那半句（"…见下表" / 表在下一节）。★ **不产生新引用**：`SourceRef` 的条数、编号、`full` 一律不变，邻居段只标一句"与上段相邻，仅供理解上下文，不单独编号"。★ 默认关 = 凡是主链路上多一次数据库查询的开关，缺省必须与不做这件事一致 |
| `app.chat.retrieval.neighbor-context.before`（`AIP_NEIGHBOR_CONTEXT_BEFORE`） | 1 | 往上取几个邻座段（`0` = 不往上取） |
| `app.chat.retrieval.neighbor-context.after`（`AIP_NEIGHBOR_CONTEXT_AFTER`） | 1 | 往下取几个邻座段（`0` = 不往下取） |

## 八之补三、把本机 Ollama 登记成服务商（`#288`，建议一开始就做）

新建模型时，服务商下拉里有一条「**不绑定（旧式直连 / 默认上游）**」。选它，填上 `apiBase` 就能用 —— 但
它会**悄悄少掉三件事**：出站密级（能不能出网）、认证方式、连通性检查，数据外发闸门也就没有供应商类别可判。
代码侧 `ProviderKind` 早就准备好了（`OLLAMA("http://localhost:11434/v1", NONE, LOCAL, true)`），
问题只是"从来没人走过那条路"—— 新装的实例里 `ai_model_provider` 是**空表**。

所以装完之后，在界面上走一遍（或等价地 `POST /api/v1/model-providers`）：

| 字段 | 值 |
|---|---|
| 名称 | 本机 Ollama |
| 类别 | `OLLAMA` |
| 服务地址 | `http://127.0.0.1:11434/v1`（★ **用 `127.0.0.1`，不要用 `localhost`**：容器的端口只绑 IPv4，而 Java 的 `localhost` 会先解析成 `::1`，症状是"连接上游失败 / ConnectException"） |
| 认证方式 | `NONE` |
| 出站 | `LOCAL`（不出网） |

登记之后，新模型优先选它。★ **存量模型不用动**：它们的 `api_base` 是直填的，改绑服务商会让地址来源变一个，
不属于这次调整的范围。

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
