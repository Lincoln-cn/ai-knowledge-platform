# frontend

知识助理平台前端。**从零搭的脚手架**，正在把 `_archive/qa-ui-3` 的可复用内容按目标设计迁进来。

**明确不依赖**：`@smallwei/avue`、`vuex`、`moment`、`lodash`（用 `lodash-es`）、以及 BladeX 的任何构件。

---

## 快速开始

```bash
npm install
npm run dev          # 开发：走 mock，无需后端
npm run typecheck    # vue-tsc 类型检查
npm run build        # 生产构建（走后端，VITE_USE_MOCK=false）
npm run build:mock   # 生产构建但走 mock（下面三套校验都基于它）

# 四层校验（各自独立，建议改动后全跑）
python verify_refs.py        # ① mock 跨模块引用一致性：有没有悬空的 appId/kbId/conversationId
python smoke_test.py         # ② 基础设施回归：登录/守卫/路由/模型管理
python verify_features.py    # ③ 功能点校验：各模块的设计要点真的做了吗
python verify_permissions.py # ④ 访问控制：四角色 × 各路由的门禁矩阵
python check_template_tags.py src/**/*.vue   # 工具：SFC 模板配平（见下「四条防线」）
```

mock 账号：`admin / admin`、`zhangyun / 123456`（知识运营）、`liwei / 123456`（应用配置）、`chenjun / 123456`（普通用户）。后三个角色权限不同，可观察按钮显隐差异。

### 四条防线，缺一条就会漏

| 防线 | 抓什么 | 抓不到什么 |
|---|---|---|
| `vue-tsc --noEmit` | 类型错误 | **SFC 模板语法错误**（不编译模板）、**运行时响应式 bug**、**悬空引用** |
| `vite build` | 模板语法错误（未闭合标签等） | 运行时响应式 bug、悬空引用 |
| `verify_refs.py` | **跨模块 id 悬空**（引用不存在的 appId / kbId / conversationId） | 响应式 bug |
| `verify_features.py` | 界面上功能点是否真的生效 | — |

**这四条都是实战打出来的，每一条都对应一次真实漏检**：

1. **模板语法** —— 一个模块「类型检查 0 错误」，但模板少了一个闭合 `</div>`，构建直接失败。
2. **运行时响应式** —— 一个模块「类型检查 0 错误、构建通过」，但会话页的流式回答**一个字都渲染不出来**：往响应式数组里 `push` 了**原始对象**、之后在闭包外改它的属性，**不触发重渲染**。
3. **跨模块悬空引用** —— 各模块自己都能跑、页面都能渲染，但**三套应用 id 词汇表**（`as1` / `a1` / `app1`）让会话页的应用名、反馈页的「跳转对话」、看板的应用排行全部静默指向空 —— **不报错，只显示「—」**。

> **写 mock 时记住 id 的所有者**：应用归 `fixtures/assistants.ts`，知识库归 `fixtures/knowledge.ts`，会话归 `fixtures/conversations.ts`，模型归 `fixtures/models.ts`。**其它地方只能引用，不能自造一套。** 拿不准就先跑 `verify_refs.py`。

> **写流式/回调里改列表项的代码时注意**：`ref([])` 里 `push(rawObj)` 之后，改 `rawObj.x` 不会重渲染。要么入库前 `reactive({ ...raw })`，要么一律 `list.value.find(i => i.id === id)` 取代理再写。

---

## 接口已固定，先走 mock

这是本工程的核心约定：**接口契约先冻结，后端后接**。

```
pages/**  ──只用──▶  src/api/**（冻结的契约：类型 + 函数签名）
                          │
                          ├── USE_MOCK=true  ──▶  src/mock/**（handlers + fixtures）
                          └── USE_MOCK=false ──▶  src/lib/http.ts ──▶ 后端 /api/v1
```

- **`src/api/**` 是唯一契约**。页面只从这里取接口，不直接引 `lib/http`。改接口 = 改契约 = 破坏性变更。
- **mock 与真实后端是同一契约的两种实现**。切换只改 `.env` 的 `VITE_USE_MOCK`，页面代码一行不动。
- mock **按令牌识别用户**（`Authorization: Bearer mock_access_u1`），与真实 JWT 行为一致 —— 不用模块级变量记登录态，否则一刷新登录态就丢。
- 契约里没有的接口，mock 抛 `404` 而不是返回空 —— 不把「没实现」伪装成「返回空」。

### 已冻结的契约

| 模块 | 文件 | 说明 |
|---|---|---|
| identity | `api/identity.ts` | 登录、令牌、用户、角色、权限点 |
| modelcatalog | `api/models.ts` | 模型注册/加载/能力探测（思考三态、维度、窗口） |
| knowledge | `api/knowledge.ts` | 知识库、分类 |
| ingestion | `api/ingestion.ts` | 文档六态、真实进度、分段、切分预览 |
| retrieval | `api/retrieval.ts` | 三种检索模式、参数、命中测试与对比 |
| assistant | `api/assistants.ts` | 应用配置、发布前校验、访问凭证、API Key、导入导出 |
| conversation | `api/conversations.ts` | 会话、消息、赞踩、免登录凭证解析 |
| feedback | `api/feedback.ts` | 反馈处理闭环（状态机、指派、沉淀） |
| analytics | `api/analytics.ts` | 概览、趋势、应用排行、质量观测 |
| **stream** | `api/stream.ts` + `stream-types.ts` | **全平台唯一一套流式契约** |

### 流式契约（唯一的）

原项目有 4 个来源各自解析（`api/chat.js`、`api/chat/chat.js`、`workFlowChat` 内手写、`axios.js postStream`），这里固定为一套事件契约：

```
meta → refs → delta → artifact → done
                  └─ error（失败原因 + 下一步，之后仍走 done 收尾）
```

`meta` 先到，才谈得上「生成中可停止」；`refs` 先于 `delta`，才能边看答案边点引用。换传输只替换 `lib/sse.ts` 或 `mock/stream.ts`，事件契约不变。

### mock 实现进度

| 领域 | 状态 |
|---|---|
| identity | ✅ 已实现（登录/用户/角色/权限点） |
| 其余 8 个领域 | ⏳ 契约已冻结，handlers 随模块迁移逐个补 |

---

## 目录结构

命名规则五条原则：
① 用领域术语、对齐《01-架构设计》的模块名 ② 一个概念一个目录 ③ 单复数只留一种 ④ 组件只放一处 ⑤ 目录名不承载版本信息。

```
src/
├─ api/            冻结的接口契约，一个领域一个文件
├─ bootstrap/      入口装配：router / stores / permission
├─ components/     通用组件（全平台唯一一处）
├─ composables/    组合式函数
├─ config/         运行期配置（env.ts）
├─ constants/      权限点、迁移进度表
├─ lib/            无框架依赖的能力层
│  ├─ http.ts        唯一请求出口（mock/真实二选一）
│  ├─ sse.ts         真实流式实现（fetch + ReadableStream）
│  └─ token.ts       令牌存取（唯一出口）
├─ mock/           mock 传输层
│  ├─ dispatch.ts    method + path → handler 的路由
│  ├─ fixtures/      数据
│  └─ handlers/      处理器
├─ pages/          页面，按领域模块分目录
│  └─ _shell/        框架布局（侧栏 + 顶栏）
├─ router/         路由表（未迁移模块统一指向 pending 页）
├─ stores/         pinia，按模块
└─ styles/         设计令牌 + 原子类
```

**约定**：页面私有组件放页面内 `components/`，跨页面复用的才上提到 `src/components/`。

---

## 迁移进度

**9 个模块全部迁移完成。** 应用首页「迁移总览」就是进度表，数据在 `constants/migration.ts`。

> **`status` 由验证方填，不由迁移方自评。** 迁移方的产出必须经独立校验复核通过后才改状态 —— 让生产者自己宣布完成，是把验证这件事作废。

迁移一个模块的动作：确认 `api/` 契约 → 在 `mock/handlers/` 补实现（导出 `*Routes` 即自动生效）→ 在 `pages/<模块>/` 建页面 → 把该模块 `route.ts` 里的 `planned(...)` 换成真实组件。

### 为并行开发做的两处结构选择

多个模块并行开发时，**集中注册表是冲突源，也是「导入却忘了挂上」这类漏的温床**（文件在、类型对、页面能开，但每次请求都 404）。所以：

| 位置 | 做法 |
|---|---|
| `mock/dispatch.ts` | 用 `import.meta.glob('./handlers/*.ts')` **自动收集**所有导出的 `*Routes` —— 新增领域只加文件，不改本文件 |
| `router/routes.ts` | 用 `import.meta.glob('../pages/*/route.ts')` 自动发现子路由 —— **每个模块拥有自己的 `route.ts`** |

**代价**：自动发现只拿到各 `route.ts` 的 `default` 导出（shell 子路由）。**沉浸布局页（免登录公开页）挂不上顶层**，必须由 `routes.ts` 显式合并 —— 会话模块的 `immersiveRoutes` 就是这个约定解法。

### 校验「声明 vs 事实」的做法（通则）

**`constants/migration.ts` 的 `status` 是声明，不是事实。** 一个状态校验器要把声明与六类事实逐项对照：
模板配平、路由指向（真实页面 / 占位 / 根本没声明）、页面文件存在、契约文件存在、
mock handler 存在**且导出了 `*Routes`**（文件在但导出名不对 = 没挂）、以及归档目录是否被动过。

然后逐个模块真实打开一次，断言：页面不是占位页、无控制台报错、**页面里不出现「mock 未实现该接口」**
（这是「页面在跑但接口全挂」的唯一可靠信号）、无错误提示条、非空白页。

> 「归档是否干净」应当换成「**相对归档基线是否变化**」—— 归档那一刻目录本身就带未提交改动，
> 「干净」这条永远会红，而一个永远会红的检查等于没有检查。

（针对本仓库老代码归档的那份具体校验脚本已随内部材料移出 `frontend/`。）

---

## 与原项目的行为差异（有意为之）

| 项 | qa-ui-3 | 本工程 |
|---|---|---|
| 后端地址 | 硬编码内网 IP `172.16.20.130` | `.env` 的 `VITE_API_TARGET`，默认 `127.0.0.1` |
| UI 体系 | avue + Element Plus 双栈并存 | 只用 Element Plus |
| 状态库 | vuex（127 文件）+ pinia（25 行的空壳） | 只用 pinia |
| 日期库 | moment + dayjs 并存 | 只用 dayjs |
| 流式 | 4 个来源各写一套 | 1 套契约，见上 |
| 令牌 | access/refresh 散在 vuex、localStorage、cookie | 只有 `lib/token.ts` 一个出口 |
| 全局 store | 字典、标签页、上传临时文件都塞进去 | 只存「当前是谁 + 权限点」 |
| 401 处理 | — | 静默刷新一次，并发请求排队等同一个刷新 |
| 接口失败文案 | — | 无业务包体时给出可行动原因（HTTP 状态 + 提示查 `VITE_API_BASE`） |
