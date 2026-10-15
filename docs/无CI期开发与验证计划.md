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

---

## 七、分支与合并纪律（2026-10-13 夜**两次**踩到，写下来免得第三次）

★★ **只要前面的 PR 是 `squash` 合并，就不要再叠层。**

| 现象 | 读数 |
|---|---|
| 叠层的 PR 被 `gh pr merge` 合进了**它的 base 分支**（不是 `main`） | `#99` 的 base 写成 F3 分支 ⇒ 收尾内容进了 F3 分支，`main` 一直没拿到 |
| 基于它开的下一个 PR 与 `main` **冲突** | `mergeable=CONFLICTING`（squash 造成的历史分叉：同一批文件在两个不相关的谱系上各改了一次） |
| `gh pr update-branch` 救不了 | ★ **它只把 base 并进 head：不改 base，也不消解 squash 的分叉** |

★ **正确做法**（今晚用过两次，均**无冲突**）：**从 `origin/main` 开新分支 + `cherry-pick`** 那一个提交。
★ 判据（可核对）：`gh pr merge` 若只回一句 `gh pr checkout … && git fetch origin main && git merge origin/main`，**那就是分叉了**，别再试第二次。
★ 今晚的代价：白跑两轮（`#99`/`#100` → `#101`；`#105` → `#106`）。

★ 同族纪律（`#49`）：**破坏性 git 操作会吃掉子代理的未提交改动** —— `reset --hard` 已经因此丢过 9 个文件；
要"干净基线"用 `git switch -c <new> origin/main`（切分支不动未提交改动），**绝不用 `reset --hard`**。

---

## 八、`.cmd` 闸门脚本的两条硬约束（2026-10-15 实测踩到，**改脚本前先读**）

`tools/run-frontend-gates.cmd` 是断供期的**唯一**前端读数来源，它两个坑**都会让"闸门红"与"脚本坏"混在一起**（本项目的头号病形态：**读数不可信**）：

| 坑 | 症状（逐字） | 约束 |
|---|---|---|
| ★ **行尾必须是 CRLF** | `'ayedexpansion' is not recognized…` · `'ser' is not recognized…` —— cmd.exe 把 `setlocal enabledelayedexpansion` 读断、逐行撕开 | ★ 用**任何编辑器/工具重写该文件后**，必须确认**行尾仍是 CRLF**（LF-only 的 `.cmd` 会被 cmd.exe 逐字节误读）；判据 = 文件头前 20 字节含 `13,10` |
| ★ **内容必须纯 ASCII** | 非 ASCII 注释被 cmd.exe 按 **GBK** 解析 ⇒ **吃掉后续行**（本项目已栽过：`.cmd` 里的中文注释连带吃掉了 `set VITE_USE_MOCK=false`，前端静默跑成 mock） | ★ 连**文档名**都不要写中文；要引用就用 ASCII 描述（"the no-CI plan under `docs/`"） |
| ★★ **`python -c` 不许用 `\` 续行** | `SyntaxError: unexpected EOF while parsing` —— cmd.exe **不认反斜杠续行**，它把字面 `\` 交给 Python | ★ 内联 Python **一律压成一行**；★ 症状是"**看起来像 5280 没起**"，而实际上**即使服务起着也会报同一个错** —— 这条最坑，因为它伪装成环境问题 |

★ **本次实测**（2026-10-15）：修前在 5280 **未起**时报 `SyntaxError`（伪装成环境问题）；修后同一场景报干净的
`[ERROR] frontend dev server is not answering on http://127.0.0.1:5280` + **该敲哪两条命令**，报告里 `SyntaxError` 命中 **0**、`not recognized` 命中 **0**。

★ **顺带一条设计（同批落的）**：最后两道闸门（`verify_features` / `smoke_test`）需要**真栈**（后端 + 桩），
而前 11 道只需 dev server。⇒ 脚本现在**先探后端 18088**，探不到就把那两道记为 **`SKIPPED`** 并在摘要里单独计数
（★ **`SKIPPED` 是"缺读数"，绝不是"通过"** —— 不许让它读起来像绿的）。

---

## 九、本机启动器住在 `scripts/`（**不在 `%TEMP%`**）

★ **起因**：2026-10-15 早上发现 `%TEMP%` 下的三个启动器**全都没了**（临时目录被清理）⇒ 用户**点不到**，
而"服务由用户起、agent 只做只读检查"这条纪律当场的表现就是**整条读数链断掉**（前端闸门跑不了、桩起不来）。
⇒ **真源进仓库**，`%TEMP%` 只留**两行薄包装**（`call` 到仓库脚本），两边不会漂移。

| 脚本 | 起什么 | 端口 | 谁要它 |
|---|---|---|---|
| `scripts/aip-fe-live.cmd` | 前端 dev（**mock**） | 5280 | ★ 11 道浏览器闸门 |
| `scripts/aip-fe-real.cmd` | 前端 dev（**`VITE_USE_MOCK=false`**） | 5280 | 真栈两道（`verify_features` / `smoke_test`） |
| `scripts/aip-be-live.cmd` | 后端（`SERVER_PORT=18088`） | 18088 | 真栈两道 + 手工端到端 |
| `scripts/aip-stub.cmd` | 假模型（`backend/tools/openai_stub.py`） | 11434 | 真栈两道 + ★ 三个桩依赖测试类 |

★ **`--check` 是这条纪律的补丁**：agent **不许起服务**，但**可以验"路径与前置对不对"** ⇒
四个启动器都支持 `cmd /c "scripts\aip-fe-live.cmd" --check`（只打印解析出的路径 + npm/python/包装脚本在不在，**不起服务**）。
★ 注意 **5280 同一时刻只能一种模式** ⇒ 13 道闸门要**两趟**（先 mock 跑 11 道，再真栈跑 2 道）。
★ 详见 `scripts/README.md`（含 CRLF/ASCII 两条硬约束与 18088 的由来）。


