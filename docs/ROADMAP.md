# ROADMAP

从当前状态到「可对外发布」的路线。**每一阶段都以可复核的数字收尾**，不用"做完了"当结论。

---

## 当前状态（实测，2026-09-25）

| 项 | 实测值 |
|---|---|
| 前端 | 264 个源文件 / 1.1 MB；111 个 `.vue`；9 个模块全部迁完 |
| 前端闸门 | `vue-tsc` **0 错** · 模板 **59/59** 配平 · 设计系统 14 维**全 0** · `build:mock` ✓ |
| 前端页面 | 17 条路由（含 `/public/:token` 公开沉浸页、`/assistants/:appId/workflow` 编排画布） |
| 接口契约 | `src/api/` **15 个文件 / 9 个域**，已冻结；`mock/handlers/` 11 个 | 
| 后端 | **只有 `backend/README.md`（模块 ↔ 契约对应表），零代码** |
| 老代码 | `_archive/` **25.15 GB / 35,437 文件** —— 已 `.gitignore`，不进公开仓库 |
| 内部材料 | `innerdoc/` 34 个文件 —— 已 `.gitignore`，不进公开仓库 |

**一个罕见的有利条件**：前端契约先冻结、mock 齐备 ⇒ 后端被外部约束，
且**前端一行不改就能切后端**（`VITE_USE_MOCK=false` + `VITE_API_TARGET`）。这是现成的联合验收手段。

---

## 阶段 0 · 仓库基线 ✅ 已完成

- [x] 根 `.gitignore`：**`_archive/` 与 `innerdoc/` 放在首段**（顺序有讲究，避免被后置规则盖掉）
- [x] `git init --initial-branch=main`
- [x] `innerdoc/` 建立，内部材料按来源镜像搬迁（`innerdoc/docs|tools|frontend/`）+ `innerdoc/README.md` 说明取舍
- [x] **首次提交前用 `git add -A --dry-run` 逐条核对**：414 个待加入文件、`_archive` 命中 **0**、最大单文件 237 KB
- [x] 清理因搬迁产生的悬空引用（`frontend/README.md`、根 `README.md`、`docs/系统设计方案/00,01`、`migration.ts`、`verify_features.py`）

> **为什么第 0 步必须先做**：没有基线，后面每一步都不可回滚。这一步之前不该动任何代码。

**验收口径**：`git check-ignore -v _archive innerdoc` 均命中；`git add -A --dry-run` 里 `_archive`/`innerdoc` 命中数为 **0**。

---

## 阶段 1 · 开源脱敏（前端收尾）

目标：**把仓库里一切"只有内部人才看得懂"的东西清掉**，让外部读者能独立读懂。

- [x] **`api/legacy/{dictbiz,document,setting}.ts` 的旧系统注释改写为中性描述**（✅ 已完成）
      —— 只改注释、逻辑零改动；用 `git diff -U0` 证明**全部改动行都是注释行**。
      三份适配层引用数 **1+2+0 → 0/0/0**。
      > 注：`document.ts` 本来就没有旧系统引用；三份里实际只有 2 份、共 3 处。
- [x] **`verify_refs.py` 假阳性修正**（✅ 已完成）—— 它会把「故意违法」的样例图
      （`fixtures/workflow.ts` 的 `sampleInvalidGraph`，用途就是含悬空 id 来验证"校验能拦下发布"）
      里的 5 处引用报成悬空。修法是**具名挖掉那一个块、不整文件跳过**，并把跳过数量**打印出来**
      （静默的例外迟早会变成藏真问题的地方）。金丝雀已验：注入真悬空引用后仍能报错并退出码 1。
- [x] **`overview` 页改造成产品首页 + 删掉三个死文件**（✅ 已完成）
      —— 一次动作清掉 **19/21**。`src/` 下旧系统引用 **24 处 / 7 文件 → 0 处 / 0 文件**。
  - `pages/overview/Index.vue` 重写为**工作台**：指标条（问答/活跃/满意度/待处理反馈，取现有
    `analytics` 契约，**未扩契约、未造数据**）+ 常用应用（`listTopAssistants`）+ 从这里开始
    （**入口按权限过滤**，与侧栏同源判据，避免点进去 403）+ 待办条
  - 删 `constants/migration.ts`（13 处）· `pages/pending/`（1 处）· `router/planned.ts`
    （`planned()` **零调用方**，随 pending 页一起成为死代码）
  - 配套改写：`_shell` 侧栏 · `routes.ts` 标题 · `forbidden`/`not-found` 的「回到…」
  - `components/dynamic-form/{types.ts,DynamicForm.vue}`：两处**否定式声明**（"不依赖
    avue/vuex/BladeX"）改写为中性表述 —— 对没听过这些产品的外部读者只是噪声
  - `frontend/package.json` 的 `description` 去掉旧系统品牌名（它是随包分发的元数据）
- [x] **`check_theme.py` 修一处静默丢覆盖**（✅ 已完成）—— 表头字体那条断言原本写成
      `if 有表格: 验`，落地页从「迁移总览」换成「工作台」后那张表没了 ⇒ 断言**静默消失**
      （10/10 → 9/9，无任何报红）。改为**在 `/analytics` 上无条件执行**，找不到表格即判失败。
      > 通则：`if 条件: 断言` 是覆盖的漏洞 —— 条件不成立时不会报红，只会少一条。
- [ ] **文档与配置里的旧系统引用 25 处**（`grep` 全库，排除 `innerdoc/`、`_archive/`）：
  - `docs/ROADMAP.md`（7 处）—— **需要重新定性**：一个对外发布的路线图不该以"清理前身系统"为目录，
    阶段一做完后本段应整段移除，内部清理过程移入 `innerdoc/`
  - `backend/README.md`（4 处）· `docs/系统设计方案/{00,05}`（3 处）·
    `docs/需求规格说明书/{00,01}`（4 处）—— 改写成中性描述（"前身系统"而非具体产品名）
  - `frontend/README.md`（4 处，含 3/5 两行把它定义成"某系统的迁移产物"）—— 属下面那条重写
- [ ] **根 `README.md` 重写**：现在那份讲的是"我们怎么搬过来的"，外部读者要的是"这是什么、能干什么、怎么跑起来"
- [ ] **`frontend/README.md` 重写**：删掉迁移进度章节，补「本地起服务 → 看演示 → 跑校验」的路径
- [ ] 补 `LICENSE`（**需先拍板**，见文末）、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`
- [ ] `.github/`：issue / PR 模板
- [ ] **依赖许可复核**（已实测，列在此处备查）：Element Plus MIT · LogicFlow **Apache-2.0** · @antv/layout MIT · 自托管三字体 **OFL-1.1** · sass MIT
- [ ] `screenshots/` 已 gitignore；README 用的演示图**另建 `docs/images/` 并刻意挑选**，不要直接把 60 张迭代图带进去

**验收口径**：`grep -rn "_archive\|qa-ui-3\|qa-server\|bladex\|avue" --exclude-dir=innerdoc --exclude-dir=_archive .` 返回 **0 行**；外部读者按 README 能从零跑到登录页。

---

## 阶段 2 · 后端（"服务"的落地）

`backend/README.md` 已经把「模块 ↔ 前端契约」对应表写好了，按它切模块即可。

**技术栈（已在 `01-架构设计` 拍定）**：Java 21 + Spring Modulith + **Spring AI** + pgvector（与业务库同库）。

**做法：不要从"九个域全实现"起，要按"能让哪几个页面从 mock 切真接口"增量推进。**

- [ ] **V0.1 · 打通一条主链**（`models` → `knowledge` → `retrieval` → `conversations`(SSE 流式) → `assistants`）
  目标：`/models`、`/knowledge`、`/assistants`、`/conversation` 四页在 `VITE_USE_MOCK=false` 下真实可用。
  - `models`：模型列表 + 能力探测（含新建时无 id 的 `probeBaseModelCapability`，契约里已留位）
  - `knowledge` + `ingestion`：建库、上传、切分、入库进度（**进度唯一真相源在后端**，契约已注明）
  - `retrieval` + `conversations`：检索 + 流式回答 + 引用回传
- [ ] **V0.2 · 身份与权限**：`identity` 域。**不自研**，用现成方案；但要满足契约里的权限点枚举与路由门禁四角色矩阵
- [ ] **V0.3 · `prompts` / `feedback` / `analytics`**：三个轻域
- [ ] **V0.5+ · `workflow` 执行引擎**：当初就是「**配置面进 MVP、执行引擎留 V1.0**」，配置面已完成，引擎是后端最重的一块
- [ ] `docker-compose.yml`：后端 + pgvector + 种子数据一键起（阶段 4 复用）

**验收口径**：把 `VITE_USE_MOCK` 置 `false`，阶段目标的页面**逐页打开、无 console 报错、数据来自真接口**；
`verify_refs.py` 仍绿（id 不悬空）。

---

## 阶段 3 · 页面功能补完（按"外人能不能自己上手"排序）

- [ ] **首次引导**：新用户进来看到 9 个空模块、**没有起点**。这是开源项目最影响第一印象的地方
      —— 需要一条「建知识库 → 传文档 → 建应用 → 试问答」的最短路径
- [ ] **`overview` 页重做**：它现在是内部的"迁移总览"看板，开源版应是**产品首页**
      （今日问答量、知识库健康度、最近使用的应用、待处理反馈）
- [ ] **「应用发布 → 公开问答页」这条链打通**：`/public/:token` 已有，需补分享入口与失效态
- [ ] **编排画布的执行状态**：引擎未上之前，发布出去的流程跑不了 —— 要么 UI 明示"配置已就绪、执行待后端"，
      要么先只交付"配置面"并写清边界
- [ ] 空态与错误态盘点：现在是"能用"级，外部用户对空态/失败态的容忍度低得多

**验收口径**：一个没看过任何内部文档的人，按界面提示能在 **10 分钟内**完成一次"建库→问答"。

---

## 阶段 4 · 开源工程化

- [ ] **CI**：`typecheck` + `build:mock` + 四套 `verify_*.py` + `check_*.py`（都是现成的、能直接当 CI 步骤）
- [ ] `docker-compose.yml` + `.env.example`（后端 + pgvector）
- [ ] 示例数据种子（让 clone 下来就能看到一个"有内容"的演示）
- [ ] 版本发布流程 + `CHANGELOG.md`
- [ ] 安全：`gitleaks` 之类的密钥扫描接进 CI（当前 `.env` 无密钥，但要防以后）
- [ ] 面向贡献者的「如何加一个模块」文档 —— 本仓库的自动发现约定（`import.meta.glob`）值得写清楚

---

## 需要先拍板的两件事

### ① 内部版与开源版要不要分叉？

`innerdoc/`、`_archive/`、以及阶段一那 24 处引用 —— 如果内部还要继续用这套老材料，
**建议开两个仓库/两条线**，不要在一条提交线上既想内部用又想开源。

当前选择的机制是 **`.gitignore` 忽略**（而不是"提交后再删"）：公开仓库里**从未存在过**这些内容，
就不存在"误推"这一整类错误。代价是内部材料没有版本历史 —— 需要历史时，正解是**另一个私有仓库**。

### ② LICENSE 选哪个？

- **Apache-2.0** —— 含专利授权条款，企业采用更放心；与已依赖的 LogicFlow 一致
- **MIT** —— 最宽松、最短，社区最熟

两者都允许商用与闭源二次分发。**建议 Apache-2.0**（本项目是平台型、要吸引企业贡献者）。
需在拍板后再补 `LICENSE` 并同步更新根 `README.md` 的徽章。

---

## 阶段依赖图

```
阶段0 基线 ✅
   └─→ 阶段1 脱敏 ──┬─→ 阶段2 后端 ──┐
                    └─→ 阶段3 页面 ──┴─→ 阶段4 工程化 ──→ 可对外发布
```

阶段 2 与 3 可以并行，但**都依赖阶段 1 先做完** —— 否则会把内部材料带进公开历史。
