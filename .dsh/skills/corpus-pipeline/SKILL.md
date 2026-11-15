---
name: corpus-pipeline
description: "构建、扩展或重跑本项目的检索评测语料：从公开文本抓取 → 清洗成 markdown → 走真实摄取链路入库 → 两轮独立标注 → 跑 B1/B3 评测。当用户提到「扩语料」「换真语料」「再抓几篇」「重新标注」「跑 B1/B3」「评测语料」「0.9.2 语料」时使用。也用于排查「评测报错一堆」这类问题（多半是向量化没跑）。"
metadata:
  version: 1.0.0
---

# 语料流水线（抓取 → 入库 → 标注 → 评测）

目标是**可复现**：任何人照着跑，都能得到与 `docs/检索基线读数.md` 同口径的读数。
所有命令都在仓库根 `D:\IdeaProjects\ai-plantform` 下执行。

## 先读这两份文档

| 文档 | 说明 |
|---|---|
| `docs/检索基线读数.md` | 指标的**唯一**定义（B1/B3/doc_top1 共用 `core` 剥前缀判据）、当前读数、已证伪的路 |
| `docs/ROADMAP.md §11.49` | 真语料的来龙去脉、两轮对照、26 条错例分类、读数不能当结论的地方 |

★ **两套语料永远分栏写，不混算**：合成语料（模板化，`corpus/synthetic/qa.jsonl` 195 条）
与真实语料（`corpus/real/qa-labeled.jsonl`）。混在一起算平均是最容易犯的错。

## 五个阶段

### ① 抓取：只有一条通路能用

| 通道 | 结果 |
|---|---|
| `web_search` 中文查"企业制度 全文" | ❌ 返回裁判文书与期刊论文 |
| `flk.npc.gov.cn`（国家法律法规库） | ❌ Vue SPA，正文靠 XHR；试过 `/api/detail`、`/api/law/detail`、`/api/detail/{id}`、`/api/v1/detail` 全返回 SPA 外壳 |
| `gov.cn/zhengce/zhengceku/` | ❌ 直连 403（反爬） |
| `mem.gov.cn` 法规索引页 | ⚠️ 直连 200，但文档链接全部指向 `flk.npc.gov.cn/detail?id=...` ⇒ 死路 |
| **Node `fetch` 直连 `zh.wikisource.org`** | ❌ `UND_ERR_CONNECT_TIMEOUT`（超时） |
| **PowerShell `Invoke-WebRequest`** | ✅ 能通（走系统代理） |
| **`web_fetch` 工具** | ✅ 能通（走代理），拿到的正文干净 |

⇒ **抓取只能用 `web_fetch` 工具或 PowerShell，清洗用 Node**。

```powershell
# 单篇抓取（raw 是维基文本，结构比渲染页干净得多）
$r = Invoke-WebRequest -Uri "https://zh.wikisource.org/w/index.php?title=中华人民共和国公司法&action=raw" -UseBasicParsing
[IO.File]::WriteAllText("$pwd\.build\wiki-raw\公司法.txt", $r.Content, (New-Object System.Text.UTF8Encoding($false)))
```

★ **沿革页陷阱**：主页面常只有"沿革和最新版"目录（查 `action=raw` 得到 400 多字符），
正文在 `[[中华人民共和国劳动合同法 (2012年)]]` 这种子页里 ⇒ 要先抽年份最大的子页标题，再取一次。
`tools/fetch_wikisource_law.mjs` 已实现这个逻辑（Node 版；直连不通时用它生成的 URL 交给 `web_fetch`）。

★ PowerShell 正则坑：`[regex]::Matches` 的结果必须用 `$m.Groups[1].Value`，写 `$Matches[1]` 会报
`Cannot index into a null array`。

### ② 清洗：wikitext → markdown

```powershell
node tools\wikitext_to_md.mjs      # .build/wiki-raw/*.txt → corpus/real/NN-标题.md
node tools\renumber_corpus.mjs     # 删掉 0 条的空文件（沿革页）、按条文数降序重编号 01..N
```

清洗规则（`wikitext_to_md.mjs`）：丢 `{{header}}` 多行模板与单行 `{{…}}`、去 `{{gap}}` 前缀、
`[[A|B]]→B`、`== X ==→## X`、`=== X ===→### X`、`'''X'''→**X**`、全角空格收成一个普通空格。
元信息只保留"制定机关""修正沿革"两行。

### ③ 入库：**向量化是用户显式动作，后端不会自动推进**

这是整条流水线最容易踩的坑：文档与段落都会停在 `PENDING_EMBEDDING`，
而评测的报错文案是 `<urlopen error [WinError 10061] 目标计算机积极拒绝>` —— **看起来像服务挂了，其实是没向量**。

```powershell
node .build\reupload-real.mjs      # 模板：清旧 → 上传 → 等解析 → 逐个触发向量化 → 等全部 EMBEDDED
node .build\dump-real.mjs          # 导出 .build/real-corpus/segments.jsonl（标注要读它）
```

契约（都在 `backend/src/main/java/cn/lincoln/aiplatform/knowledge/web/`）：
- 上传：`POST /api/v1/knowledge-bases/{kbId}/documents`，multipart 字段 `files` / `docType` / `orgId` / `classification`
- **向量化**：`POST /api/v1/documents/{id}/embedding`（`DocumentActionController`）
- 删除：`DELETE /api/v1/documents/{id}`（★ 不是 `/knowledge-bases/{kbId}/documents/{id}`，那个 404）
- ★ **不存在 `GET /documents/{id}`** ⇒ 判"解析完成没有"只能查库：
  `select status from kb_document` / `select count(*) from kb_segment where embedding_status <> 'EMBEDDED'`

评测库（本机）：`52f02f99-2468-46a5-91e9-e85f935c7761`（`语料解析-1793188905`，384 维 e5-small 向量）、
组织 `0356598a-f2a0-44ed-a904-a2ec3ce27c56`、`docType=制度`、`classification=PUBLIC`。

★ 库里**不能留重复文档**（旧命名的 `01-安全生产法.md` 与新命名的 `02-中华人民共和国安全生产法.md` 并存），
否则 `expected_document` 匹配会出现同名多篇。重命名后一定要先清旧。

### ④ 标注：两轮独立，只留一致的

```powershell
node tools\label_real_corpus.mjs --per-doc 200 --out corpus\real\qa-labeled.jsonl
```

- 第一轮（出题）：只给**单段**，要 `{question, answer}`；answer 必须逐字复制连续原文
  （可省略前后文、不能改写/概括/补字）；question 不得含 answer 的关键数字/关键词；
  问题要具体，不带"根据第X条"这类位置提示。
- 第二轮（验证）：给**整篇所有段落**（带 ordinal）+ 第一轮的问题，独立指认 `{ordinal, answer}`；
  只有"这段直接回答了问题"才算，答不了给 `ordinal: -1`。
- 保留条件：**两轮指向同一段** 且 answer 逐字在该段（比较前 `stripMd` 剥 `**`/`#`/`\|` 并归一空白）。
- 输出字段与 `corpus/synthetic/qa.jsonl` 完全对齐 ⇒ `check_b1_b3_quality.py --qa` 一行不用改：
  `id` / `question` / `expected_document` / `expected_snippet` / `answer_ordinal` / `source` / `doc_id`。

一致性经验值：**法律条文 90–92%**（一条只讲一件事）；**制度台账 50–70%**（一段塞三四个数字）。
低于 50% 说明语料本身有歧义，先修语料，不要硬放大样本量。

### ⑤ 评测

```powershell
python tools\check_b1_b3_quality.py --stage eval --qa corpus\real\qa-labeled.jsonl `
  --kb-id 52f02f99-2468-46a5-91e9-e85f935c7761 --base http://localhost:18088/api/v1 `
  --mode HYBRID --topn 10 --threshold 0.6 --out .build\real-b1b3-9docs.json
```

- 只跑 `eval`（`ingest`/`embed` 已被 ③ 取代，别重复跑）。
- **必须先确认后端活着**：`curl.exe -s -o NUL -w "%{http_code}" http://localhost:18088/api/v1/auth/captcha`
  返回 200。`mvn spring-boot:run` 的 job 报 `exit code 1` **不代表进程死了**，判活只看端口监听。
- 277 条约 50 分钟（每条含查询理解 3 条 + 两路检索 + 重排）。中途后端挂了，**整批重跑**（已失败的不会补）。
- 读三条：`B1`（前 5 有答案句）/ `B3`（第 1 条既是同文档又含答案句）/ `doc_top1`（第 1 条来自正确文档）。

## 报告读数时的话术纪律

- 可以说："同一套脚本、同一条链路、同一种标注方式下，真实语料 B3 比模板化合成语料高 19 点。"
- 不可以写："本系统 B3 已达 90.6%" —— 问题由 LLM 从原文反向生成（它知道答案在哪段），绝对值偏高。
- **`doc_top1` 要单列**：它是唯一在真语料上暴露短板的读数（9 篇 93.9% < 2 篇 98.2% < 合成 97.4%），
  库变大、文档变像时它先掉。
- 1 条 = 0.36 点（277 条）⇒ 小数位不要对比。

## 扩展点（按这个顺序加，别动前面的阶段）

1. **加语料源**：新抓取函数放进 `tools/fetch_wikisource_law.mjs` 同级，输出 `.build/wiki-raw/*.txt`，
   后面三个阶段完全复用。
2. **加体裁**（企业制度汇编、管理办法）：清洗规则可能要加"多级编号"处理；
   标注一致性会从 92% 掉到 50–70%，**先小批（每篇 4 段）验一致率再全量**。
3. **加指标**：只改 `tools/check_b1_b3_quality.py` 的 `core_of` / 统计段，
   不要在两处各写一份判据（`docs/检索基线读数.md` 第二节是唯一真源）。
4. **换库**：改 KB id 与 `orgId` 即可；但**换语料必须重取基线**，不能拿旧读数当真语料水平。

## 已知坑清单（每一条都真实踩过）

| 坑 | 正确做法 |
|---|---|
| `docker exec -i ... psql -f -` 在 PowerShell 里触发 `AccessViolationException (AmsiScanBuffer)` | 用 Node `execFileSync('docker', [...], { input: sql })` |
| `corpus/` 被 `.gitignore` 忽略 | `git add -f corpus/real` |
| `git commit -F` 的文件带 BOM | `[IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))` |
| 后端反复 `exit code 1` | 判活看 `Get-NetTCPConnection -LocalPort 18088 -State Listen`；真僵死时 `Stop-Process -Force` 清进程再起 |
| 上传后立刻评测 | 一定先跑 ③ 的向量化，否则大面积 10061 报错 |
| 同一份语料改了切分规则 | 走「重新切分」入口（`POST /documents/{id}/resegment`），不要删档重传（会丢解析结果与人工改过的段标题） |
