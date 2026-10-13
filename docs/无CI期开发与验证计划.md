# 无 CI 期：开发与验证计划（2026-10-13 起 · 至额度恢复）

> **背景**：GitHub Actions 因账户额度 / 付款限制**无法启动**（三个 job 全部 0 步即失败；在 `main` 手触发同样失败）。
> 逐字判据与纪律见 **Issue `#102`**。★ 本文件回答一件事：**这个月怎么继续开发，而不用"CI 绿了"当结论。**

---

## 一、CI 原来提供了什么（逐条，**判据一条都不改**）

| # | CI 的那一道 | 内容 |
|---|---|---|
| 1 | **后端 · 编译 + 四类闸门** | stub 起在自己人手里 → 装 OCR → **构建自制 PG 镜像** + `verify_image_pg.py` 自检 → `mvn verify` → **零用例闸门**（`tools/check_zero_tests.py`，防"Tests run: 0 报成绿"） |
| 2 | **前端 · 静态闸门** | `verify_refs` · `check_template_tags` · `check_design` · `check_error_codes` · `check_workflow_node_types` |
| 3 | **前端 · 11 道浏览器闸门** | `check_theme` `check_nodeconfig` `check_node_menu` `check_canvas` `check_collapse` `check_history` `check_palette` `check_pickers` `check_assistants` `check_singleton` `check_publish`（★ 自带 dev server，**需要绑端口**） |
| 4 | **前端 · 类型 + 构建** | `npm run typecheck` · `npm run build:mock` |
| 5 | **前端 · 功能点 / 冒烟** | `verify_features.py` · `smoke_test.py`（★ **自带 dist 服务，需要绑端口**） |
| 6 | **真栈 · 端到端闸门** | PG + 桩 + 后端 + 前端**真栈**跑 `check_*_e2e.py` 一族（扫描件 / 分段 / 密级 / 查询理解 / 多跳 / 走查 / 权限矩阵 …）—— **九条** |

---

## 二、★ 断供期替代矩阵（谁跑、跑什么、判据是什么）

| CI 的那一道 | 本机等价读数 | 谁跑 | 判据 |
|---|---|---|---|
| 1 后端四类 | ★ **单类跑**：`cmd /c "backend\mvn-jdk25.cmd -B -f backend/pom.xml -o test -Dtest=<一个类名>"`（Docker 已起 ⇒ **真栈类也能跑**）；`-o compile` / `-o test-compile` | **我** | 相关类 `Failures: 0, Errors: 0`；★ **绝不用 `-Dtest=A,B`**（它静默不跑却报 SUCCESS） |
| 1 零用例闸门 | `python tools/check_zero_tests.py backend/target/surefire-reports` | **我** | 无"零用例"类 |
| 2 静态 5 道 | 原样跑（**不绑端口**） | **我** | 每道 exit 0 |
| 3 ★★ **11 道浏览器闸门** | ★ **无法本机替代**（沙箱不允许绑 4209/4205/4210，实测 `WinError 10013`） | ★ **你**（一键脚本见 §四） | 11 道全绿 |
| 4 类型 + 构建 | `npm run typecheck` · `npm run build:mock` | **我** | 0 错 / 构建成功 |
| 5 功能点 / 冒烟 | ★ 同 3（自带 dist 服务）⇒ 一并交给你 | ★ **你** | 2 道全绿 |
| 6 真栈九条 | ★ **你起三件服务**（`aip-be-live.cmd` / `aip-fe-live.cmd` / `aip-stub.cmd`），**我跑脚本**（它们只连 5280，不绑端口） | 你起 + 我跑 | 九条全绿 |

★ **格式闸门**（CI 里由 `mvn verify` 带出）：`spotless:apply` → `spotless:check` —— ★ **动过 Java（哪怕只改注释）必须先 apply**（本批已两次被它拦下）。

---

## 三、本月开发计划（批次 × 验证方式）

> 排序依据：**先做"本机可验证"的部分**，把"只能在 CI 里验证"的留到恢复后一次性补。

| 批 | 内容 | 本机可验证 | 需要你 | 备注 |
|---|---|---|---|---|
| **0** | ★ 本文件 + 一键脚本（§四）+ `#102` 记账 | 文档 | — | 让"断供"这件事**可执行**，而不是靠记忆 |
| **1** | ★ **`03 §4.7` 的 SSE 六条数值与形状写成契约测试**（假模型，时序可断言：`refs` 先于首个 `delta` · **恰好一次 `done`**（正常/停止/断开/超时）· 心跳 · 三态终态 · `tokens` · 框架假设断言） | ✅ 真栈类 + 单测 | — | `§4.7.4` 第 1 条：**"提前一周写测试"**；它把"最难的单点"变成**红的测试** |
| **2** | `#92` 的 `asSubject` 实现（+ `platform` 侧新端口 + 三条判据：匿名走通 / 登录用户不放大 / 金丝雀） | ✅ 真栈 | — | 公开面入口的**唯一**阻塞项 |
| **3** | `cv_conversation` 建表（**带 `max_classification`**，`#27` 裁定）+ 后端四态码 emit + `PublicContext.maxClassification` 改必填 | ✅ 真栈（`MigrationIntegrationTest` / `ContractDriftTest`） | — | 表不带那列就要返工 |
| **4** | SSE 真链路（`refs` 时序 · 停止/重生成 · 三态 · advisor 挂点 `order=250`） | ✅ 单测 + 真栈类 | — | 本批**前端改动少**（会话页已在） |
| **5** | 前端收口（会话页接线 + 引用栏） | ⚠️ 类型/构建我跑；**11 道闸门要你跑** | ★ 跑 11 道 | ★ 断供期**前端改动集中到最后一次做**，一次性交你验 |
| **6** | 第 18 周：赞踩 + **三场景脚本 S1/S2/S3** + A1–A9 断言 | ⚠️ 混合 | ★ 起服务 + 跑浏览器闸门 | 验收那条必须在真栈上跑 |
| **7** | **B1–B7 实测**（命中率 / 可用率 / 首字延迟 / 吞吐） | ⚠️ 需要语料 | ★ 语料（§6.1 P1） | ★ **唯一的外部阻断项**：≥50 份文档 + ≥50 标注问题 + ≥2 名评审人 |

★ **每批合并前必须有的东西**（断供期的最小集合）：相关单类测试读数 · `spotless:check` · `TestNamingConventionTest` · `check_error_codes` · `typecheck`（**有前端改动时**）；
并在 PR 正文里**逐条写明**"哪些 gate 有替代读数、哪些没有"。

---

## 四、交给你的一键脚本（★ 只在你本机跑）

```
tools\run-frontend-gates.cmd     ← 跑 11 道浏览器闸门 + verify_features + smoke_test
```

- ★ **用法**：先起前端 dev（`%TEMP%\aip-fe-live.cmd`，本脚本会先探 5280、探不到就**直接告诉你该起哪个**），然后
  `cmd /c "tools\run-frontend-gates.cmd"`；把末尾那行 `Full report: …` 贴回来即可（或直接说"跑完了"）。
- 报告落 `.git\frontend-gates-report.txt`（**永远不会被提交**），含**逐道 PASS/FAIL** 与末尾汇总（`gates run: N failed: M`）。
- ★ 设计约束三条（都是踩过的坑）：**纯 ASCII**（`.cmd` 里的中文注释会被 cmd.exe 按 GBK 读、曾把同文件的 `set` 吃掉）；
  **自带服务的那两道（`verify_features` / `smoke_test`）也在这个脚本里**（它们自己起 dist 服务，无需额外准备）；
  ★ **脚本自己不启动服务**（服务一律你起 —— 从 agent 的调用里起长驻服务会卡会话，本项目已记）。

---

## 五、恢复后的补验包（★ 这是本计划唯一的"欠账"）

1. ★ **在 `main` 上跑一次全量 CI**（三个 job 全量）⇒ **判据：三 job 全绿**；
2. 它必须覆盖**断供期间合并的全部提交**（`git log --oneline <断供起点>..main` 逐条核对）；
3. ★ 若中途有"你有读数、CI 没读数"的格子（11 道闸门），那次 CI 就是**交叉复核**：两边都绿才算过；
4. ★ **不许把这条降级成"有空再跑"** —— 它是**唯一**能回答"断供期间那批改动到底行不行"的读数（`#102`）。

---

## 六、红线（断供期一样不许越）

1. ★ **不许把"CI 未跑"写成"CI 通过"** —— 二者的读数完全一样，只有**写下来的那句话**能区分；
2. ★ **不许用 `-Dtest=A,B`**（静默不跑却 BUILD SUCCESS，本项目已记的坑）；
3. ★ **不许改任何已应用的迁移**（Flyway checksum 一破，后端起不来）；
4. ★ **不许用破坏性 git 操作**（`reset --hard` / 强推）—— 本批已因此丢过 9 个文件，纪律见 `#49`；
5. ★ **不许"本机跑不了就不跑"** —— 跑不了的就**写下来**："这一格本轮没有读数 + 谁在哪一批补"。
