# 知识助理平台 · 前端

这个平台的界面层。**接口契约先冻结、后端后接** —— 页面现在跑在 mock 上，
但 mock 与真实后端是**同一契约的两种实现**，切换只改一个环境变量。

**依赖白名单**：`element-plus` · `@element-plus/icons-vue` · `pinia` · `vue-router` · `axios` ·
`dayjs` · `lodash-es` · `mitt` · `echarts` · `@logicflow/*`（编排画布）· `@antv/layout`。
**不用**任何商业基座框架及其 CRUD 模板产物，不用 `vuex`，不用 `moment`（冗余的日期库），
不用非 ESM 版的 `lodash`。字体自托管（OFL-1.1），不引外部 CDN。

---

## 快速开始

```bash
npm install
npm run dev          # 开发：走 mock，无需后端  → http://localhost:5280/
npm run typecheck    # vue-tsc 类型检查
npm run build        # 生产构建（走后端，VITE_USE_MOCK=false）
npm run build:mock   # 生产构建但走 mock（下面几套校验都基于它）
```

mock 账号：`admin / admin`、`zhangyun / 123456`（知识运营）、`liwei / 123456`（应用配置）、
`chenjun / 123456`（普通用户）。后三个角色权限不同，可观察按钮显隐差异。

### 改动后跑这些

```bash
# 静态闸门
npm run typecheck                  # 类型
python check_template_tags.py      # SFC 模板配平（裸跑扫 src/ 全部 .vue）
python check_design.py             # 设计系统合规（14 个维度，见下）
python verify_refs.py              # mock 跨模块引用一致性

# 需要 dev server 在 5280 上跑（走 mock）
python smoke_test.py               # 基础设施回归：登录/守卫/路由/模型管理
python verify_features.py          # 功能点：各模块的设计要点真的做了吗
python verify_permissions.py       # 访问控制：四角色 × 各路由的门禁矩阵
python check_theme.py              # 设计令牌与字体分工是否真的生效
python check_canvas.py             # 编排画布几何（节点数/重叠/控件位置/被切）
python check_nodeconfig.py         # 节点与配置分离（点卡片开抽屉，逐节点）

# ★ 真栈闸门：dev server 要以 VITE_USE_MOCK=false 起，且后端 8080 + PG 15432 + 桩 11434 在跑
python check_knowledge_parse.py    # 解析链（Markdown 结构 / 失败原因可读）
python check_knowledge_form.py     # 建库向导 + 建档表单 + 生成预览
python check_segmentation_e2e.py   # 分段 / 向量化 / ★ 刷新页面不重置
python check_document_actions.py   # ★ 界面按钮真的生效（改名/启停/删除/分段启停/重向量）
python check_flow_walkthrough.py   # ★ 逐页走查 12 页，分三档记账
```

**这些都是独立脚本，退出码即结论**，可以直接当 CI 步骤。

> ★ **真栈闸门与 mock 闸门回答的不是一回事**：mock 闸门验"界面按契约渲染"，真栈闸门验"这条链在真后端上
> 真的走通了"。契约的两种实现（mock / 真后端）**长期有口径差**（mock 更宽松），所以一条流程能否交付，
> 只能看真栈那一道 —— 这也是新建 `check_document_actions.py` 与 `check_flow_walkthrough.py` 的原因：
> 前者管"**页面上的按钮点了有没有用**"，后者管"**逐页看下来哪一页是坏的**"，
> 而这两类既有的闸门都不覆盖。

---

## 六条防线，每条都对应一次真实漏检

| 防线 | 抓什么 | 抓不到什么 |
|---|---|---|
| `vue-tsc --noEmit` | 类型错误 | **SFC 模板语法错误**（不编译模板）、运行时响应式 bug、悬空引用 |
| `vite build` | 模板语法错误（未闭合标签等） | 运行时响应式 bug、悬空引用 |
| `verify_refs.py` | **跨模块 id 悬空** | 响应式 bug、视觉问题 |
| `verify_features.py` | 界面上功能点是否真的生效 | 视觉问题（"做了"≠"看着对"） |
| `check_design.py` | 内联色值 / 字号跌破下限 / 反模式 | **"看着对不对"** |
| `check_theme.py` | 令牌与字体是否真的落到计算样式上 | 同上 |

**为什么需要这么多层** —— 每一条都对应一次真实漏检：

1. **模板语法** —— 一个模块「类型检查 0 错误」，但模板少了一个闭合 `</div>`，构建直接失败。
2. **运行时响应式** —— 「类型检查 0 错误、构建通过」，但会话页的流式回答**一个字都渲染不出来**：
   往响应式数组里 `push` 了**原始对象**、之后在闭包外改它的属性，**不触发重渲染**。
3. **跨模块悬空引用** —— 各模块自己都能跑、页面都能渲染，但**三套应用 id 词汇表**让会话页的应用名、
   反馈页的「跳转对话」、看板的应用排行全部静默指向空 —— **不报错，只显示「—」**。
4. **视觉问题** —— 三套回归全绿，而用户报的"应用名被截断"一行断言都没覆盖（是全局样式表覆盖导致的，
   见 `design.md` 的 Provenance）。

> **写 mock 时记住 id 的所有者**：应用归 `fixtures/assistants.ts`，知识库归 `fixtures/knowledge.ts`，
> 会话归 `fixtures/conversations.ts`，模型归 `fixtures/models.ts`。
> **其它地方只能引用，不能自造一套。** 拿不准先跑 `verify_refs.py`。

> **写「流式回调里改列表项」的代码时注意**：`ref([])` 里 `push(rawObj)` 之后，改 `rawObj.x`
> 不会重渲染。要么入库前 `reactive({ ...raw })`，要么一律 `list.value.find(i => i.id === id)` 取代理再写。

---

## 接口已固定，先走 mock

这是本工程的核心约定：**接口契约先冻结，后端后接。**

```
pages/**  ──只用──▶  src/api/**（冻结的契约：类型 + 函数签名）
                          │
                          ├── USE_MOCK=true  ──▶  src/mock/**（handlers + fixtures）
                          └── USE_MOCK=false ──▶  src/lib/http.ts ──▶ 后端 /api/v1
```

- **`src/api/**` 是唯一契约**。页面只从这里取接口，**不直接引 `lib/http`**。
  改接口 = 改契约 = 破坏性变更（契约冻结约束的是"调用方不许绕过它"，不是"它不许增行"）。
- **mock 与真实后端是同一契约的两种实现**。切换只改 `.env` 的 `VITE_USE_MOCK`，**页面代码一行不动**。
- mock **按令牌识别用户**（`Authorization: Bearer mock_access_u1`），与真实 JWT 行为一致 ——
  不用模块级变量记登录态，否则一刷新登录态就丢。
- 契约里没有的接口，mock 抛 `404` 而不是返回空 —— 不把「没实现」伪装成「返回空」。

### 已冻结的契约（11 个领域）

| 领域 | 文件 | 说明 |
|---|---|---|
| identity | `api/identity.ts` | 登录、令牌、用户、角色、权限点 |
| modelcatalog | `api/models.ts` | 模型注册/加载/能力探测（能力三态、维度、窗口） |
| knowledge | `api/knowledge.ts` | 知识库、分类 |
| ingestion | `api/ingestion.ts` | 文档六态、真实进度、分段、切分预览 |
| retrieval | `api/retrieval.ts` | 三种检索模式、参数、命中测试与对比 |
| assistant | `api/assistants.ts` | 应用配置、发布前校验、访问凭证、API Key、导入导出。**应用分简单/编排两档**（`type`），
编排应用没有应用级模型（模型在各 AI 节点里），列表卡片按类型显示不同数据 |
| prompts | `api/prompts.ts` | 提示词类型与配置 |
| conversation | `api/conversations.ts` | 会话、消息、赞踩、免登录凭证解析 |
| feedback | `api/feedback.ts` | 反馈处理闭环（状态机、指派、沉淀） |
| analytics | `api/analytics.ts` | 概览、趋势、应用排行、质量观测 |
| **workflow** | `api/workflow.ts` | **编排契约**：17 种节点目录、存/校验/发布/版本、调试 |
| **stream** | `api/stream.ts` + `stream-types.ts` | **全平台唯一一套流式契约** |

`src/mock/handlers/` 11 个处理器全部就位 —— 界面现在跑的就是它们。

### 流式契约（唯一的）

这里固定为一套事件契约：

```
meta → refs → delta → artifact → done
                  └─ error（失败原因 + 下一步，之后仍走 done 收尾）
```

顺序是有意的：`meta` 先到，才谈得上「生成中可停止」；`refs` 先于 `delta`，才能边看答案边点引用。
换传输只替换 `lib/sse.ts` 或 `mock/stream.ts`，事件契约不变。

---

## 设计系统

界面层的**唯一真源是 [`design.md`](design.md)**，`tokens.css` 是它的机器可读镜像。主题是冷调工程白纸 +
发丝线分面 +**一个**电光钴蓝信号色 + 6px 紧圆角；深度靠线不靠影。

四条中文排印纪律（照搬英文配方会出错）：正文 ≥14px · **中文正文行高 ≥1.7** ·
**中文标题字距必须为 0**（负字距会让汉字笔画相撞）· **不用 `font-style: italic`**。

**页面里每一个颜色、字体、间距都必须写成 `var(--token)`**，不许内联色值 / 字族 / 裸 px
（`check_design.py` 会抓）。Element Plus 的 `--el-*` 由 `src/styles/element-plus.css`
单向映射到本系统的令牌 —— 页面不许直接写 `--el-*`。

---

## 目录结构

命名规则五条原则：① 用领域术语、对齐架构文档的模块名 ② 一个概念一个目录 ③ 单复数只留一种
④ 组件只放一处 ⑤ 目录名不承载版本信息。

```
src/
├─ api/            冻结的接口契约，一个领域一个文件
│  └─ legacy/        编排画布的旧形状适配层（唯一的删除点，见文件头注释）
├─ bootstrap/      入口装配：router / stores / permission
├─ components/     跨页面复用的通用组件（全平台唯一一处）
├─ config/         运行期配置（env.ts）
├─ constants/      权限点
├─ lib/            无框架依赖的能力层
│  ├─ http.ts        唯一请求出口（mock / 真实二选一）
│  ├─ sse.ts         真实流式实现（fetch + ReadableStream）
│  └─ token.ts       令牌存取（唯一出口）
├─ mock/           mock 传输层
│  ├─ dispatch.ts    method + path → handler 的路由（自动发现）
│  ├─ fixtures/      数据
│  └─ handlers/      处理器（导出 *Routes 即自动生效）
├─ pages/          页面，按领域模块分目录
│  ├─ _shell/        框架布局（侧栏 + 顶栏）
│  └─ <模块>/route.ts  模块自己的路由真相源
├─ router/         路由表（自动发现各模块 route.ts）
├─ stores/         pinia，按模块
└─ styles/         令牌 + 字体 + 基线 + 原子类
```

**约定**：页面私有组件放页面内 `components/`，跨页面复用的才上提到 `src/components/`。

---

## 两处为并行开发做的结构选择

多个模块并行开发时，**集中注册表是冲突源，也是「导入却忘了挂上」这类漏的温床**
（文件在、类型对、页面能开，但每次请求都 404）。所以：

| 位置 | 做法 |
|---|---|
| `mock/dispatch.ts` | 用 `import.meta.glob('./handlers/*.ts')` **自动收集**所有导出的 `*Routes` —— 新增领域只加文件，不改本文件 |
| `router/routes.ts` | 用 `import.meta.glob('../pages/*/route.ts')` 自动发现子路由 —— **每个模块拥有自己的 `route.ts`** |

**代价**：自动发现只拿到各 `route.ts` 的 `default` 导出（shell 子路由）。
**沉浸布局页（免登录公开页）挂不上顶层**，必须由 `routes.ts` 显式合并 ——
会话模块的 `immersiveRoutes` 就是这个约定解法。

### 路由级权限门禁只有一个声明点

`RouteMeta.permission` 是**唯一**声明处：守卫拦不拦、侧栏显不显、工作台的入门入口显不显，
都只读它。**不给导航项另加一个权限字段** —— 那样同一个权限有两处声明，
迟早漂移成「菜单看得见、点进去 403」。

---

## 几个刻意的工程取舍

| 决定 | 原因 |
|---|---|
| 后端地址来自 `.env` 的 `VITE_API_TARGET` | 换环境只改配置，不改代码 |
| 只用 Element Plus 一套 UI 体系 | 避免两套组件库并存导致的观感与行为不一致 |
| 只用 pinia | 官方推荐、类型友好、API 更简洁 |
| 只用 dayjs | 一个日期库就够，两个并存会让人不知道新代码该用哪个 |
| 令牌只有 `lib/token.ts` 一个出口 | 存取口散在多处时，登出漏清一处就是安全问题 |
| 全局 store 只存「当前是谁 + 权限点」 | 字典、标签页、上传临时文件塞进全局 store 会让状态归属失控 |
| 401 时静默刷新一次，并发请求排队等同一个刷新 | 否则一个页面同时发 5 个请求会触发 5 次刷新 |
| 接口失败给出**可行动**的原因 | 无业务包体时提示 HTTP 状态 + 让用户去查 `VITE_API_BASE` |
| `el-dialog` 一律 `append-to-body` | 否则会被 LogicFlow 节点的 `foreignObject` 裁掉 |
| 节点/画布内部的**下拉类**浮层一律**不写** `:teleported="false"` | 写了会把 popper 塞进 `foreignObject`，用户根本选不了。这条踩过：节点级必填项因此永远填不上，发布被永久拦住 |
| 节点卡点开才配（配置在抽屉里） | 一张卡塞 8 个表单字段时单卡高到 952px，9 张卡把画布缩放压到 0.25、字读不了 |
