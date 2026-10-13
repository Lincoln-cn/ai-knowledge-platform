-- ════════════════════════════════════════════════════════════════════════════
-- conversation 模块：cv_conversation —— 会话（Issue #27「会话密级抬升的载体」，
--   2026-10-14 · 第 16 周末）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 本批只做**数据层 + 领域不变量**：建这一张表（列形状**逐字照 `02 §5.2`**）+
--   领域层那个**单调抬升**的方法。**不加 HTTP 端点、不改契约、不写服务层编排** ——
--   会话域真正的消费者在第 17 周的 SSE 问答链路。
--
-- ── 为什么是 V32 而不是 V31 ─────────────────────────────────────────────────
--
-- ★ `V31` 已经被 `knowledge/V31__knowledge_qa_pair.sql` 占用了
--   （问答对的「数据层留缝」，Issue #78，**紧接着**在同一个工作树里落地）。
--   Flyway 的 `locations: classpath:db/migration` 只按**版本号**排序、
--   **不按模块目录**分段 ⇒ 全库（九个模块目录）共用一条版本号链：
--   已有的链末 = `knowledge/V31`，所以本表的下一号就是 **32**。
--   ★ 更不能去改 V31 或任何更早的迁移：已应用的迁移一旦改动，
--     Flyway 的 checksum 就对不上，后端起不来（本项目已经踩过一次，是硬纪律）。
--   ★ 也就是说「conversation 模块的迁移号从 32 起」是**正常现象**，不是漏号：
--     1–23 / 26 / 27 归 platform / identity / modelcatalog / knowledge，
--     24 归 platform、25 / 28 归 identity、29 / 30 归 assistant、31 归 knowledge。
--
-- ── ★★ 这张表为什么现在建（「现在不做就得返工」的定义） ──────────────────────
--
-- ★ 会话密级 = `max(该会话历史里所有引用过的 chunk)`（`13 §2.2`）—— 它是
--   **跨轮累计**的、**单调递增**的：一旦引用过机密，**整个会话就是机密的**，
--   后续每一轮回答都按会话密级处理。
--   ⇒ 它是一个**必须绑在会话上的状态**（挂在检索侧会让"换一个会话"悄悄降级）。
--
-- ★ `#27` 的裁定（`ROADMAP §11.7` 逐条表 + `13 §2.2`）是「**落一列，不是每次现算**」：
--   载体 = `cv_conversation.max_classification`，**写侧维护 O(1)**，
--   写入点 = 「本轮回答的引用集合落库」那一刻的**同事务内**：
--   `max_classification = max(现值, max(本轮 refs 的 classification))`。
--   ★ **不为它单独开一个异步 job** —— 异步化只会制造"引用已落库、会话密级还没跟上"
--     的窗口（而那个窗口里的读数正好是"越权能看到"）。
--
-- ★★ 而**会话表今天才存在**（`conversation` 模块此前只有一个 `package-info.java`），
--   所以这一列的全部价值就是"**建表时带上它**"：
--   一旦表已建成、已写入会话，再补这一列就要**回填** ——
--   而会话密级**无法事后猜出**：它不等于"消息集合里现在有什么"，
--   而是"**历史上引用过哪些 chunk**"（引用快照那一半还没落，第 17 周才落）。
--   ★ 这正是"现在不做就返工"的定义，也是本批唯一的推进理由。
--
-- ── 与 V22 / V21 / V31 的密级列是什么关系 ───────────────────────────────────
--
-- ★ 取值同域：`PUBLIC / INTERNAL / SECRET / CONFIDENTIAL`，与
--   `kb_document.classification` / `kb_segment.classification` /
--   `kb_knowledge_base.max_classification` / `as_access_credential.max_classification`
--   **同名同域**（`14 §2.1`：标识符 = 线上值 = 库存值，SCREAMING_SNAKE_CASE）。
--   ★ 而 Java 侧的权威是 `ClassificationLevel`（`platform.access`），
--     它的**声明顺序即密级高低** ⇒ 本表的 CHECK 逐字照抄那四个值，
--     顺序与声明顺序一致（顺序是语义的一部分，不是排版）。
--
-- ★ 本表**没有** `classification_rank` 生成列 —— 与 `kb_segment` / `kb_qa_pair`
--   刻意不同：那一列是**检索谓词**（`classification_rank <= ?`）的绑定列，
--   而会话密级**不参与检索 pre-filter**（它管的是"这个会话后续按什么密级处理"，
--   过滤仍然发生在 chunk 那一级）。加一列没人读的 rank 只会多一处漂移点。
--
-- ── 这一列的不变量（`02 §2.6` / `13 §2.2`） ─────────────────────────────────
--
-- ★ I1 **非空**：`not null` ⇒ "这个会话的累计密级是多少"永远有答案。
--     可空会让"还没抬升过"和"忘了写"长得一模一样。
-- ★ I2 **默认档 = PUBLIC**：新会话还没有引用过任何 chunk ⇒ 累计值就是最低档。
--     ★ 这不是"给一个宽松的默认值"：`PUBLIC` 是**空引用集合的 max**（数学上的
--       单位元），换成 `CONFIDENTIAL` 会让每个新会话一出生就看不见自己的回答。
-- ★ I3 **取值受 CHECK 约束**：四个机器码之外的一切取值被库层拒绝。
--     ★ 这一条挡的是"将来有人加第五档却只改了 Java 枚举"——那种错的症状不是报错，
--       而是比较运算悄悄变味（见 `ClassificationLevel` 的类注释）。
-- ★ I4 **单调递增**：库层**表达不了**（CHECK 不能跨行比较），所以它由**领域层**
--    挡（`Conversation.raiseClassification`：只升不降、相等幂等）。
--     ★ 这一条必须写在领域层，而不是"靠调用方自觉"：后者等于允许一次**静默降密**。
-- ════════════════════════════════════════════════════════════════════════════

-- ★★ 列名、类型、默认值、约束名**逐字照 `02 §5.2` 的 DDL**（那一节是权威）：
--   本文件只补"库里才有的三件"——`primary key`、四个审计/展示列的注释与
--   `comment on …`（DDL 片段是示意形状，不带主键声明）。
create table cv_conversation
(
  id                   uuid         not null,
  -- ★ 归属：会话的所有者。本批**故意不加外键** → identity 模块的 `iam_user`：
  --   会话的归属是"谁在问"，与 `kb_document.org_id`（V27，写了外键）不是一类 ——
  --   用户的删除是软删，外键只会让"清一个测试用户"变成一件要按顺序做的事。
  user_id              uuid         not null,
  -- 可空 = **通用问答**（不属于任何一个应用，`02 §2.6`）。
  -- ★ 可空是语义的一部分，不是"还没填"：绝大多数会话就是从通用入口开始的。
  --   本批同样不加外键 → assistant 模块的 `as_assistant`。
  assistant_id         uuid,
  title                varchar(256) not null default '',
  -- 展示用（列表页不必为每一行数一次消息）。★ 与 #44 那条"冗余计数列"是同一族：
  --   冗余列的写法口径（谁在何时 +1 / 怎么对账）见该 Issue，本批只落列。
  message_count        integer      not null default 0,
  last_message_at      timestamptz,
  -- ★★ 会话累计密级（`13 §2.2`：max(该会话历史里所有引用过的 chunk)）——
  --    写侧维护、单调递增。见文件头 I1–I4。NOT NULL + 默认 'PUBLIC'。
  max_classification   varchar(16)  not null default 'PUBLIC',
  created_at           timestamptz  not null,
  updated_at           timestamptz  not null,
  -- 软删。★ 列存在 ≠ 已经有删除入口 —— 本批没有服务层，也就没有删除路径
  --   （不提供"列在但没人写"之外的任何暗示，见交付报告「明确没做的」）。
  deleted_at           timestamptz,
  primary key (id),

  constraint chk_conv_max_classification
    check (max_classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL'))
);

-- 会话列表（"我的会话"，按最近发言排序）。
-- ★ partial（`where deleted_at is null`）与 `idx_doc_kb` / `idx_kb_owner` / `idx_qa_pair_*`
--   同口径：`idx_conv_user` 这个名字与列序**逐字照 `02 §5.2`**。
create index idx_conv_user on cv_conversation (user_id, last_message_at desc)
  where deleted_at is null;

comment on table cv_conversation is
  '会话（Issue #27 的载体 · 第 16 周末先落表）。★★ 带 max_classification：会话累计密级 = max(该会话历史里所有引用过的 chunk)，'
  '单调递增、写侧维护 O(1)（13 §2.2）。★ 表建好之后再补这一列就要回填，而会话密级无法事后猜出'
  '（它等于"历史上引用过哪些 chunk"，引用快照第 17 周才落）—— 这就是"现在不做就返工"的定义';

comment on column cv_conversation.max_classification is
  '会话累计密级（13 §2.2）：= max(该会话历史里所有引用过的 chunk)。★★ 单调递增、写侧维护 O(1)：'
  '写入点 = 「本轮回答的引用集合落库」的同事务内，max_classification = max(现值, max(本轮 refs 的 classification))；'
  '不为它开异步 job（异步化会制造"引用已落库、会话密级还没跟上"的窗口）。'
  '★ 取值同域：与 kb_document.classification / kb_segment.classification / kb_knowledge_base.max_classification '
  '/ as_access_credential.max_classification 同为四个机器码（14 §2.1）。'
  '★ 本表刻意没有 classification_rank 生成列：那一列是检索 pre-filter 的绑定列，而会话密级不参与 pre-filter。'
  '★ 库层只能保住"非空 + 取值合法 + 默认 PUBLIC"；"只升不降"由领域层 Conversation.raiseClassification 挡';

comment on column cv_conversation.user_id is
  '归属：会话的所有者。★ 本批不加外键 → iam_user（用户的删除是软删，外键只会让清理变成有顺序的操作）';

comment on column cv_conversation.assistant_id is
  '源自应用。★ 可空 = 通用问答（02 §2.6）—— 可空是语义的一部分，不是"还没填"；本批不加外键 → as_assistant';

comment on column cv_conversation.message_count is
  '展示用的冗余计数（列表页不必逐行数消息）。★ 写法口径（谁在何时 +1、怎么对账）见 Issue #44；本批只落列';

comment on column cv_conversation.deleted_at is
  '软删。★ 列存在 ≠ 已经有删除入口：本批没有服务层，也就没有任何写它的路径';
