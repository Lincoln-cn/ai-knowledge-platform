-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：**检索域的三域形状** —— `kb_document` 的归属改 `scope_kind` + `scope_id`
--   （+ 生成列 `conversation_id`）、`kb_segment` 上同一套冗余列、`kb_knowledge_base.kind`
--   2026-10-15 · 第 17 周开工 · Issue #31 / `13 §十一` / `ROADMAP §11.3`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ── 为什么是 V34 而不是别的号 ────────────────────────────────────────────────
--
-- ★ Flyway 的 `locations: classpath:db/migration` 是**单一版本号链**：它按版本号排序、
--   **不按模块目录**分段 ⇒ 九个模块目录共用一条链。既有链末 = `conversation/V33`
--   （`cv_message`），所以本迁移的下一号就是 **34**。
--   ★ 更不能去改 `V33` 或任何更早的迁移：已应用的迁移一旦改动，Flyway 的 checksum
--     就对不上、后端起不来（本项目已经踩过一次，是硬纪律）。
--   ★ 也就是说「`knowledge` 模块拿到 34 号」是**正常现象**，不是抢号：
--     1–23 / 26 / 27 / 31 归 knowledge，24 归 platform，25 / 28 归 identity，
--     29 / 30 归 assistant，32 / 33 归 conversation。
--
-- ── 本批做四件事（一件都不许多做） ───────────────────────────────────────────
--
--   ① `kb_knowledge_base.kind`（`SHARED` 默认 / `PERSONAL` 个人库，**每人至多一行**）；
--   ② `kb_document`：`scope_kind` + `scope_id` + 生成列 `conversation_id` + `owner_id`；
--   ③ `kb_segment`：**同一套列**（冗余，见 D5）+ 同一生成列；
--   ④ 必要的 CHECK 与索引（见 D7 / D13）。
--
-- ★ 本批**只落形状**：不加 HTTP 端点、不改契约（`frontend/src/api/**`）、不实现会话附件的
--   写入路径、不做个人库的惰性创建。判据是「形状同一批落」+「三域进同一个谓词」。
--
-- ════════════════════════════════════════════════════════════════════════════
-- ★★ D1 · 归属形状取 (A)：`scope_kind` + `scope_id`，不取 (B) `kind` + 可空 `conversation_id`
-- ════════════════════════════════════════════════════════════════════════════
--
-- 裁定见 `13 §11.6` 第 1 行（2026-10-13 已定，(B) 被取代）。理由复述在这里，因为读这条
-- 迁移的人未必会翻设计册：
--   · (A) 把「归属**种类**」放在**数据里**（一个枚举值）⇒ 加一个域 = 加一个枚举值；
--   · (B) 把它编码进**列名的有无**（"`kb_id` 为空且 `conversation_id` 非空"这种推断）
--     ⇒ 第三域一来就要再改一次表，而"哪些域"这件事永远只有一个地方说得清；
--   · (A) 与 `13 §11.3` 的三支谓词**逐字对应**（`scope_kind = X and scope_id = :y`）：
--     读谓词就能反查数据，读数据就能写出谓词。
-- ★ 判据出处：`13 §8.2`「会改数据形状的东西要一次做对」—— 本迁移正是那一类。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D2 · `scope_kind` 只有两个取值；**个人库不是第三个域**
-- ════════════════════════════════════════════════════════════════════════════
--
-- `scope_kind ∈ {KB, CONVERSATION}`。个人库**就是** `kb_knowledge_base.kind = 'PERSONAL'`
-- 的那一行，在 `kb_document` 侧它与共用库**同形**（`scope_kind = 'KB'`）。
-- ★ 把 `PERSONAL` 单列成一个 `scope_kind` 会造出「同一种文档两种归属写法」的第二套真相 ——
--   而那正是本项目最贵的病（`10 §11.1`）。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D3 · `conversation_id` 是**生成列**（`GENERATED ALWAYS AS … STORED`）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 形状与理由逐字照 `13 §11.3` 的列映射表：
--   `conversation_id = case when scope_kind = 'CONVERSATION' then scope_id end`。
-- ★ 为什么不做成一个普通列：普通列 = 第二个真相 —— "会话 A 的附件"会有两处说法，
--   而它们不一致时**不会报错**（只是检索结果变了）。生成列由 `scope_kind/scope_id`
--   唯一决定 ⇒ 那一类不一致在物理上不存在。
-- ★ 它换来什么：让「会话 A 上传的附件与另一个会话无关」这条**能建索引、能写约束**
--   （`13 §11.3` 的原话），而不是只活在 WHERE 的意会里。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D4 · `kb_id` 保留、但**改成可空**（不是删掉、也不是改造成生成列）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 三条理由，缺一条这个决定就站不住：
--   ① **会话域没有 `kb_knowledge_base` 行**（`13 §11.2`：会话临时"不该有 ceiling /
--      向量模型 / 共享"）⇒ 它必须能留空。`13 §11.6` 的迁移纪律第 ② 条原话就是
--      「第 17 周加列时 `kb_id` **允许暂时留空**（新写入的会话临时附件不填它）」。
--   ② **库域今天有真实数据与一条外键**：删掉 `kb_id` 就连带删掉 `V13` 的外键与
--      `idx_doc_kb`（`kb_segment` 同理）—— 那对**活着的那一半域**是净损失，
--      而"没有孤儿"这件事本该由库层保的，不该退化成一条巡检。
--   ③ ★ **它不许成为第二处真相**：`chk_doc_scope_shape` 把 `kb_id` 钉成
--      "库域那一支的镜像"（`scope_kind='KB'` ⇒ `kb_id is not null and kb_id = scope_id`）。
--      ★ 为什么必须显式写 `kb_id is not null` 而不是只写 `kb_id = scope_id`：
--      SQL 的 CHECK 是**三值逻辑**，`NULL = uuid` 求值为 NULL ⇒ 整条 CHECK 判为通过
--      ⇒ 一行「库域、但没有知识库」的数据会被静默放进来。这个坑不会报错，只会让
--      那一行永远检索不到（而它的 `scope_id` 也没有外键兜着）。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D5 · `kb_segment` 上**同一套冗余列**（同批落，理由与代价都写在这里）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么必须冗余（`13 §6.4` 的三条，逐条对上本表）：
--   ① **两条检索路都在 `kb_segment` 上过滤**：全文那一路的 `content_tsv` 就是它的列，
--      向量那一路以它为驱动表 ⇒ 域谓词必须能在**这张表上直接生效**；
--   ② 谓词只长在 `kb_document` 上时，"两路同谓词"就退化成"两处 join 写法必须一致"，
--      而两处写法迟早会漂（`13 §三` 闸门②点名的那个病）；
--   ③ 与既有的 `kb_segment.kb_id`（`V15` 的注释："冗余列，查询时不必每次 join 文档表"）
--      是**同一个理由** —— 本迁移只是把那一列升级成"能表达三个域的那一套"。
-- ★ 代价（唯一一条，且必须给出应对）：冗余列可能与文档漂移 ⇒
--   · 写入侧：分段的域值**只从文档继承**（`Segment` 的构造器里由文档/知识库派生），
--     且三列 `not null` 无默认值 ⇒ 漏传是**插入时的一次报错**；
--   · 读不到的地方：一条**巡检**（`13 §11.7` 判据 5：没有孤儿 + 冗余不漂移）。
-- ★ 为什么不"每组一次 join 文档"就完事：那正是上面 ① 要挡的 —— 而且会话域的那张
--   `kb_knowledge_base` 行**根本不存在**，`join kb_knowledge_base k` 会把会话附件
--   **静默地全部丢掉**（内连接 + `k.enabled`），这是本批最容易长出来的一个静默洞。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D6 · `owner_id` 是**新增列**（设计册把它当"既有列"，但它并不存在）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 实测：`13 §11.3` 的列映射表写着「`owner_id` | 传输者 | 三域都有（`OwnedResource`
--   的既有列）」—— 而 `kb_document` 上**从来没有**这一列（`V13` 只有 `created_by`，
--   而 `created_by` 是审计列、`owner_id` 是归属列，两者在 `kb_knowledge_base` 上是分开的）。
--   ⇒ 谓词第三支 `owner_id = :me` 缺一个载体，本迁移把它补上。
--   ★ 这正是 `13 §11.3` 自己记过的同类错误（"上一版写的 `d.conversation_id` 是表上
--     还不存在的列"）—— 同一个形状又出现了一次，所以列名列全在这里，写谓词时不必回头猜。
-- ★ 语义边界（写进列注释）：`owner_id` = **传输者**（谁把这份内容放进来的），
--   它**不**参与前两支的可见性判定 —— 共用库靠 `visibility`/`grants`，个人库靠
--   "它就是我的那一行"。只有第三支（会话域）拿它当附加条件。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D7 · 三条 CHECK，以及它们**不**管什么
-- ════════════════════════════════════════════════════════════════════════════
--
--   `chk_*_scope_kind`        取值只能是 `KB` / `CONVERSATION`（挡"第五档只改了 Java 枚举"）
--   `chk_*_scope_shape`       库域：`kb_id is not null and kb_id = scope_id`；
--                             会话域：`kb_id is null`（见 D4 ③）
--   `chk_*_conversation_owner` 会话域 ⇒ `owner_id is not null`
--                             ★ 理由不是"整齐"：第三支谓词是 `… and owner_id = :me`，
--                             `owner_id` 为空 ⇒ 那一支**恒假** ⇒ 会话附件对**所有人**
--                             都检索不到（包括传它的人）—— 一次静默失效。
-- ★ 三条 CHECK 都**不是** NULL 安全的兜底：`scope_kind` / `scope_id` 是 `not null`（见 D8），
--   所以三个分支是穷尽的；换个人加第四个枚举值，第一条 CHECK 会先红。
-- ★ 它们**不**管：孤儿（`scope_id` 指向不存在的会话/库）—— 见 D14。
--
-- ════════════════════════════════════════════════════════════════════════════
-- ★★ D8 · 默认值的取舍：`scope_kind` / `scope_id` **都不给默认值**
-- ════════════════════════════════════════════════════════════════════════════
--
-- 与 `V22`（`kb_segment.classification`）**逐字同一条口径**：
--   ★「列是 `not null` **无默认值** ⇒ 漏传是**插入时的一次报错**，而不是静默落一个域」。
-- ★ 为什么这里比密级那一次更不能给默认值：密级默认错的后果是"看得见/看不见"，
--   而**域**默认错的后果是**归属换了**：一份会话临时附件若被落成
--   `scope_kind='KB' + scope_id=<会话绑定的库>`，它就变成了那个库的文档 ——
--   凡是能看那个库的人都能检索到它，**而且不报错**（这正是 `#31` 正文点名的那个静默越权）。
--   `chk_*_scope_shape` 能挡住"kb_id 没填"的那一半，挡不住"kb_id 填了库里那个 id"的那一半
--   ⇒ 唯一的防线是**不给默认值 + 会话域的写入必须显式**（写侧那条留给 `POST /files` 那一批）。
-- ★ 老数据怎么办：既有行**全部**是库域，回填是**同一行内的直接复制**（见 D15），
--   所以"没有默认值"不会让任何既有行失败。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D9 · `kb_knowledge_base.kind`：这里**允许**用默认值 `'SHARED'`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 允许的唯一理由：**默认值恰好等于既有行的真实语义**。`V34` 之前"个人库"这个概念
--   还不存在，没有任何一条路径能建出第二类库 ⇒ 既有行**就是**共用库，不是"猜"。
--   （对照组：D8 那两列如果用默认值，默认值**不等于**任何既有事实 —— 它是新域的语义。）
-- ★ 为什么不用"`update … set kind='SHARED'` 再 `set not null`"那种更啰嗦的写法：
--   在这个具体取值上两者等价（`default` 会把既有行填成 `SHARED`），而多一步没有任何
--   额外收益。★ 但代价必须写下来：**将来若加第三类库（如团队库），这个 DEFAULT 会
--   变成一个静默的错误默认值** —— 所以 `comment on column` 里钉了一条「加第三类库时
--   必须复核这条默认值」，并把它登记进交付报告的"待裁"。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D10 · 个人库**每人至多一行**：`unique (owner_id) where kind = 'PERSONAL'`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 理由：`13 §11.3` 的第二支绑定值是**一个单值** `:myPersonalKbId`。
--   一个人若有两行 `PERSONAL`，绑定值取哪一行没有定义 —— 而取错那一行**不会报错**，
--   只是"有些内容检索不到"（静默少看见）。唯一索引让这件事在写入那一刻就红。
-- ★ 为什么不是全局 `unique (owner_id)`：一个人**可以**有多个共用库（他就是那些库的创建者）。
-- ★ 为什么带 `and deleted_at is null`：软删过的个人库不该继续占位 —— 否则
--   "删掉个人库再建一个"会撞唯一约束，而错误信息会指向一个用户根本看不见的行。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D11 · 本批**不**建 `13 §11.6` ③ 那条 `unique (scope_kind, scope_id, content_hash)`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 它不是被否掉，而是**今天建不出来**：`kb_document` 上没有 `content_hash` 列
--   （它在 `kb_segment` 上，`V15`）⇒ 这条约束会直接语法/语义失败。登记在这里，
--   随"库内去重"的实现那一批落（那一批才需要它）。写下来是为了不被读成"漏了"。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D12 · **不**给 `conversation_id` 加唯一约束
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ `13 §11.3` 说生成列让"会话 A 的附件在会话 B 里检索不到"这条**可加唯一约束、
--   可建索引**。**唯一约束这一半不适用**：一个会话里可以传**多份**附件，
--   `unique (conversation_id)` 会把它限制成"一个会话只能有一份"——那与事实相反。
--   真正需要唯一性的是 `(scope_kind, scope_id, content_hash)`（D11，今天建不了）。
--   ⇒ 生成列在这里的价值是**可索引 + 可校验一致性**，不是唯一。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D13 · 索引：只建"谓词真的会走"的那一条
-- ════════════════════════════════════════════════════════════════════════════
--
--   `idx_segment_scope_classification (scope_kind, scope_id, classification_rank)`
--     —— 与 `V22` 的 `idx_segment_kb_classification` **同一个形状与同一个理由**：
--        两条检索路的过滤都是「域（scope_kind + scope_id）+ 密级」，域在前才有选择性
--        （密级只有四档，单独给 rank 建索引不会被用到）。
--   `idx_doc_scope (scope_kind, scope_id) where deleted_at is null`
--     —— 会话附件列表 / 巡检用（检索谓词走分段那一侧，见 D5）；与 `idx_doc_kb` 同口径取 partial。
-- ★ **不**建 `(conversation_id)` 单列索引：谓词从来不单独按它过滤（三支都以
--   `(scope_kind, scope_id)` 为前导），建了只是一条没人走的索引 + 一处要维护的东西。
-- ★ **不**删 `idx_segment_kb` / `idx_segment_kb_classification` / `idx_doc_kb`：
--   库域的列表页与库级统计仍然按 `kb_id` 走（`KnowledgeBaseRepositoryAdapter.countsFor`），
--   而"顺手删掉旧索引"属于顺手改别的形状 —— 本批不做。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D14 · 会话域**没有外键**：`scope_id` 是多态引用，`references` 表达不了多态
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ `scope_id` 可能指向 `kb_knowledge_base(id)`，也可能指向 `cv_conversation(id)`
--   —— 一条外键只能指向一张表，所以**会话那一支拿不到库层的引用完整性**。
-- ★ 库域仍有外键：`chk_*_scope_shape` 保证库域 `kb_id = scope_id`，而 `kb_id` 带着
--   `V13`/`V15` 的外键 ⇒ 库域的"没有孤儿"是**库层保的**。
-- ★ 会话那一支改由**巡检**守（`13 §11.7` 判据 5 的原话就是"写成一条巡检"，
--   与 `13 §6.4` 的"文档密级 ≠ 分段密级"同构）：`scope_kind='CONVERSATION'` 的行
--   必须能反查到会话。★ 为什么不用触发器去补一个外键：本项目迄今零触发器，
--   而"隐藏的写入"正是最难看懂的那类代码（判据同 `V27`：宁可留一条能跑的巡检）。
--
-- ════════════════════════════════════════════════════════════════════════════
-- ★★ D15 · 老数据怎么办（三处，全部是**同一行内的直接复制**）
-- ════════════════════════════════════════════════════════════════════════════
--
--   `kb_document.scope_kind` ← `'KB'`（常量：既有行全部是库域，见 D9）
--   `kb_document.scope_id`   ← `kb_id`（**直接复制**，不是按名字匹配）
--   `kb_document.owner_id`   ← `created_by`（**直接复制**；`created_by` 可空 ⇒ 留空）
--   `kb_segment.*`           ← 它的文档（`document_id` 有外键 ⇒ 一定能反查到）
--
-- ★★ 这条必须写清楚，因为 `#31` 正文最担心的是**回填**：本批的回填**一处都不是
--    "按名字匹配"** —— 旧形状（`kb_id`）本来就带着身份，新列是把同一个值搬过来。
--    ⇒ "匹配准确率随时间下降"那个风险在本批**不适用**（对照 `V27` 的
--    `department → org_id`：那一次只能按名字匹配，所以它必须留存档列 + 不猜）。
-- ★ 留空的两种情形各有归属：
--   · `owner_id` 为空：只在库域可能（`created_by` 为空的老行），而库域**不使用**它
--     （D6）⇒ 无害；会话域由 CHECK 强制非空。
--   · `scope_id` 为空：**不可能** —— 回填源是 `not null` 的 `kb_id`，而且紧接着的
--     `set not null` 会在迁移期报错。这是刻意的：宁可迁移失败，也不留一行"域未知"的数据。
--
-- ════════════════════════════════════════════════════════════════════════════
-- D16 · 本批**明确不做**的（写下来是为了不被读成漏了）
-- ════════════════════════════════════════════════════════════════════════════
--
--   · 个人库的**惰性创建**与"个人库不出现在知识库列表里"（`13 §11.6` 第 4 项，随第 17 周）；
--   · 会话附件的写入端点（`POST /files`）与 `FileStorePort`（`13 §11.6.1`，随 V1.1 产物）；
--   · 密级列的抬升逻辑（`cv_conversation.max_classification` 已在 `V32` 落，写入点在问答链）；
--   · 任何既有迁移的改动（硬纪律：checksum）。
-- ════════════════════════════════════════════════════════════════════════════

-- ────────────────────────── ① kb_knowledge_base.kind ──────────────────────────

-- ★ `not null default 'SHARED'`：默认值 = 既有行的真实语义（D9）
alter table kb_knowledge_base
  add column kind varchar(16) not null default 'SHARED';

alter table kb_knowledge_base
  add constraint chk_kb_kind check (kind in ('SHARED', 'PERSONAL'));

-- ★ 每人至多一个个人库（D10）；共用库不受限
create unique index uq_kb_personal_owner on kb_knowledge_base (owner_id)
  where kind = 'PERSONAL' and deleted_at is null;

comment on column kb_knowledge_base.kind is
  '库的**种类**（13 §11.2）：SHARED = 共用知识库（建库要 knowledge:manage，出现在列表/绑定/分享面）；PERSONAL = 个人库（每人隐式一个、owner_id = 我，**不出现在**任何知识库列表 / 选择器 / 分享对话框里，只能通过检索时的「加上我的资料」开关隐式进入检索域）。★ 个人库复用库这条链（向量模型、ceiling、分段、向量表），不新增平行的表；它的向量模型与 ceiling 继承平台默认。★ 每人至多一行：uq_kb_personal_owner（错的那一行不会报错，只会让"我的个人库是哪一行"没有定义）。★★ 加第三类库时**必须复核本列的 DEFAULT ''SHARED''** —— 那时它不再等于"既有行的真实语义"，而会变成一个静默的错误默认值';

-- ────────────────────────── ② kb_document 的归属形状 ──────────────────────────

-- 1 · 先加可空列（新列没有默认值，见 D8）
alter table kb_document
  add column scope_kind varchar(16);
alter table kb_document
  add column scope_id uuid;
-- ★ owner_id 是新增列（D6）：谓词第三支的载体，设计册把它当"既有列"但它并不存在
alter table kb_document
  add column owner_id uuid;

-- 2 · 回填：全部是**同一行内的直接复制**（D15），一处都没有按名字匹配
update kb_document
   set scope_kind = 'KB',
       scope_id   = kb_id,
       owner_id   = created_by;

-- 3 · 回填完才收紧成 not null：任何"没拿到域"的行都会在这一步暴露（D15）
alter table kb_document
  alter column scope_kind set not null;
alter table kb_document
  alter column scope_id set not null;

-- 4 · ★ kb_id 放开 not null（D4）—— 外键与 idx_doc_kb 都保留
alter table kb_document
  alter column kb_id drop not null;

-- 5 · ★ 生成列 conversation_id（D3）：由 scope_kind / scope_id 唯一决定
alter table kb_document
  add column conversation_id uuid
    generated always as (
      case when scope_kind = 'CONVERSATION' then scope_id end) stored;

-- 6 · 三条 CHECK（D7）
alter table kb_document
  add constraint chk_doc_scope_kind
  check (scope_kind in ('KB', 'CONVERSATION'));

alter table kb_document
  add constraint chk_doc_scope_shape
  check ((scope_kind = 'KB' and kb_id is not null and kb_id = scope_id)
      or (scope_kind = 'CONVERSATION' and kb_id is null));

alter table kb_document
  add constraint chk_doc_conversation_owner
  check (scope_kind <> 'CONVERSATION' or owner_id is not null);

-- 7 · 会话附件列表 / 巡检走的索引（D13）
create index idx_doc_scope on kb_document (scope_kind, scope_id)
  where deleted_at is null;

comment on column kb_document.scope_kind is
  '归属**种类**（13 §11.3）：KB = 共用库或个人库（由 kb_knowledge_base.kind 区分，**不在这里再分一次**）；CONVERSATION = 会话临时（只有 kb_document 行，没有 kb_knowledge_base 行，见 13 §11.2）。★ 没有默认值：漏传必须是插入时的一次报错，而不是静默落一个域（域错了 = 归属换了，而且不报错）';

comment on column kb_document.scope_id is
  '归属**主体**（13 §11.3）：KB ⇒ 那个库的 id；CONVERSATION ⇒ 会话 id。★ **不是** owner_id（那是"谁传的"，只进会话域的附加条件）。★ 多态引用 ⇒ 没有外键（D14）：会话那一支的"没有孤儿"由巡检守（13 §11.7 判据 5）';

comment on column kb_document.conversation_id is
  '★ **生成列** = case when scope_kind = ''CONVERSATION'' then scope_id end（13 §11.3）。让"会话 A 的附件与另一个会话无关"这条**可索引、可校验一致性**，而不是只活在 WHERE 的意会里。★ 为什么不做成普通列：普通列 = 第二个真相，两处不一致时不报错。★ 刻意**不**加唯一约束：一个会话可以有多份附件（唯一性属于 (scope_kind, scope_id, content_hash)，见迁移文件头 D11/D12）';

comment on column kb_document.owner_id is
  '**传输者**（谁把这份内容放进来的，13 §11.3）。★ 前两支的可见性**不**看它：共用库靠 visibility/grants，个人库靠"它就是我的那一行"；只有会话域那一支拿它当附加条件（owner_id = :me）。★ 可空：存量行回填自 created_by（可能为空）—— 库域不使用它，故无害；会话域由 chk_doc_conversation_owner 强制非空（为空 ⇒ 那一支恒假 ⇒ 会话附件谁都搜不到）';

comment on column kb_document.kb_id is
  '所属知识库。★ 自 V34 起**可空**（会话临时没有库行，13 §11.6 迁移纪律②），但**不是**自由的：chk_doc_scope_shape 把它钉成"库域那一支的镜像"（scope_kind = ''KB'' ⇒ kb_id is not null and kb_id = scope_id）⇒ 它不会成为第二处真相。★ 库域的外键仍由它承担（会话域的多态引用没有外键，见 D14）';

comment on table kb_document is
  '文档聚合根。建档必填三项（文种/责任部门/密级）+ ceiling 校验（13 §6）；责任部门自 V27 起是 org_id 外键（07 §11.2）—— ★ 身份与表达分开：身份是 org_id/code（不可改），部门名只是表达（可改、可重名）。★★ 归属自 V34 起是 **scope_kind + scope_id**（13 §11.3 裁定 (A)）：共用库与个人库都是 scope_kind = ''KB''，会话临时是 ''CONVERSATION''（且没有 kb_knowledge_base 行）；conversation_id 是由它导出的生成列';

-- ────────────────────────── ③ kb_segment 的同一套冗余列 ──────────────────────────

-- 1 · 先加可空列（D5/D8）
alter table kb_segment
  add column scope_kind varchar(16);
alter table kb_segment
  add column scope_id uuid;
alter table kb_segment
  add column owner_id uuid;

-- 2 · 回填：从**它的文档**继承（document_id 有外键 ⇒ 一定能反查到）
update kb_segment s
   set scope_kind = d.scope_kind,
       scope_id   = d.scope_id,
       owner_id   = d.owner_id
  from kb_document d
 where d.id = s.document_id;

-- 3 · 收紧成 not null：任何一行没继承到域，都会在这里报错（D5 的"漏传是报错"）
alter table kb_segment
  alter column scope_kind set not null;
alter table kb_segment
  alter column scope_id set not null;

-- 4 · ★ kb_id 同样放开 not null（D4；外键与既有索引保留）
alter table kb_segment
  alter column kb_id drop not null;

-- 5 · 生成列 conversation_id，口径与 kb_document 逐字相同（D3）
alter table kb_segment
  add column conversation_id uuid
    generated always as (
      case when scope_kind = 'CONVERSATION' then scope_id end) stored;

-- 6 · 三条 CHECK，与 kb_document 逐字同构（D7）
alter table kb_segment
  add constraint chk_segment_scope_kind
  check (scope_kind in ('KB', 'CONVERSATION'));

alter table kb_segment
  add constraint chk_segment_scope_shape
  check ((scope_kind = 'KB' and kb_id is not null and kb_id = scope_id)
      or (scope_kind = 'CONVERSATION' and kb_id is null));

alter table kb_segment
  add constraint chk_segment_conversation_owner
  check (scope_kind <> 'CONVERSATION' or owner_id is not null);

-- 7 · 检索谓词的支撑索引：与 V22 的 idx_segment_kb_classification 同形同理（D13）
create index idx_segment_scope_classification
  on kb_segment (scope_kind, scope_id, classification_rank);

comment on column kb_segment.scope_kind is
  '★ 冗余列（13 §6.4 / §11.3）：域谓词必须能在本表上**直接生效**（content_tsv 在这张表上，两条检索路都以它为驱动表），否则"两路同谓词"就退化成"两处 join 写法必须一致"。★ 只从文档继承，没有默认值：漏传是插入时报错。★ 与 kb_document.scope_kind 必须一致 —— 不一致时**检索按本列判**（那是 13 §6.4 的结构论证），由巡检报漂移';

comment on column kb_segment.scope_id is
  '★ 冗余列（同 scope_kind）：KB ⇒ 库 id；CONVERSATION ⇒ 会话 id。★ 会话域的 join 陷阱：kb_segment 原来的 kb_id 对会话附件是空的，而 `join kb_knowledge_base k on k.id = s.kb_id` 会把它们**静默全部丢掉**（内连接 + k.enabled）—— 检索侧因此改为左连 + 按域分别判启用位';

comment on column kb_segment.conversation_id is
  '★ 生成列，口径与 kb_document.conversation_id 逐字相同 = case when scope_kind = ''CONVERSATION'' then scope_id end。★ 会话域的分段过滤靠它（配合 owner_id），不必回连文档表';

comment on column kb_segment.owner_id is
  '★ 冗余列（同 scope_kind）：传输者。会话域那一支的附加条件（owner_id = :me）；库域不使用它。★ 可空只对存量与库域成立：会话域由 chk_segment_conversation_owner 强制非空';
