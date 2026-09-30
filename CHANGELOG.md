# 变更记录

> **本文件写"这一版能做什么"，不写"改了哪些文件"**（`ROADMAP §4.7.1` 判据 4）。
>
> 版本号规则见 `ROADMAP §4.3`：`0.<阶段>.<周>` —— **补丁位 = 周**，每周一个可发布的版本。
> 也就是说，这张表里的每一行都对应"一周结束时可演示的东西"，而不是一次提交。

---

## 0.2.1 · 第 6 周 · M2-b Tika 解析成 Markdown（含噪声剥离与实测数字）

### 这一版能做什么

**上传 DOCX/PDF → 看到解析后的 Markdown**（标题、列表、表格在），并给出两张**实测数字表**：

```
解析   DOCX / PDF / TXT / MD → Markdown（标题层级 · 有序/无序列表 · 表格）
       GBK 编码的中文不乱码（Tika CharsetDetector，12 §一）
剥离   页眉页脚 · 页码 · 目录页 · 版权行 · 空白段落（阈值保守：重复 ≥3 + 长度 <60 + 非结构行）
失败   failReason **可读**（不是堆栈）+ 保留已缓冲部分；解析出 0 字符 ⇒ **不算成功**
```

### ★ 两张数字表（`docs/技术结论/0.2.1-解析实测.md`，`mvn test -Dtest=ParseMetricsTest` 可刷新）

| 结构丢失率 | | 噪声剥离误杀率 | |
|---|---:|---|---:|
| structured.docx | **0.0%** | noisy.docx | **0.0%**（12/12 剥掉） |
| structured.pdf | **66.7%** | noisy.pdf | **0.0%**（12/12） |
| noisy.docx | **0.0%** | | |
| noisy.pdf | **25.0%** | | |
| 扫描件 | **100%** | | |

方法：基准 = 夹具脚本写入的 `ground-truth.json`（标题+列表项+表格单元格、正文行），输出按 Markdown 结构语法计数 —— **数字可复现刷新，不是手抄**。
★ PDF 结构丢失率高是**已知的**（`12 §2.2`：PDF 未打结构 tag）；扫描件 100% 是无文本层，**待第 9 周 OCR 链**。

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **Tika 依赖**（`tika-bom 3.3.1` + core + pdf/microsoft/text 三模块） | 按 `12 §一` 精准组合，**不引** `tika-parsers-standard-package`（会拖 OCR/CAD/音视频） |
| 2 | **解析链** → Markdown | ★ Word 列表归一（OOXML 只给 `p` 打 class，要包成 `ul/ol/li`，否则 Markdown 没列表语法）；★ 关掉 PDF 的 `catchIntermediateIOExceptions`（默认会吞掉中间 IOException ⇒「页面丢了」静默发生） |
| 3 | ★ **0 字符 ≠ 成功** | 输入非空却抽出 0 字符（PDFBox 吞掉损坏、扫描件无文本层）⇒ 失败 + 原因指向「扫描件需走 OCR / 空文档 / 文件损坏」。静默产出空文档是最坏的失败形态（界面显示「已解析」但内容是空的） |
| 4 | **噪声剥离五条** | 页眉页脚 / 页码 / 目录页 / 版权行 / 空白段落；每条规则一组「能剥掉 + 不误杀」反例 |
| 5 | **两张数字表** + 可复现夹具 | `generate_parse_fixtures.py` 造 12 个样张（含 GBK / 加密 / 截断 / 空文档 / 含噪声 / 扫描件），`ParseMetricsTest` 产出数字 |

### 验证记录

```
mvn -B -f backend/pom.xml verify → BUILD SUCCESS · Tests run: 221（基线 195 → 204 → 221）
闸门  check_knowledge_form.py / check_models_form.py 保持不破
```

### ★ 这一版踩到的坑（都是「静默掉率 / 静默失败」那一类）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | ★ **目录剥离的召回从 12/12 静默掉到 3/12** | Tika 的 `ToMarkdownContentHandler` 在**段落间插空行** ⇒「连续 N 行」类规则把空行当成了**边界** | 空行算**间隙**不算边界 |
| 2 | **短噪声行误命中正文** | 页码「3」用 `contains` 匹配到列表项「3. 归档备案」 | 计量区分「整行相等」与「行内包含」 |
| 3 | **页眉/页脚剥不掉** | 规则前提是重复 ≥3 次；夹具只写了 section header/footer 各一次 | 生成脚本让页眉在正文流里重复 |
| 4 | ★ **「中途失败」的真实夹具造不出来** | PDFBox 对坏流/悬空引用/Crypt/JBIG2 一律吞掉、仍算成功；POI 解析 DOCX 是**先 DOM 读整个 `document.xml`** —— XML 一坏就整体失败、一字不吐（试过 zip 截断、document.xml 截断、坏 zlib、坏 xref） | 「保留部分正文」钉在 **`ParseResult`/`Document` 的契约**上（handler 缓冲了什么，失败时就必须留下什么），parser 的 catch 分支已按此实现。**记为欠账**：能稳定产出中途失败夹具时补端到端断言 |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | **「中途失败」缺端到端夹具** | 见上表 4。契约已钉，端到端断言待补 |
| 2 | **PDF 结构丢失率 66.7%** | `12 §2.2` 已知（PDF 未打结构 tag）。若要降低需打 tag 的 PDF 源或走版面分析 |
| 3 | **扫描件 100% 丢失** | 无文本层，**待第 9 周 OCR 链**；0 字符规则已把这条路的提示做好 |

---

## 0.2.0（进行中）· 第 5 周 · M2-a 建档（密级必填 + ceiling）

### 这一版能做什么

**建知识库 → 给文档建档，密级从头管到尾**：

```
POST /api/v1/knowledge-bases                    → 必须选「密级上限」（ceiling），缺了 400
POST /api/v1/knowledge-bases/:id/documents      → 文种 / 责任部门 / 密级 **三项必填**，缺哪项点哪项
                                                 文档密级 > 库 ceiling ⇒ 400「文档密级（机密）高于
                                                 知识库的密级上限（秘密）—— 请提高库的上限…」
GET  /api/v1/knowledge-bases                    → 按 dataScope 过滤（拼进查询）
GET  /api/v1/knowledge-bases/:id                → 看不见 ⇒ **404**（不是 403）
POST /api/v1/knowledge-bases  + clientRequestId → 同一键重试 ⇒ 返**首次那条**（不报「已存在」）
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **知识库 7 端点** + 迁移 V12 | 含 categories / enabled；`kb_knowledge_base_grant` 用 `07 §8.1` 的可长形状（不用 `{userIds, roleCodes}`） |
| 2 | ★ **第一个归属资源落地** | `ownerId` 由后端写（JWT 的 `sub`，**不接受前端传**）；`visibility` 默认 `PRIVATE`（一律收窄）；**列表按 `dataScope` 拼进查询**（`Specification`，search/count 共用一份谓词）；**看不见 ⇒ 404** |
| 3 | ★ **幂等** `clientRequestId` | 同一键重试 ⇒ 返首次那条。★ 唯一键取 `(client_request_id, owner_id)` 而非全平台唯一 —— 幂等是"同一调用方的重试"，全局唯一会让用户 B 撞上 A 的键时莫名失败，也多一处信息泄漏面 |
| 4 | **ceiling（`maxClassification`）服务端必填** | 缺失 ⇒ 400 说清原因，不静默给默认值（`13 §6`：默认要么全放行要么全拦，都是错的） |
| 5 | **文档建档** + 迁移 V13 | 三项必填（`12 §5.1`：决定召回前缀与安全过滤，**不能有默认值**）；ceiling 校验的拒绝原因**带两个密级的中文展示名**（机器码进错误消息不可读）；**可见性继承库**（同一判定，不另写一套） |
| 6 | **前端** | 建库表单加「密级上限」· 文档三项必填 · ceiling 超限把后端原因**原文**展示（不代写）· 越权深链给可读提示 · 新闸门 `check_knowledge_form.py` |
| 7 | 契约**只加可选字段** | `KnowledgeBase.maxClassification?` · `DocumentItem.docType?/department?/classification?` · 导入入参同名三项 |

### 验证记录（本地实跑）

```
后端  mvn -B -f backend/pom.xml verify → BUILD SUCCESS · Tests run: 194（189+5）
前端  typecheck / build:mock / verify_refs / check_template_tags / check_design → 通过
闸门  check_knowledge_form.py  24/24（mock）· 24/24（真栈）
      check_models_form.py     38/38（mock）· 38/38（真栈）—— 未破坏
★ 联调（真前端代理 + 真后端 + 真 PG）
    三项必填逐项点名（「文种必填」「责任部门必填」「密级必填」）
    ceiling：等于/低于放行；高于 ⇒ 400 +「文档密级（机密）高于知识库的密级上限（秘密）…」
    越权：看库 ⇒ 404、看其文档 ⇒ 404、建档 ⇒ 404（可见性继承库）
    ★ 写操作探针（临时给 USER 开动作权限、不动它的 SHARED 范围）：
      越权建档 ⇒ 404 且**没写进去** —— 7/7；库侧同样 12/12
```

### ★ 这一版踩到的坑

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | ★ **真栈建库直接 400「请求体不是合法 JSON」** | `importType` 还是小写 `local`/`online` —— **枚举换码漏了它**（后端是 `LOCAL`/`ONLINE`） | 改成 `SCREAMING_SNAKE_CASE`（`14` 规范） |
| 2 | **批 2 的 TDD 红阶段没留证据** | 测试先于实现写了，但**没先跑出红再实现** —— 断言先于实现存在，红→绿证据链不完整 | 如实记录。下批要求红阶段必须有构建日志 |
| 3 | **真栈 `documents` 端点 404** | `live-run.jar`（21:06）比 `DocumentController.java`（21:58）**旧** —— jar 与源码脱节 | 跑真栈闸门前先核对 jar 时间戳 vs 最新源码 |
| 4 | **契约形状差**：后端收 `names[]`，契约写的是 `uploadIds` | 建档入参走了最简形态，契约没跟上 | 两侧共存时提交体同时带 `names`；**待收敛**（见欠账） |
| 5 | ★ **无动作权限的用户对写操作拿 403，不是 404** | `@PreAuthorize` 先拦，对**任何** id 都 403 ⇒ 不泄漏存在性，**这是对的** | 「看不见 ⇒ 404」针对的是**有动作权限但范围受限**那一类。两版探针都按这个口径验（库 12/12、文档 7/7） |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | **`names` 与 `uploadIds` 的契约形状差要收敛** | 后端建档收 `names[]`，契约 `ImportDocumentsBody` 是 `uploadIds`。要么契约加 `names?`，要么后端收 `uploadIds` —— 现在是"两侧共存"，长期必漂 |
| 2 | **`stageUploads` / `upload-policy` / `segmentation/preview` / `ingestion-progress` 仍未实现** | 向导这几步在真栈会 404；前端已做容错（暂存失败退回建档、预览改为可选）。`ContractDriftTest` 已标欠账 |
| 3 | **文档列表不按 dataScope 再筛一次** | 文档可见性**继承库**，范围判定只在「找库」一处 —— 两套判定迟早分叉（刻意的，注释已记） |
| 4 | `department` 用 `varchar(64)` 而不是 `uuid` | `02` 的 `dept_id uuid` 需要部门字典表（本批没有）；契约也是 `string`。形状先占住 |
| 5 | 逐篇覆盖文档标签 | 本批三项是**批级**（一次导入同一套）；逐篇覆盖是后续 |

---

## 0.1.3 · 第 4 周 · M1 模型资产全链路（含 ★ 枚举取值统一为机器码 —— **破坏性契约变更**）

> **真源见 [`系统设计方案/14-枚举与取值命名规范.md`](系统设计方案/14-枚举与取值命名规范.md)** —— 规则、全量映射表、边界都在那里。
> 本节只记「改了什么、为什么、怎么验的」。

### 这一版改的是「取值口径」

**所有枚举的标识符 / 线上 JSON 值 / 库存值 三处同名，统一 `SCREAMING_SNAKE_CASE`**（多词用下划线）；
**中文只做展示**（`frontend/src/constants/labels.ts` 的 label 映射），不进标识符、不进 JSON、不进库。

```
改前：public enum UserStatus { 正常, 停用, 待审核 }     // 中文写进了标识符
改后：public enum UserStatus { ACTIVE, DISABLED, PENDING_REVIEW }

改前：GET /users → {"status":"正常"}                    // 展示文案变成了机器码
改后：GET /users → {"status":"ACTIVE"}                  // 界面查 label 显示「正常」
```

**为什么要改**：旧口径（「库、代码、契约三处同一个中文字面量，少一层映射少一处对不上」）确实省了一层映射，
代价是**把展示文案变成了机器码** —— 中文进了 Java 标识符、进了 JSON、进了库约束：不符合开发规范、无法国际化、无法排序、对工具链不友好。
新口径把两件事分开：**机器码稳定可比较；文案随时可改而不影响契约与数据**。

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **规格文档 `14-枚举与取值命名规范`** | 规则三条 + 全量映射表（2.1 后端 / 2.2 契约 / 2.3 节点类型 / 2.4 补录）+ **边界**（改取值、不改展示文案与文件名） |
| 2 | **后端 7 个枚举**改常量 | `ClassificationLevel`（★ 声明顺序即密级高低，**未调整顺序**）· `DataScope` · `Visibility` · `SubjectKind` · `UserStatus` · `ModelStatus` · `Supplier` · `ModelKind`；`ThinkingMode`/`ToolsMode`/`ErrorCode` 本就规范，不动 |
| 3 | ★ **`VisibilityConverter` 删除** | 常量与值统一成大写后，那一层映射失去存在理由 —— 少一层映射正是本次目的 |
| 4 | **三条迁移 V9/V10/V11** | `drop 旧约束 → update 翻译存量 → add 新约束`（顺序反了会被旧约束拒掉）；**不改 V3/V6/V8**（Flyway 校验和） |
| 5 | **契约与前端全量换码** | `api/**` 的 union、`mock/**` 的数据与比较、`constants/labels.ts` 的 label 映射、`pages/**` 约 30 个文件的渲染点 |
| 6 | **钉死新口径的断言** | JDBC 直查库断言存的是新码；Jackson 序列化断言出的是新码（防止有人加 `@JsonValue` 又把中文写回去） |

### 验证记录（本地实跑）

```
后端  mvn -B -f backend/pom.xml verify → BUILD SUCCESS · Tests run: 152, Failures: 0
前端  npm run typecheck → 0 错误 · build:mock 通过 · verify_refs / check_template_tags / check_design 全绿
★ 真库迁移（开发库有中文存量数据）
    V9 / V10 / V11 全部 success=t
    iam_user.status          → ACTIVE（原「正常」）
    iam_role.data_scope      → ALL / OWN / SHARED（原 all/own/shared）
    iam_role.max_classification → CONFIDENTIAL / INTERNAL / SECRET / PUBLIC（原 中文）
★ 接口与令牌里流通的取值（真请求实测）
    令牌 scope=ALL  clearance=CONFIDENTIAL
    GET /users  → status=ACTIVE
    GET /roles  → dataScope/maxClassification = SHARED/PUBLIC · OWN/INTERNAL · ALL/INTERNAL · ALL/SECRET · ALL/CONFIDENTIAL
    界面显示的仍是中文 —— 由 constants/labels.ts 的 label 映射给出
```

### ★ 这一版踩到的坑

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | **改约束后再翻译数据 ⇒ 翻译被旧约束拒掉** | Flyway 迁移里 `update` 的新值过不了还没换掉的 check 约束 | 顺序固定为 **drop 旧约束 → update → add 新约束** |
| 2 | ★ **第一轮改完还剩一半中文取值** | 规格表漏了 8 个「表外 union」（`WorkflowRecord.status`、`Segment.embedding`、`noRefStrategy`、`logic`、`ModelCapability.source`、`ChatMessage.role`…）—— 它们同样是被 `===` 比较 / 出现在 JSON 里的取值 | 补进规格 **2.4** 并一并换码。★ 判据要写死：**被 `===` 比较过、或出现在 JSON/库里 ⇒ 它就是取值** |
| 3 | **盲替换会改坏撞词字面量** | `role: 'user'`（消息角色）、`source: 'local'`（文档来源）、`key: 'workflow'`（发布检查项的键）与枚举撞词 | 换码前先确认是不是本表里的枚举取值；**碰撞词保留** |
| 4 | `npm run build:mock` 一直红 | 它是 `vue-tsc --noEmit && vite build`，前半段被页面的类型错挡住 | 分段验证：先 typecheck 归零，再跑整条；`npx vite build` 可单独验构建半段 |
| 5 | 模板里 `{{ row.status }}` 这类**裸渲染不产生类型错** | 只换 TS 字面量不会被抓到 | 靠 grep 扫出裸渲染点，改成 `labelOf(...)`（本次扫出并改了约 10 处） |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | **这是破坏性契约变更**（打破冻结契约的「只加可选字段」） | 已在本节与规格文档记录。任何仍持有旧取值的调用方（含旧 mock 快照）会失效 |
| 2 | **`models/Index.vue` 的 `KIND_LABEL` 是页面级短标签**（对话/向量），与 `MODEL_KIND_LABELS`（对话模型/向量模型）文案不同 | 按「展示文案不动」保留了本地 map。两处文案并存，将来要合并成一份 |
| 3 | `pages/workflow/enums/common.ts` 的 `ValidType.User = 'user'` 是画布级联选择器**内部**枚举 | 不是契约 `SubjectKind`，未改。它是否也该进规格，待定 |
| 4 | ~~**第 4 周的 `/models` 端点尚未实现**~~ | ✅ 已在本版落地（见下方「/models 全链路」） |

### `/models` 全链路（同版 0.1.3 · 第 4 周）

> 枚举改造之后接上端点：上游目录 / 能力探测 / 注册 CRUD / load 语义。

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **8 个端点**全部落地，均带 `@PreAuthorize("hasAuthority('model:manage')")`（含 GET） | `GET /models` · `GET /models/base-models` · `POST /models` · `PUT /models/:id` · `DELETE /models/:id` · `POST /models/:id/load` · `POST /models/:id/probe` · `POST /models/probe` |
| 2 | **能力探测**：元数据优先 + 行为探针兜底 | contextWindow/tools 从 `/v1/models` 元数据读；dimension 调 embeddings 实测；thinking 三态发带/不带 `reasoning_effort` 的 chat 看行为 |
| 3 | **load 语义** = 平台启用位 + 上游可用性校验，不操纵上游实例 | 异步：置 LOADING 立即返回，后台校验 → LOADED + enabled=true 或 LOAD_FAILED + 可读原因 |
| 4 | **视图绝不回传 apiKey 明文** | `ModelView` 显式列出出网字段（与 `UserView` 不含 `passwordHash` 同纪律） |
| 5 | **维度校验** | embedding 缺 dimension 拒绝；chat 带 dimension 拒绝；LOADED 后改 dimension 拒绝（需全量重灌向量表） |
| 6 | **桩服务扩了能力元数据** | `/v1/models` 带 `context_window` / `tools` / `dimension`，四类能力都能被探出来 |

**⚠️ 钩子：DELETE 的「被应用/知识库引用」检查**
删除的拒绝条件是「被应用/知识库引用」而不是「已加载」（契约只有 load 没有 unload，否则加载过的模型永远删不掉）。
当前 `assistant` / `knowledge` 两张表是第 5 周的，**引用检查留成钩子**（代码注释已标），两张表落地后在此处加引用计数查询。
builtin=true 仍拒绝删除；删除时清掉启用位。

---

### 模型服务端点（同版 0.1.3 · 以业务完整性补齐，TDD 推进）

**「模型管理」在真环境里缺了一环：没地方配上游端点** —— 界面没有 `apiBase`/`apiKey`
输入项 ⇒ 外部 API 的模型**从界面建不出来**（后端会要求 apiBase）；基础模型目录也被钉死在
默认上游（`listBaseModels` 没有指定上游的入参）。按业务完整性补齐，**每批先写红测试再实现**。

| # | 交付 | 关键点 |
|---|---|---|
| 1 | ★ **契约调整：`listBaseModels` 由 `GET` 改 `POST /models/base-models`** | body 带 `apiBase`/`apiKey` —— **密钥不能进查询串**（会进日志/代理/浏览器历史）。返回形状不变；不传 ⇒ 照旧走默认上游（现行为不回归） |
| 2 | **目录跟着上游走** | 传不同 `apiBase` 拉不同目录；上游不可达 ⇒ **200 + 空列表 + 可读 hint**（不是 500、不是干巴巴的空数组） |
| 3 | **`create` 与 `update` 同口径** | `EMBEDDING` 传 `thinking`/`tools` ⇒ 400。原先 `create` 是**静默接受并落库**（不是改写 —— 实测 200 落库），`update` 是 400 |
| 4 | **表单流程改成「先定服务再选模型」** | 顶部：供应商 → 服务地址 → 密钥 → 模型类型 → 基础模型；改了上游就重拉目录。外部 API 必填 apiBase（前端拦 + 后端守卫） |
| 5 | ★ **`http.ts` 补上包络 `hint` 透传** | 之前成功/失败两条路径都丢 `hint` ⇒ 后端给的可读提示到不了界面。现经 `onHint` / `ApiError.hint` 交出 |
| 6 | **mock 路由改 POST** 并按 `apiBase` 分支目录 | 让 mock 模式下也能看出「目录跟着上游走」 |

**验证**：后端 `verify` **174** 条（基线 170 + 新 4）；前端 typecheck / build:mock / 三道静态闸门 + 新闸门 `check_models_form.py`（mock 模式 **23/23**）
**联调（真前端代理 + 真后端 + 真桩）**：按指定上游拉到 4 个模型 · 死地址返空 + 可读 hint · 不传走默认上游 · 外部API 带 apiBase 注册成功 · 缺 apiBase ⇒ 400 · 向量模型带 thinking ⇒ 400

⚠️ **闸门在真栈上是 18/20**，两条红是**夹具差异**而不是表单 bug：真库没有已注册模型 ⇒ 没有「编辑」入口；真栈只起了一套桩 ⇒「目录随上游变」的双目录断言不成立。mock 模式 23/23。它需要「先造一条数据」才适合跑真栈 —— 记为待改进。

★ 顺手修掉的同区域潜伏 bug：`form.kind` 原是小写 `'chat'`（与契约 `CHAT` 不符）、`form.supplier` 初值竟是中文串 `'本地推理'` 强转枚举 —— 两者都会让提交出去的取值对不上契约。

---

### 降级机制补全（同版 0.1.3 · 「没有侦测到」这条路要走得通）

后端本来是**宽容**的：`baseModel` 就是个字符串，创建时**不校验**它在不在上游
（`lookupContextWindow` 明写「上游不可达时返回 null —— 不阻断注册」），真伪留到 `load`
才验（失败给可读原因）。**但界面把它堵死了** —— 只能从目录点选基础模型、向量维度只能显示
不能填 ⇒ 目录拉不到 / 探不出来就**注册不了**。按契约里已有的 `PROBED / MANUAL` 思想补上
「手工声明」这条降级路径：

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **基础模型可手填** | 目录区给「目录里没有？手动填写」逃生口。手填的在 `load` 时才验证 —— 上游没有它就 `LOAD_FAILED` + 可读原因，**不阻断录入**（模型可能是按需加载、或目录接口被关） |
| 2 | **向量维度可手填** | 探不出/目录没给时变成输入框（正整数校验）；hint 写明「维度与向量表绑定，创建后不可更改；不确定先用探测」 |
| 3 | **来源标注** | 基础模型 / 维度 / 能力都标 `目录 · 探测 · 手填` —— 界面必须让人分得清「测出来的」还是「填的」 |
| 4 | ★ 顺带修一个**真栈阻塞 bug** | 向量模型提交时也带 `thinking`/`tools` ⇒ 后端 400（mock 静默吞掉）⇒ 真栈上弹窗永远关不掉 |

**验证**：`check_models_form.py` **mock 38/38 · 真栈 38/38**（既有 24 条不动 + 新增 14 条）；typecheck / build:mock 通过

⚠️ **mock 与后端口径不一致（记账）**：后端 `baseModel` 不校验（任意串可注册），mock 的 `POST /models` 却拒「不在可选目录中」的名字 ⇒ 同一契约两种实现。mock 应放宽到与后端一致，否则 mock 模式会掩盖真后端的宽容行为。

### 0.1.3 发布后的两处口径修补（**未改 tag**，进下一版）

| # | 修补 | 为什么 |
|---|---|---|
| 1 | ★ **`http.ts` 兜底文案不再把「后端连不上」说成「后端异常」** | dev 代理在上游不可达时也会给一个 500 ⇒ 真实原因是「后端进程没了」，界面却说「后端异常，请稍后重试」，把排查引向「代码崩了」。与「非法 UUID ⇒ 500」「请求体非法 ⇒ 500」同一类。现改为：**响应体不是业务包络**时说「未得到后端的正常响应（后端可能未启动，或被代理/网关拦下）：HTTP xxx」。★ 顺带把这段逻辑拆成 `lib/http-error.ts` 并补了 4 条单测（仓库此前无 JS 测试基建，用 `node --test`） |
| 2 | **mock 的 `baseModel` 放宽到与后端一致** | 上面记账的那条。任意 `baseModel` 可注册，`contextWindow`/`dimension` 从目录 best-effort 带出；并补齐 mock 的「不适用字段拒绝」（原先只在 PUT 有）。`check_models_form.py` 的「陌生名被拒则退回种子名」适配随之删除 |

**验证**：typecheck 0 错误 · build:mock 通过 · `check_models_form.py` 38/38 · `node --test http-error.test.ts` 4/4 · verify_refs 通过

---

## 0.1.2 · 第 3 周 · M1 角色 / 权限点 / **数据范围 + 密级**

> ★ **版本号顺延（先说清，免得又出现两套编号）**：`ROADMAP §4.7.3` 原把第 3 周定为 `0.1.1`，
> 而 `0.1.1` 已被「等保补齐」占用（它是**第 2 周范围内的追加**，却单独发了一次版）。
> 按 `§4.3` 的规则「补丁位 = 周」，第 3 周顺延为 **0.1.2**、第 4 周为 **0.1.3**（第 5 周起仍是 `0.2.0`）。
> ROADMAP 里所有引用已同步 —— 同一个版号指向两个不同的周，正是 `§一` 认账过的"两套编号"病。

### 这一版能做什么

**「账号与权限」页真的能管账号了 —— 而且界面上的改动真的会生效**：

```
GET    /api/v1/users?page&size&keyword&roleId&status → { list, total, page, size }
POST   /api/v1/users                                 → 不传口令时**一次性回初始口令**
PUT    /api/v1/users/:id · DELETE /api/v1/users/:id  → 软删（同名可重建）
GET    /api/v1/roles                                 → 含 memberCount / permissions / dataScope / maxClassification
PUT    /api/v1/roles/:id/permissions                 → ★ 改完**重新登录立即生效**
PUT    /api/v1/roles/:id/data-scope                  → own / shared / all
GET    /api/v1/permissions                           → 16 个权限点（供角色管理页渲染勾选框）
```

**三种"应答语义"从此各归各位**（这是本周刻意划清的一条线）：

```
缺动作权限        → 403「无权限」（可以找管理员开）
不存在 / 已软删   → 404（★ 不确认"这个 id 存在" —— 403 等于告诉对方它存在，可被枚举）
id 格式不合法     → 400「参数类型不正确」（"你传错了"能自己修，不该让人来找后端）
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **迁移 V6**：`iam_role` 加 `data_scope` / `data_scope_rule` / `max_classification` / `labels`；新增 `iam_permission` / `iam_role_permission` | ★ 密级与范围**同批落**：它们进的是**同一个谓词构建器**（`13 §8.3`），分两次做 = 同一个函数被改两遍 |
| 2 | **迁移 V7**：只含 `comment on table`，修正 V6 里已过时的表注释 | ★ 不改 V6 —— Flyway 校验和一旦变化，已有环境的迁移历史就失效 |
| 3 | **判定层**：`AccessScopePort` / `ScopeFilter` / `OwnedResource` / `Grant` / `Subject` / `DataScope` / `Visibility` / `ClassificationLevel` | ★ 端口放 `platform`、**唯一实现放 `identity`** ⇒ `01` 的依赖矩阵**零改动**；全工程只有一处判定逻辑，且有金丝雀（第二个实现 ⇒ 构建失败） |
| 4 | **三条兜底一律收窄**：未认证 ⇒ `nobody()`（连 public 都看不见）· 读不出 `scope` ⇒ `own` · 读不出 `clearance` ⇒ `公开` | 取"放开"的话，任何一次令牌结构变更都会**静默地**把所有人变成全平台可见 |
| 5 | **用户 CRUD**：`GET/POST/PUT/DELETE /users` + 分页/筛选 + 软删 | ★ 过滤**拼进查询**（`Specification`），不是查完再筛 —— 否则 `total` 与每页条数全错 |
| 6 | **角色 CRUD**：`GET /roles` · `PUT /roles/:id/permissions` · `PUT /roles/:id/data-scope` | 两个维度**分两个端点**改（`07 §3.2`）：勾权限点是"能做什么"，调范围是"能看到谁的" |
| 7 | ★★ **权限点的真源分两层**（本版最重要的一处设计修正） | **目录**（有哪些权限点）⇒ 代码；**角色挂哪些** ⇒ **库**；**ADMIN 的授权** ⇒ 代码（详见下面"坑 1/2"） |
| 8 | **端点守卫**：ADMIN 的权限点/数据范围不可改 · 空权限列表被拒 · 不能删自己 · 不能删内置 `admin` · 不能改自己的角色 · 账号名不可修改 | 文案与前端 `RoleManage.vue` / `UserManage.vue` **一字不差**（前端原文展示后端 message，两处各写一套会变成"界面说 A、接口说 B"） |
| 9 | **初始口令由后端决定并一次性回传**（契约只加可选字段 `User.initialPassword`） | 契约里 `password?` 是可选的 ⇒ **服务端必须有默认口令** ⇒ 而管理员必须知道它 |
| 10 | **38 个新后端测试**（合计 **104**） | 含：软删 ⇒ 404 · 软删后刷新失效 · 重启不冲掉界面改动 · 改权限点后重新登录立即生效 · 不带筛选的列表必须可用 |
| 11 | ★ **前后端联调**（真前端 + 真 jar + 真 PG，**28 条断言全过**） | 见下面"验证记录" |

### 验证记录（本地实跑）

```
mvn -B -f backend/pom.xml verify        → BUILD SUCCESS · Tests run: 104, Failures: 0
前端 typecheck / build:mock / verify_refs / check_template_tags / check_design → 全部通过
★ 前后端联调（VITE_USE_MOCK=false → 真前端 + 真 jar + 真 PG）—— 28 条断言全过
    令牌 payload 实测：scope=all · clearance=机密 · authorities=16 项（不是 mock_access_*，是真 JWT）
    ★ 在角色管理页勾掉 chenjun 角色的 conversation:share → 保存 → chenjun 重新登录后
       /users/me 里那一项**真的没了**（再勾回来也真的回来）
    ★ 在用户管理页新建账号（不填口令）→ 界面提示「初始口令：ChangeMe@2026（首次登录需修改）」
       → 用这个口令**真的能登录**，且被要求首次改密
    ★ 直连 API 削减 ADMIN 的权限点被拒（前端置灰只是体验，不是安全边界）
    403（chenjun 调 /users）· 404（不存在的用户）· 400（/users/not-a-uuid）· 404（未实现的
      /analytics/*）—— 四种应答各自正确且不互相冒充
    新建的账号被软删后：列表看不见、详情 404、同名可重建、不能再登录
```

### ★ 这一版踩到的坑（**全部属于"不报错却失效"那一类**）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | ★★ **管理员在界面上勾的权限点完全不生效** | `JwtTokenService` 把 `authorities` 写成 `Permissions.of(role.code())`（代码常量），而"角色挂哪些"本该是**数据**。界面上能存、能回显、看起来一切正常，**鉴权根本不理会它** | 登录 / 刷新 / `GET /users/me` 三处统一改读 `iam_role_permission`。★ 顺带定下"真源分两层"：目录 ⇒ 代码，角色授权 ⇒ 库 |
| 2 | ★★ **重启会把界面上改的权限点悄悄改回去** | 启动同步无条件按代码映射对账角色授权 ⇒ 管理员的配置被**静默回滚**，同样不报错 | 非 ADMIN 角色**只在"一行都没有"时 seed 一次**，此后以库为准；ADMIN 例外（见交付物 7）。为了让"0 行 ⟺ 从未初始化"成立，`PUT /roles/:id/permissions` **拒绝空列表** |
| 3 | ★★ **真环境首屏的用户列表恒 500，而 104 条测试全绿** | HQL 里 `concat('%', :keyword, '%')` 的 `:keyword` **没有类型上下文**：首次执行若传 null，Hibernate 推不出类型 ⇒ 回退 `JAVA_OBJECT`（PG 驱动态按 **bytea** 发）⇒ `'%'\|\|?\|\|'%'` 退化成 bytea 拼接 ⇒ `ERROR: function lower(bytea) does not exist`。★ 更毒的是**类型会被缓存进查询计划**：冷启动后第一次带 keyword ⇒ VARCHAR ⇒ 之后不带 keyword 也 200；反之第一次不带 keyword ⇒ 此后**永远 500**。而测试里恰好有一个先带 keyword 的用例，**先把类型锁成了 VARCHAR，后面的用例就"净光"了** | 不 cast 打补丁：整体换成 Spring Data 的 **`Specification`**（按需拼谓词、**没有 null 参数**），筛选/计数共用同一份谓词。这也正是 `08 §4.3.3` 指定的做法。★ 新增"不带筛选的列表必须可用"断言钉住真环境首屏那条路径 |
| 4 | `@PreAuthorize` 拒绝返 **500** 而不是 403 | `@RestControllerAdvice` 的兜底 `@ExceptionHandler(Exception.class)` **抢在 `ExceptionTranslationFilter` 之前**把安全异常改写掉了 —— 而它从 0.1.0 起就没被测到（当时只测了"不带令牌 ⇒ 401"，那是过滤器链在更前面就拦下的） | 显式 `@ExceptionHandler(AccessDeniedException.class)` → 403 包络；并把这条陷阱写进注释 |
| 5 | 非法 UUID 返 **500** | `MethodArgumentTypeMismatchException` 落进兜底 handler | 显式映射 400。★ "你传错了"与"我崩了"必须分开 —— 混起来会把排查引到错误方向 |
| 6 | 建角色的 `PUT /roles/:id/data-scope` 返 **500** | `@NotBlank` 标在**枚举**字段上 ⇒ 校验期抛 `UnexpectedTypeException`（它只认 `CharSequence`） | 改 `@NotNull`，空值交给服务层判 |
| 7 | ★ **界面告诉管理员的初始口令是错的** | 前端与 mock 写死 `123456`，后端默认口令是 `ChangeMe@2026` ⇒ 管理员把"123456"告诉新人，新人**必然登不进去**。（而且 `123456` 连本项目自己的口令策略都过不了） | 真源放后端：创建且未传口令时，响应里**一次性**带 `initialPassword`；前端展示它，所有写死口令的文案（弹出窗 placeholder/提示、改密页说明、mock 默认值）全部清掉 |
| 8 | 页面允许改账号名，但后端不改（且不报错） | 编辑态前端把 `username` 一起发回，后端没有这个字段 ⇒ Jackson **静默忽略** | 后端：与现账号不一致 ⇒ 400「账号名不可修改」；前端：编辑态输入框置灰。★ 前端置灰只是体验，守卫必须在后端 |
| 9 | ★ 我把一条**错的根因**写进了代码注释 | 我写"`AuthorizationDeniedException` 不继承 `AccessDeniedException`"，审计时用 `javap` 实测（`spring-security-core-7.1.1`）**它确实继承** ⇒ 注释会误导每一个后来人 | 改成真实机制（坑 4）。★ 教训：**把结论写进注释之前先验证它** —— 注释在本项目里是文档，不是草稿 |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | ★ **「看不见 ⇒ 404」本周只在"不存在 / 已软删"上验到了** | 完整的"**存在但越权**"需要**归属资源**，而平台上最早的归属资源是**第 5 周的知识库**。`ROADMAP` 第 3 周原演示项「chenjun 访问别人的资源返 404」在本周**没有验证对象** ⇒ 已把该条阶段退出标准从 `0.1.0` 改挂到 `0.2.0`（见 `ROADMAP §3.2/§4.4/§4.7.3`）。本周改验能验的那一半：403 / 404 / 400 三者不互相冒充 |
| 2 | `maxClassification` 的初始值是**按角色定位推断的** | 在 `V6` 里，**待业务确认**（界面上可随时改） |
| 3 | **内置 `admin` 是按用户名识别的** | 与前端 `isBuiltinAdmin` 同口径。更稳的做法是给用户加一个 `built_in` 标记（或不可删的角色标记）—— 记下来，等有第二个内置账号时再做 |
| 4 | **mock 的口令校验弱于后端** | mock 只查"≥ 8 位"，后端 `PasswordPolicy` 还要求四类字符至少 3 类、不含账号名、有上限。⇒ mock 会放过真后端会拒的弱口令，可能掩盖口径差异 |
| 5 | **登录页文案「接口已固定、当前走 mock」在真联调时是错的** | 它没跟着 `VITE_USE_MOCK` 变。不影响功能，但界面在说谎 —— 属同一类问题，记下来 |
| 6 | **前端 3 个 flaky 闸门仍未接入 CI**（`verify_features` / `verify_permissions` / `smoke_test`） | 沿用 0.1.1 的欠账，待修稳定性 |
| 7 | **演示口令 `123456` 仍不满足等保强度** | 沿用 0.1.1 的欠账（`V4` 已注明生产必须删除演示账号）。★ 本版新引入的"默认初始口令"是**平台级固定值**，也是同一类待议项（现状缓解：`mustChangePassword=true` 强制首次改密） |

### 未做（刻意的）

- **不做 `POST /roles` / `DELETE /roles`**：五个角色由迁移建立，契约里也没有这两个端点；"新建角色"是 V0.2 的话题
- **不把"用户"纳入 `dataScope`**：`07 §4.2` 的数据面矩阵里**没有"用户"这一行** —— 范围管的是"知识库 / 应用 / 会话"这类**用户产出**的资源，而"能看多少个账号"由 `system:manage` 这一个动作权限决定。把用户也塞进范围，会让"管理员只看得到自己"这种荒谬配置变得**可表达**（`07 §8.8`：属性策略引擎这类机制"先做等于空转"）
- **不做`dataScopeRule` 的解析器**：`07 §8.3` —— MVP 只存不解析，先加枚举值通常就够

---

## 0.1.1 · 等保补齐（第 2 周范围内的追加，不是新的一周开始）

### 这一版能做什么

**登录多了三道等保要求的关，口令强度有了硬约束**：

```
GET  /api/v1/auth/captcha               → { id, image:"data:image/png;base64,…" }
POST /api/v1/auth/login                 → 不带验证码 ⇒ 400 + code 1003
                                         验证码只能用一次（重放 400）
                                         密码连错 3 次 ⇒ 403 + code 1001（锁定 30 分钟）
                                         锁定期间**正确密码也进不去**
POST/PUT /api/v1/users/me/password      → 弱口令 ⇒ 400 + code 1002（带具体原因）
                                         改密成功 ⇒ 该用户全部刷新令牌被撤销
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **契约**：`ErrorCode` 加 `BAD_REQUEST(400)` / `ACCOUNT_LOCKED(1001)` / `PASSWORD_TOO_WEAK(1002)` / `CAPTCHA_INVALID(1003)`；`LoginRequest` 加可选的 `captchaId`/`captchaCode`；新增 `getCaptcha()` | ★ 用户点名要的——契约是唯一真源，所以**先改契约再改实现** |
| 2 | **迁移 V5**：`iam_user` 加 `failed_login_attempts` / `locked_until` | ★ 落库而非放内存：锁定是"跨越重启的约束"，重启一次不该送攻击者一轮新机会 |
| 3 | **`PasswordPolicy`** | 长度 ≥8、四类字符中至少 3 类、不含账号名；★ 上限 64 是因为 BCrypt 在 72 字节处**静默截断** |
| 4 | **`CaptchaService`** | Java2D 画 PNG + Caffeine TTL 2 分钟 + **一次性消费**；★ 不引 kaptcha —— JDK 与 Caffeine 已经够用 |
| 5 | **`AuthService` 登录四道关** | 验证码 → **锁定检查** → 密码 → 账号状态。★ 锁定放在密码校验**之前**：已锁定就不必再跑最贵的 BCrypt |
| 6 | **`AuthSecurityRecorder`** | ★ 独立事务（`REQUIRES_NEW`），见下"踩到的坑" |
| 7 | **`PUT /users/me/password`** + 强度校验 | 改密成功后撤销该用户全部刷新令牌 |
| 8 | **27 个新测试** | 口令强度 9 · 验证码 7 · 等保端到端 11 |
| 9 | ★ **前端登录页接入验证码** | `pages/login/Index.vue` 加验证码图与输入框（图形比例与后端一致）；**任何失败之后都换一张** —— 验证码是一次性的，不换会让用户陷入"怎么输都说验证码不对"的死循环。mock 侧补 `/auth/captcha`，让 mock 模式下的页面形态与真后端一致 |
| 10 | ★ **前后端联调**（`VITE_USE_MOCK=false` → 真后端 + 真 PG） | 用 Playwright 对着"真前端 + 真后端 + 真 PG"跑完整登录：验证码图来自后端（PNG）、登录拿到**真 JWT**、`/users/me` 返回正确。**这一步抓出了一个真 bug**（见下面第 6 条坑） |

### 验证记录（本地实跑）

```
mvn -B -f backend/pom.xml verify          → BUILD SUCCESS · Tests run: 66, Failures: 0
前端 typecheck / 3 个静态闸门 / 11 个浏览器闸门 / build:mock → 全部通过
★ 前后端联调（VITE_USE_MOCK=false → 真后端 + 真 PG）
    GET  /api/v1/auth/captcha  → 200（图是后端渲染的 PNG，不是 mock 的 SVG）
    POST /api/v1/auth/login    → 200（拿到 accessToken）
    GET  /api/v1/users/me      → 200（admin / 管理员）
    GET  /api/v1/analytics/*   → 404（后端未实现，前端先行的看板页在调）
    登录后 localStorage 里是真 JWT（eyJ…），不是 mock_access_*
```

### ★ 这一版踩到的坑（都是"功能看起来是对的"那类）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | ★★ **登录失败 3 次锁不上账号** | `login()` 是事务方法，而它在**记录失败计数之后抛异常** ⇒ 异常把这次写入一起**回滚**了，计数永远是 0 | 抽 `AuthSecurityRecorder`，用 `REQUIRES_NEW` 独立事务。★ **同一个 bug 还藏在重放检测里**："撤销全部会话"也被回滚掉 ⇒ 攻击者手里那个令牌依然有效。两处都修了 |
| 2 | `ACCOUNT_LOCKED` 返回 **400** 而不是 403 | 我加了码，却忘了更新 `GlobalExceptionHandler` 的状态映射 ⇒ 它悄悄落进 `default`。"加了码、忘了改映射"不会报任何异常 | 改成"**码值本身是 4xx 就直接对齐 HTTP**"，这类码以后不必再改表；只有语义与 HTTP 不一致的（如 `ACCOUNT_LOCKED`）才显式列出 |
| 3 | ★ **测试恢复步骤失败，导致后续测试连锁失败** | 一个测试在 `finally` 里用 `123456` 把口令改回去，而**新加的强度校验拒绝了它**（6 位纯数字）⇒ 口令卡在被改成的那个值上 | 复位改为 `@BeforeEach` 里**直接写库**（绕过业务接口）。★ 顺带暴露一件真事：**seed 里的演示口令本身不满足等保强度** —— 这符合预期（它们是开发数据，V4 已注明生产必须删除），但它意味着"改回去"这件事只能由测试自己安排 |
| 4 | `OptionalAssert.contains` 全部断言失败 | AssertJ 的 `assertThat(Optional).contains(x)` 是"**值等于** x"，不是"含子串" | 改用 `.orElseThrow()` 取出消息再查子串 —— 后者在"应该违规却通过了"时也会明确失败 |
| 5 | 一条测试数据算错了字符类别 | `"abcdefg!"` 是"小写 + 特殊"= **2 类**，我按 3 类写的 | 改为 `"abcdefg1!"`。类别计数现在有单独的测试守着 |
| 6 | ★★ **联调时"未实现的端点"返回 500 而不是 404** | 兜底的 `@ExceptionHandler(Exception.class)` 把 Spring 的 `NoResourceFoundException` 也抓了（它继承自 Exception）。于是前端调 `/api/v1/analytics/*`（后端还没实现）时收到"服务内部错误"⇒ 排查方向变成"后端崩了"，而真实原因是"这个接口还没做" | 显式处理 `NoResourceFoundException` → **404** + hint 直接点出"也可能是前端先行开发留下的差异"；并补一条断言钉住 |
| 7 | 重新打包失败：`Unable to rename … .jar.original` | 旧 jar 被**我自己刚启动的后端进程**占着 | 顺序问题：先停服务再打包。值得记的是这条报错信息里**没有一个字提到"文件被占用"** —— 只有一句 rename 失败 |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | ★ **演示口令 `123456` 不满足强度要求** | 它是与前端 mock 对齐的开发数据（`mustChangePassword=false`）。这本身没问题，但**等保测评时演示账号也在范围内** —— 要么改 seed 口令，要么确保测评环境里没有它们 |
| 2 | **锁定只按账号，没有按 IP** | 等保要求"限制登录失败次数"，按账号已满足。但攻击者可以换账号名遍历（不存在的账号不会被锁）—— 按 IP 限流是另一层，未做 |
| 3 | **没有口令定期更换 / 历史口令检查** | 等保三级可能要求"定期更换"与"不得与最近 N 次重复"。后者要存口令历史（本身是新攻击面），且 NIST SP 800-63B 对强制轮换持保留态度 ⇒ 等测评明确要求再按口径补 |
| 4 | **验证码是唯一一处"内存态"** | 重启丢失。这是**可接受**的（2 分钟就过期，代价几乎为零），与锁定必须落库的理由正好相反 —— 判断标准是"它是否承载需要跨越重启的约束" |
| 5 | `CaptchaService.peekCodeForTesting` 是公开方法 | 为了让自动化测试能验"验证码开启时的行为"。名字里带 `ForTesting` 就是让任何人一眼看出它不该被生产代码调用 |

### 未做（刻意的）

- **不引 kaptcha / easy-captcha**：需要的东西只有三件（随机字符、Java2D、带过期的键值表），JDK 与 Caffeine 都已具备
- **验证码不做点选/滑块**：图形字符码已满足等保那一条；更复杂的形态是体验优化，不是合规门槛

---

## 0.1.0 · 第 2 周 · M1 认证链与双令牌

### 这一版能做什么

**用账号登录，并带着令牌访问受保护接口**（三个动作都是实测过的）：

```
GET  /api/v1/users/me        不带令牌 → HTTP 401 {"code":401,"hint":"登录已过期或令牌无效，请重新登录"}
POST /api/v1/auth/login      {"username":"admin","password":"admin"} → accessToken(30m) + refreshToken(7d)
GET  /api/v1/users/me        带令牌 → HTTP 200 + 用户名 / 角色名 / 16 个权限点
POST /api/v1/auth/refresh    换发新的一对；★ 旧令牌再用 → 判定重放，撤销该用户全部会话
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **迁移 V3/V4**：`iam_user` / `iam_role` / `iam_refresh_token` + 5 角色 4 演示账号 | 用户名唯一用**部分索引**（删掉的账号不该永久占用名字）；刷新令牌只存 **sha256 哈希** |
| 2 | **domain**：`User` / `Role` / `RefreshToken` / `Permissions` + 仓储接口 | 权限点 16 个与前端 `permissions.ts` 逐项对齐；角色→权限点照 mock fixtures 对齐 |
| 3 | **JWT 签发**（`JwtTokenService` + `NimbusJwtEncoder`，HS256） | ★ 权限点放进令牌 ⇒ 省掉"每请求一次权限查询"，代价是改动最长 30 分钟生效（写在类注释里） |
| 4 | **责任链补全**：`oauth2ResourceServer().jwt()` + `@EnableMethodSecurity` | 验签解析全走官方；401/403 用官方扩展点，输出包络 |
| 5 | **`AuthService`**：登录 / 刷新轮换 / 重放检测 / 登出 | ★ 这是 `08 §4.2.3` 说的"两处必须自己写"的第一处 |
| 6 | **Web 层**：`/auth/login` · `/auth/refresh` · `/auth/logout` · `/users/me` | 当前用户从 `@AuthenticationPrincipal Jwt` 取，不用 ThreadLocal |
| 7 | **统一异常映射**（`GlobalExceptionHandler`） | ★ 401 返 **HTTP 401**（前端靠它静默刷新，见下） |
| 8 | ★ **第三条闸门**：写端点必须声明权限 + 金丝雀 | 与模块边界、内核纯净性同构 |
| 9 | **16 个新测试** | 令牌签发/验签 6 · 端到端认证链 8 · 权限声明 2 |

### 验证记录（本地实跑）

```
mvn -B verify  → BUILD SUCCESS · Tests run: 38, Failures: 0, Errors: 0
                  （0.0.1 的 22 个 + 本周 16 个）
curl 演示      → 401 带 hint ✅ · 登录拿双令牌 ✅ · 带令牌取到 16 个权限点 ✅
```

### ★ 这一版踩到的坑

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `Unable to convert claim 'iss' of type String to URL` | ★ **JWT 的 `iss` 必须是 URL 形式** —— Spring 的 `Jwt.getIssuer()` 返回 `URL`，给裸串（`ai-knowledge-platform`）会在读取时抛转换异常 | issuer 改成 `http://ai-knowledge-platform.local`，并在配置里注明"必须是 URL" |
| 2 | `expiresAt must be after issuedAt` | 想用"负 TTL"造过期令牌，但 `JwtClaimsSet` 在**构建期**就拒绝 | 测试改为手工构造"签发于过去"的声明 —— 那才是真正想验的（验签方是否检查过期） |
| 3 | ArchUnit 报 `failed to check any classes` | 第 2 周的三个写端点**全在白名单里**（`AuthController`）⇒ 规则空跑，而 ArchUnit 默认把空跑当失败 | 主规则 `allowEmptyShould(true)` + 由**金丝雀**负责证明规则有效（第 3 周有 CRUD 后才有真实检查对象） |
| 4 | `haveSimpleNameNotEqualTo` 不存在 | ArchUnit 的 `ClassesThat` 没有"不等于" | 白名单并入一个 `DescribedPredicate`，只保留一个条件表达式 |
| 5 | ★ 重放检测的断言失败，但响应体里**明明有**那个词 | 我把服务端文案记成了"已使用"，**实际是"已被使用"**（中间隔着"被"字） | 打印**码点**定论（`U+5DF2 U+4F7F U+7528` 两边一致）⇒ 是断言写错，不是编码问题。**若跳过诊断直接去查编码，方向就全错了** |

### ⚠️ 本版新增的待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | ★ **契约该补 `BAD_REQUEST(400)`** | 请求体校验失败总得有个码，而契约的 `ErrorCode` 只有 8 个。400 是 401/403/404 那三个"与 HTTP 对齐"的码的同族补全。前端不针对它分支（走通用错误渲染），所以现在加不破坏任何东西 |
| 2 | **登录失败不区分"账号不存在"与"密码错误"** | 我按安全默认做的（区分它们等于提供用户名探测器），文案统一为"账号或密码不正确"。★ 前端 mock 是**区分**的（`账号「x」不存在` / `密码不正确`）—— 若产品上要那种提示，需要显式决定并改这一处 |
| 3 | **`CREATOR` 角色的权限点是我按 `07 §4.1` 矩阵推的** | 前端 mock 里**没有**这个角色（只有 r1~r4），所以它没有可对齐的真源。seed 里照实建出了这个角色，没有为了"四个角色刚好整齐"而假装它不存在 |
| 4 | **登出撤销的是全部会话** | 契约的 `POST /auth/logout` 不带令牌参数，服务端没法知道"是哪一台"。单设备登出需要契约加参数 |
| 5 | `AuthorizationDeclarationTest` 的白名单按**类名** `AuthController` | 改名会让白名单失效 ⇒ 构建失败（方向安全，会有人被通知到）。第 3 周有了真正的受保护端点后，这条规则才第一次有实际检查对象 |

### 未做（刻意的）

- **`GET` 端点不在权限声明闸门内**：查询类端点归**数据范围**（第 3 周）管
- **不给 `iam_role` 预埋 `data_scope`**：第 3 周才用，而该表只有几行 ⇒ 届时 ALTER 无成本（判断标准是"行数 × 是否进查询谓词"，见 V3 迁移的注释）
- **不做公开令牌 filter**：`08 §4.2.3` 的第二处自研，M4 才需要
- **不做契约漂移检测**：按计划是 0.1.2（第 4 周），本周的端点路径是手工核对的

---

## 0.0.1 · 第 1 周 · M0 骨架

### 这一版能做什么

**起一套空系统**：`docker-compose up -d` 起 PostgreSQL（pgvector 镜像），应用本地启动，
`curl /actuator/health` 返回 `{"status":"UP"}`。数据库里已有 2 个迁移的痕迹、pgvector 扩展已装、
Modulith 的出站事件表已就位。**没有任何业务功能** —— 这一版交付的是"约束"。

**把架构约定变成构建失败**：模块边界、越界依赖、内核纯净性三者都是自动化闸门，
且各自带一条**金丝雀**（故意破坏时必须变红）。

**契约的地基**：响应包络 `{code,message,data,hint?}` 与 8 个错误码已按前端冻结契约实现并断言。

### 交付物

| # | 交付 | 怎么验 |
|---|---|---|
| 1 | **12 个模块**的边界声明（`@ApplicationModule`） | `ModuleBoundaryTest` 断言 12 个模块全部被识别 —— 数量不对说明有 package-info 没生效，此时 `verify()` 的绿是假绿 |
| 2 | `ApplicationModules.verify()` | 同上测试类 |
| 3 | ★ **越界依赖金丝雀** | 同一个规则函数跑两个命名空间：对应用代码断言"必须通过"、对 `canaryfixture` 样本断言"必须失败" |
| 4 | **内核纯净性**：`platform` 禁 `org.springframework.ai.*` / `reactor.*` | `PlatformPurityTest` |
| 5 | **响应包络 + 错误码** | `ApiResponseTest` 逐项对照 `frontend/src/api/types.ts` 的 8 个码 |
| 6 | **迁移 V1**：pgvector 扩展 | `MigrationIntegrationTest` 查 `pg_extension` |
| 7 | **迁移 V2**：Modulith 出站事件表 | 同上；列集由实体生成核定，见下"这一版踩到的坑" |
| 8 | **Security 责任链**（放行 health / auth / public） | 上下文启动 + `curl /actuator/health` 免认证返回 200 |
| 9 | **单例 `ChatClient`**，`base-url` 指向 Ollama 的 `/v1` | 上下文启动即证明装配合法 |
| 10 | **CI**：后端 `mvn verify`（含 Testcontainers 真库）+ 前端 8 个静态闸门 + 类型检查构建 | `.github/workflows/ci.yml` |
| 11 | **Testcontainers 2.x + 真 PG**（`pgvector/pgvector:pg16`） | 16 个测试全绿 |
| 12 | **三份技术结论的探针**与判定口径 | `ModelCapabilityProbeTest`（模型缺席、**或端口上是桩服务**时整体跳过，不让构建变红）+ `docs/技术结论/M0-技术结论.md` |
| 13 | ★ **OpenAI 兼容的测试桩服务** | `backend/tools/openai_stub.py` + `tools/dev-model.cmd`。监听端口与 Ollama 相同 ⇒ `application.yml` 一行不用改。开发期用它替代真模型，让 M1/M2/M4b 的链路不必等模型 |
| 14 | ★ **Spring AI ↔ 桩的契约测试**（6 条） | `OpenAiStubContractTest`。其中"实际请求路径"与"是否下发 `reasoning_effort`"两条给出了**真证据**，而不是推断 |

### 验证记录（本地实跑，不采信旧绿）

```
mvn -B verify         → BUILD SUCCESS，Tests run: 22, Failures: 0, Errors: 0
                        Spotless: 全部 Java 文件 clean
                        （桩服务在跑 ⇒ 契约测试 6 条真跑；探针被反向保护拦住，正确跳过）
前端闸门（起 dev server）→ 14 个确定性闸门全绿，两遍结果完全一致
                        ★ 但实测另发现 3 个闸门当前 flaky（见下节「检测发现」），
                          故未接入 CI —— 接入一个会随机变红的闸门，
                          等于让 CI 的红失去信号价值
curl /actuator/health → HTTP 200 {"groups":["liveness","readiness"],"status":"UP"}
```

### ★ 这一版踩到的坑（都是"照老习惯写就一定错"的那类）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `Non-parseable POM`：注释里 `--` 非法 | XML 注释**不能含双连字符**，而我在注释里写了 `--add-exports` | 改成"为 jdk.compiler 打开 exports"的表述 |
| 2 | `Unknown lifecycle phase ".check.skip=true"` | PowerShell 把 `-Dspotless.check.skip=true` 拆开了 | `-D` 参数加引号 |
| 3 | `'构建...' is not recognized as an internal or external command` | **`.cmd` 文件被 cmd.exe 按 OEM 代码页读取**，UTF-8 中文注释被当成命令 | Maven 封装脚本改为**纯 ASCII** |
| 4 | `BATCH RECURSION exceeds STACK limits` | 脚本名为 `mvn.cmd`，而当前目录优先于 PATH ⇒ `call mvn` 调用的是自己 | 改名 `mvn-jdk25.cmd` |
| 5 | `google-java-format ... requires at least 1.30.0` | **1.28.0 不支持 JVM 25** | 钉 1.30.0（不是"越新越好"，是被 JDK 25 下限钉住的） |
| 6 | `'dependencies.dependency.version' for org.testcontainers:junit-jupiter is missing` | **Testcontainers 2.x 把模块 artifact 统一改名为 `testcontainers-<module>`** | 改 `testcontainers-junit-jupiter`，版本由 `testcontainers-bom:2.0.5` 管 |
| 7 | `PostgreSQLContainer<?>` 编译不过 | 2.x 的 `org.testcontainers.postgresql.PostgreSQLContainer` **不再是泛型类** | 去掉类型参数 |
| 8 | `Schema validation: missing table [event_publication]` | `ddl-auto=validate` + `starter-jpa` 已把 Modulith 的事件实体注册进实体扫描 ⇒ **不建表应用起不来** | 建 V2；列集**不照文档抄**，用 Hibernate 生成后核定 |
| 9 | `create table event_publication` 里 `serialized_event` 是 `varchar(255)` | Hibernate 对普通 `String` 的默认长度 —— 但**事件是序列化 JSON，255 必然溢出** | 迁移里改用 `text`，并由集成测试实测 validate 接受 |
| 10 | `ports are not available ... bind: forbidden by its access permissions` | ★ 本机 Windows **保留端口段含 5281-5380**，5432 正在其中 | compose 宿主端口默认改为 **15432**（可用 `AIP_DB_PORT` 覆盖） |

### 环境事实（第 2 周起照这个来，避免重复踩）

| 项 | 值 |
|---|---|
| JDK 25 位置 | `D:\Java\jdk-25`（Temurin 25.0.4.1）；构建用 `backend\mvn-jdk25.cmd`，不改系统 `JAVA_HOME` |
| PG 宿主端口 | **15432**（本机 5432 被系统保留） |
| compose 命令 | **`docker-compose`**（带连字符）—— 本机 CLI 未加载 compose 插件，`docker compose` 会报 unknown command |
| Maven | 3.9.11（系统在 PATH），JDK 由封装脚本固定为 25 |

### ⚠️ 本版发现的漂移与欠账（不修，但要记账）

| # | 事项 | 性质 |
|---|---|---|
| 1 | ★ **前端丢 `hint`**：`08 §4.1` 称 `hint` 是"一等字段"，后端也按它实现，但前端 `src/lib/http.ts` 在**两条构造 `ApiError` 的路径上都没有取 `hint`**（成功响应里的非 0 码、HTTP 错误分支）⇒ 后端填了也到不了界面 | 前端欠账，需 2 行修复 |
| 2 | 契约 `ApiResult<T>` 是 `{code,message,data}`，**不含 `hint`** | 后端多输出一个可选字段，契约允许；但要与第 1 条一起收敛 |
| 3 | `01 §二` 写"11 个模块"，而 `backend/README.md` 已对账为 **12 个**（含 `workflow`）⇒ `01` 的模块清单与依赖矩阵**缺 `workflow` 一行** | 文档待回写；本版按 README 的 12 个实现，`workflow` 的依赖位置为**本轮推定**（取与 `assistant` 相同的依赖集），待架构评审确认 |
| 4 | `ROADMAP §7.1` 说前端"11 个脚本"，实际目录里有 **19 个** `.py` 闸门 | 计数漂移，进 CI 的是其中 8 个静态闸门 |
| 5 | Surefire 报 `going to kill self fork JVM ... elapsed 30 seconds` | Testcontainers/Spring 上下文未干净关闭，拖慢构建 30s；不影响结果，待查 |
| 6 | `ROADMAP §6.1 P3` 说"**两份**技术结论"，`§4.4` 说"**三份**"（多了"能否稳定产出结构化输出"） | 文档内部不一致；本版按 `§4.4` 的三份执行 |
| 7 | `smoke_test.py` / `shot_sweep.py` / `shot_workflow.py` **未进 CI**（需 dev server + 浏览器） | 详见 `ci.yml` 的注释 |
| 8 | `01 §5.2` 的依赖声明示例与包结构示例用的是 `com.example.platform.*`，实际是 `cn.lincoln.aiplatform.*` | 示例性代码，不影响实现；回写 `01` 时一并更正 |

### ★ 第 1 周检测发现（不是本版引入的问题，但会挡住"CI 全绿"）

> 这些是**重跑**才暴露出来的 —— 按本项目的铁律"**旧的绿一律不能继承**"。
> 它们都在前端侧、不属第 1 周的产出，但会影响 0.0.1 验收第 ③ 条（CI 含前端脚本），
> 所以必须记账，而不是把它当成"已经跑过了"。

| # | 发现 | 证据（本机实测） | 处理 |
|---|---|---|---|
| 1 | ★ **3 个闸门当前 flaky**：`verify_features` / `verify_permissions` / `smoke_test` | 连"断言总数"都在变：`verify_permissions` 49/49 → 49/50 → 49/51；`verify_features` 35/39 → 38/39 → 39/39；`smoke_test` 24/33 → 26/33 → 28/33，控制台错误 0~3 条 | **不接入 CI**（接入 = 随机变红）。待办：让它们等页面就绪，或改用确定性选择器 |
| 2 | ★ **无 dev server 时是"假绿"** | `verify_permissions` 在没有 server 时报"49/49 通过" —— 它**根本没跑浏览器那一批断言**（有 server 时是 51 条） | 已从 CI 的"静态闸门"组移出；CI 现在**先起 server 再跑**该组 |
| 3 | `check_publish` 把页面错误当告警 | 两遍都输出 `PAGEERROR: Cannot read properties of undefined (reading 'validate')`，但 exit=0 | ★ 它**报了一个真错却不拦**。`check_publish` 的判定要复核 —— 这可能是发布流程的真实缺陷 |
| 4 | `19 个 .py` 里 **16 个依赖 playwright** | 逐文件扫描：只有 `verify_refs` / `check_template_tags` / `check_design` 是纯静态 | 已据此重写 CI 的前端 job（分两组），并在注释里写明每组的依赖 |

### 三份技术结论（`ROADMAP §6.1` P3）

> ★ **分条记状态，不笼统说"待实测"**。真模型（Ollama）在本机有两个现实障碍：
> **镜像拉不到**（`registry-1.docker.io` 被 DNS 污染，解析到 `31.13.83.34`；
> 几个镜像源虽可达但拉取未成功）且 **CPU 跑推理不现实**。所以结论表**不填猜测值**。
>
> 判定口径、判据、以及"桩服务能回答什么/不能回答什么"的边界，都在
> `docs/技术结论/M0-技术结论.md`。

| # | 结论 | 状态 |
|---|---|---|
| ① | 各模型思考模式三态取值（`NONE` / `TOGGLEABLE` / `ALWAYS_ON`） | ⏳ 待真模型 |
| ② | Spring AI 能否下发思考开关（★ 两侧分开测） | **框架侧 ✅ 已答：会下发**（桩侧记录到请求体里出现了 `reasoning_effort`；不给时则不出现）· **上游侧 ⏳ 待真模型**（Ollama 认不认这个字段） |
| ③ | 能否稳定产出结构化输出（N=20 的失败率） | ⏳ 待真模型（**解析链路已验**：桩按 schema 生成的 JSON 能被 `.entity()` 正确解析，含嵌套数组字段） |

★ 这次实测顺带**确认**了两件以前只是推断的事：

| 事 | 以前 | 现在 |
|---|---|---|
| `base-url` 写 `http://host/v1` 对不对 | 照 Ollama 文档推的 | ★ **实测正确** —— 桩记录到的真实路径是 `/v1/chat/completions`，不是 `/v1/v1/chat/completions` |
| 结论一的三态判定会不会被"参数没传出去"污染 | 未知 | ★ **不会** —— 框架侧已证会下发，所以 `ALWAYS_ON` 的判定是可信的 |

### 未做（刻意的，不是遗漏）

- **不引 `spring-ai-starter-vector-store-pgvector`** 与 **Tika**：M2 有向量表之后再引（`09 §9.4`）
- **不写 `GlobalExceptionHandler`**：它需要"HTTP 状态 ↔ 业务码"的映射规则，而那是端点契约的细节 ——
  第 2 周有第一个端点时用真契约测试来定，现在猜一个等于埋一个静默失效
- **不做应用的容器化**：开发期形态是 **2 个进程**（PG + 应用），见 `ROADMAP §4.6`
- **Checkstyle / SpotBugs**：按需引入（`09 §4.3`），记在 `pom.xml` 的注释里
