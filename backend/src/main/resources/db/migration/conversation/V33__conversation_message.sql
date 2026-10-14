-- ════════════════════════════════════════════════════════════════════════════
-- conversation 模块：cv_message —— 消息（第 17 周「答给你听」的持久化地基，
--   2026-10-14 · 第 17 周开工）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 本批只做**数据层 + 领域状态机**：建这一张表 + 领域 Message（三态状态机 /
--   文本累积 / 引用快照 / tokens）。**不加 HTTP 端点、不写 SSE 控制器、
--   不接真模型、不改契约** —— 那是**下一批**（`/conversations/:id/messages` 的
--   SSE 端点）在它之上接。
--
-- ── 为什么是 V33 而不是 V32 ─────────────────────────────────────────────────
--
-- ★ `V32` 已经被 `conversation/V32__conversation.sql` 占用了
--   （`cv_conversation` 会话表 + `max_classification`，Issue #27，
--   **紧接着**在同一个工作树里落地）。
--   Flyway 的 `locations: classpath:db/migration` 只按**版本号**排序、
--   **不按模块目录**分段 ⇒ 全库（九个模块目录）共用一条版本号链：
--   已有的链末 = `conversation/V32`，所以本表的下一号就是 **33**。
--   ★ 更不能去改 V32 或任何更早的迁移：已应用的迁移一旦改动，
--     Flyway 的 checksum 就对不上，后端起不来（本项目已经踩过一次，是硬纪律）。
--   ★ 也就是说「conversation 模块连着拿 32、33 两号」是**正常现象**，
--     不是抢号：本模块在第 16–17 周之交才真正开工，此前只有一个 `package-info.java`。
--     1–23 / 26 / 27 归 platform / identity / modelcatalog / knowledge，
--     24 归 platform、25 / 28 归 identity、29 / 30 归 assistant、31 归 knowledge。
--
-- ── ★★ 这张表为什么是"地基"（`ROADMAP §二` 的两条硬判据落在它上面） ────────
--
-- ★ A2「**状态真实性：刷新页面不重置**」—— 判的正是"生成中的消息在库里是什么样"。
--   若状态只活在内存 / 只活在那一条 SSE 连接里，刷新页面就只能看到一个重置的界面。
--   ⇒ 它要求：**状态与已生成内容都在库里**，且那条记录在生成过程中不断被刷新
--     （`03 §1.4` 的周期性 flush：500ms 或 200 字先到者）。
-- ★ A5「**停止保留已生成内容、重生成用同一问题**」—— 判的是"停止"这个动作
--   在库里留下了什么。`03 §1.4` 的表格逐字写着：「用户点停止 → flush → `STOPPED`，
--   **内容保留**」。
--   ⇒ 它要求：`STOPPED` 是一个**有内容的一等终态**（`02 §2.6` 的 MSG-I2 原话是
--     "部分答案不能丢"），"停止"**不是清空**、也不是"把这一行删掉重来"。
-- ★ 这两条判据今天**无处可验**：没有这张表与那台状态机，它们只能靠"看着像"。
--
-- ── ★★ 列形状的出处，以及**两处刻意不听 `02 §5.2` 的地方**（已登记待裁） ────
--
-- ★ 列名 / 类型 / 可空 / 默认值 / 索引名**逐字照 `02 §5.2` 的 `cv_message` DDL**。
--   两处例外，都只有一个理由：**`frontend/src/api/**` 是冻结契约，而设计册那一行
--   写于契约冻结之前**。契约优先（`10 §8.3`「SSE 一个字节都不用改」）：
--
--   ① `status` 的取值域 —— `02:707` 写的是 `PENDING/STREAMING/DONE/STOPPED/ERROR`（五态），
--      而冻结契约的 `MessageStatus` 是 `GENERATING | GENERATED | STOPPED | FAILED`
--      （四态，`api/conversations.ts:8`）。★ 本表取**契约那四个**，理由是
--      `02 §5.2` 的注释自己也把状态标成"PENDING/STREAMING/DONE/..."（`02:340`
--      的字段表写着 `MessageStatus`），而 `MessageStatus` 这个类型名**只存在于契约侧**
--      ⇒ 设计册那张表是**意图**，契约是**线上形状**。两个取值域并存会让
--      "消息到底停在哪一态"变成两处口径。
--      ★ 副作用（必须写下来，免得被读成漏了）：设计册的 `PENDING`（还没开始生成）
--        在契约里**没有对应取值** ⇒ 本表用 `GENERATING` 承担它。这不是新增语义：
--        `GENERATING` 的字面意思就是"这一轮正在生成"，而"用户消息"不参与生成
--        （见下一条 role 的口径）。
--   ② `fail_hint` 列 —— `02 §5.2` 只有 `fail_reason`，而冻结契约的
--      `ChatMessage` 同时有 `failReason` 与 `failHint`（`api/conversations.ts:34-35`），
--      `stream-types.ts:15` 也把 error 帧定成"失败原因**与下一步**"。
--      ⇒ 只落 `fail_reason` 的话，"下一步怎么办"这句话**无处可存**（写进 reason
--        会把两件事缝成一个字段，读端再想分开就只能拆字符串）。
--      ★ 这与上面 ① 是同一类偏差（契约优先），所以按同一条口径处理：
--        **加这一列，并在交付报告里点名请 Lead 裁**（要么设计册补这一列，
--        要么契约去掉 `failHint`）。
--   ③ `reference_snapshot` 列名 —— `02 §5.2` 写的是 `references`，而它是 PostgreSQL 的
--      **保留关键字**：一个裸的 `references jsonb` 会让整条迁移报
--      `syntax error at or near "references"`（本批实测踩到过，真栈测试整类起不来）。
--      ⇒ 换成语义等价的名字，并在列上写清它的来源（见下面 DDL 那一段的注释）。
--
-- ── 三个不由本表承担的字段（写在文件头，因为它决定"读表时该期待什么"） ─────
--
-- ★ `artifacts` / `attachments` 两列**按 `02 §5.2` 落列**，但**本批不落
--   Java 侧的领域类型**（列存在，值恒为 NULL）：契约里的 `Artifact`
--   （suggestions / chart / mindmap，V1.1 启用）与 `ChatAttachment`（上传回执）
--   各有自己的生命周期，本批的判据一条都不落在它们上面。
--   ★ 刻意**不**给它们加领域方法/包装类型：那会造出"有类型、没人写"的空壳
--     （`#27` 点名的 A 类病）。
-- ★ `suggestions`（契约 `ChatMessage.suggestions: string[]`）**不另开一列**：
--   `02 §5.2` 把"推荐问题"归在 `artifacts` 里（那一列的注释逐字写着
--   "图表/导图/PPT/推荐问题"）。将来落 suggestions 时它进 `artifacts`，
--   由读侧物化出契约那一格 —— 单开一列就等于同一个东西有两处真源。
--
-- ── role 与状态的关系（`02 §2.6` 的 MSG-I3，库里也兜一道） ──────────────────
--
-- ★ `02 §2.6`：`USER` 消息的状态只能是 `DONE`。落到契约那四个取值上就是
--   **`USER` ⇒ `GENERATED`**（用户消息在落库那一刻就已经"完成"了，
--   它从来没有"生成中"这个阶段）。
-- ★ 为什么这条要写进库层（CHECK）而不只是领域层的工厂方法：
--   领域层只在应用进程里生效，而这张表将来会被**flush 循环、重生成、管理脚本**
--   各写一次。一条 `status='GENERATING'` 的用户消息在界面上会**永远转圈**
--   （没有任何地方会去收尾它，因为用户消息没有生成任务）—— 那不会报错。
-- ★ 与之配套的还有一条：`STOPPED` 与 `FAILED` **只可能属于助手消息** ——
--   但这一条**故意不写 CHECK**：它需要 `(role='ASSISTANT') OR status NOT IN (…)`
--   这种形状，而它挡住的错法（用户消息被标成 FAILED）今天没有任何写路径能产生。
--   等第 17 周的写入路径落地、有真实调用点时再加，才有可核对的依据。
--
-- ── 库层兜住的不变量（领域层那几条的另一半） ────────────────────────────────
--
-- ★ I1 **状态取值受 CHECK 约束**：契约那四个之外的一切取值被库层拒绝。
--     这一条挡的是"将来有人加第五态却只改了 Java 枚举"——那种错的症状不是报错，
--     而是前端 `onDone` 收到一个**认不出**的终态（页面停在"生成中"）。
-- ★ I2 **token 两个分量非负**：负数只能来自"减了一下"的实现，而 token 是只增计数器。
-- ★ I3 **USER ⇒ GENERATED**（见上）。
-- ★ I4 **FAILED ⇒ fail_reason 必填**：与 `kb_document` 的
--     `chk_failure`（`02:581`）**同一条口径** —— 一条没有原因的失败记录
--     在界面上只能显示一个空白的"失败"，而"下一步怎么办"恰好是用户唯一需要的东西。
-- ★ I5 **三态状态机 / 终态不可再转 / 停止保留内容** —— 库层**表达不了**
--     （CHECK 不能跨行、也不能比较新旧值），所以它们由**领域层**挡
--     （`Message` 的 `complete` / `stop` / `fail` 与私有 `appendChunk`）。
--     ★ 这一条必须写在领域层，而不是"靠调用方自觉"：后者等于允许
--       "生成完成之后又追加一段文本"这种**不会报错**的篡改。
-- ════════════════════════════════════════════════════════════════════════════

-- ★★ 列名、类型、默认值、索引名**逐字照 `02 §5.2` 的 `cv_message` DDL**（那一节是权威）；
--   只有 status 的取值域与新增的 fail_hint 两处按冻结契约走（理由见文件头）。
create table cv_message
(
  id                uuid         not null,
  conversation_id   uuid         not null,
  -- USER / ASSISTANT。★ 本批**故意不加外键** → cv_conversation：
  --   消息是会话的子实体，而会话的删除是软删（V32 的 deleted_at）——
  --   外键只会让"清一个测试会话"变成一件要按顺序做的事，且它挡不住软删场景。
  role              varchar(16)  not null,
  -- ★ Markdown 文本（`02 §2.6`）。默认 '' 而不是可空：助手消息在生成开始那一刻
  --   就已经有一行了（先有消息、再有第一个 delta）⇒ "还没有内容"是空串，不是未知。
  content           text         not null default '',
  -- ★ 四态取值域 = 契约 `MessageStatus`（见文件头 ①）。PENDING/STREAMING/DONE/ERROR 被拒。
  status            varchar(16)  not null,
  -- ★ 引用快照（`A4 引用可展开溯源` 的载体）：契约 `SourceRef` 的 10 个字段
  --   原样落在这里。★ 可空 = "这一批引用从来没到过"（检索还没跑完就收尾），
  --   而 `[]` = 明确的"零命中"—— 两者语义不同，不该被同一列的两个空值糊在一起。
  -- ★★ 列名**不叫** `02 §5.2` 里的 `references`：`references` 是 PostgreSQL 的**保留关键字**
  --   （`create table` 里一个裸的 `references jsonb` 会直接报
  --   `syntax error at or near "references"`，整条迁移失败、后端起不来 —— 本批实测踩到过）。
  --   三选一都可行：加双引号、`"references"` 到处都是、或换名。★ 取**换名**：
  --   加引号的写法会让"这一列叫 references"变成一个处处要记得加引号的约定，
  --   而漏掉一次引号就是一条新加的 SQL 直接语法错（开发期炸、生产期炸在热修上）。
  --   换成语义等价、且不与关键字冲突的 `reference_snapshot`，并在这里写下它的来源列名。
  --   ★ 这是本文件相对 `02 §5.2` 的**第三处**偏差（前两处 = status 取值域 + fail_hint），
  --     三处都已在交付报告中点名请 Lead 裁。
  reference_snapshot jsonb,
  -- 图表 / 导图 / PPT / 推荐问题（V1.1）。★ 本批只落列，值恒为 NULL（见文件头）。
  artifacts         jsonb,
  -- 附件引用（契约 ChatAttachment）。★ 同上：只落列。
  attachments       jsonb,
  -- ★ token 两个分量都可空：NULL = "这一步没有发生模型调用"，
  --   0 = "调用了、用量是 0"。★ 这个区别是"成本账"的起点（10 §8.4 把 done.tokens
  --   定成该次问答**全部**模型调用之和）；把两者糊成一个值会让统计偏低的幅度
  --   随策略开关变化，而那是最难查的一类账。
  prompt_tokens     integer,
  completion_tokens integer,
  duration_ms       bigint,
  -- 实际使用的模型（契约 meta.model 是串，而这里是模型目录的 id，与 mc/ai_model 同口径）。
  -- ★ 本批只落列：写入它需要"这一轮用的是哪个模型"这个上下文，属下一批。
  model_id          uuid,
  -- 失败原因（`fail_hint` 是"下一步怎么办"，见文件头 ②）。
  fail_reason       text,
  fail_hint         text,
  created_at        timestamptz  not null,
  updated_at        timestamptz  not null,
  primary key (id),

  constraint chk_msg_status
    check (status in ('GENERATING', 'GENERATED', 'STOPPED', 'FAILED')),
  constraint chk_msg_role
    check (role in ('USER', 'ASSISTANT')),
  -- MSG-I3：用户消息在落库那一刻就"完成"了，它没有"生成中"这个阶段
  constraint chk_msg_user_is_generated
    check (role <> 'USER' or status = 'GENERATED'),
  -- I2：token 是只增的计数器，负数只能来自"减了一下"的实现
  constraint chk_msg_prompt_tokens check (prompt_tokens is null or prompt_tokens >= 0),
  constraint chk_msg_completion_tokens check (completion_tokens is null or completion_tokens >= 0),
  -- I4：失败必须给得出原因（与 kb_document 的 chk_failure 同一条口径）
  constraint chk_msg_failed_has_reason
    check (status <> 'FAILED' or (fail_reason is not null and btrim(fail_reason) <> ''))
);

-- 消息列表（会话窗口按时间读）。★ 索引名与列序**逐字照 `02 §5.2`**：
--   `idx_msg_conv on cv_message (conversation_id, created_at)`。
-- ★ 与 `idx_conv_user` / `idx_qa_pair_*` 刻意不同的是**没有 partial 条件** ——
--   这张表**没有 `deleted_at` 列**（`02 §5.2` 的 DDL 里就没有）：消息不单独软删，
--   它随会话软删。加一个 `where deleted_at is null` 会引用一个不存在的列。
create index idx_msg_conv on cv_message (conversation_id, created_at);

comment on table cv_message is
  '消息（第 17 周「答给你听」的持久化地基）。★★ `ROADMAP §二` 的两条硬判据落在它上面：'
  'A2「状态真实性：刷新页面不重置」（状态与已生成内容都在库里，生成中按 03 §1.4 周期性 flush）· '
  'A5「停止保留已生成内容、重生成用同一问题」（03 §1.4：「用户点停止 → flush → STOPPED，内容保留」）。'
  '★ 列形状逐字照 02 §5.2，三处刻意不听它（都已在交付报告里点名请 Lead 裁）：① status 取契约的四态 MessageStatus'
  '（02:707 写的是 PENDING/STREAMING/DONE/STOPPED/ERROR）· ② 新增 fail_hint（契约 ChatMessage 有 failHint）· '
  '③ 列名 reference_snapshot（02 §5.2 写 references，而它是 PG 保留关键字）。理由见本迁移文件头';

comment on column cv_message.status is
  '★ 四态状态机 = 契约 frontend/src/api/conversations.ts 的 MessageStatus：'
  'GENERATING → GENERATED | STOPPED | FAILED。★ 02 §5.2 注释里写的是 PENDING/STREAMING/DONE/STOPPED/ERROR（五态），'
  '本表按契约取四态（冻结契约优先，10 §8.3）。★ 三个终态不可再转、且 GENERATED 不能回到 GENERATING —— '
  '库层表达不了（CHECK 不能跨行比较），由领域层 Message 的 complete/stop/fail 与私有 appendChunk 封死';

comment on column cv_message.role is
  'USER / ASSISTANT。★ MSG-I3（02 §2.6）：用户消息的状态只能是 GENERATED —— '
  '用户消息在落库那一刻就已经"完成"了，它从来没有"生成中"这个阶段（chk_msg_user_is_generated）';

comment on column cv_message.content is
  'Markdown 正文（02 §2.6）。★ 生成过程中**增量追加**；★ 停止时已生成的内容**必须保留**（03 §1.4 / MSG-I2）——'
  '"停止"不是清空、也不是删掉这一行重来，STOPPED 是一个**有内容的一等终态**（否则前端只能显示一个空白答案）';

comment on column cv_message.reference_snapshot is
  '引用快照（A4「引用可展开溯源」的载体）：契约 SourceRef 的 10 个字段（index/title/docName/kbName/score/rank/full/segmentId/documentId/kbId）原样落库。'
  '★ 归因类字段（子问题 / 轮次 / 命中查询）**不进契约、也不进这一列** —— 10 §8.2 已于 2026-10-14 裁定改走埋点（10 §7.1）';

comment on column cv_message.artifacts is
  '图表 / 导图 / PPT / 推荐问题（V1.1，02 §5.2）。★ 本批**只落列**，值恒为 NULL，也没有 Java 侧领域类型 ——'
  '契约的 suggestions 将来进这一列（02 §5.2 的列注释把"推荐问题"归在这里），不另开列';

comment on column cv_message.attachments is
  '附件引用（契约 ChatAttachment）。★ 本批**只落列**，值恒为 NULL ——'
  '"附件的内容算什么"（检索域 / 生命周期 / 密级传播）见 13 §十一；本批不预支';

comment on column cv_message.prompt_tokens is
  '提示词 token 数。★ 与 completion_tokens 一样**可空**：NULL = 这一步没有发生模型调用，0 = 调用了但用量为 0。'
  '★ 口径见 10 §8.4：done.tokens 是本次问答**全部**模型调用（查询理解 + 多跳判定 + 生成）之和；'
  'total 由 prompt + completion **导出**，不单独落列（独立取值就等于允许 total != prompt + completion，而那没有任何一处会报错）';

comment on column cv_message.fail_reason is
  '失败原因。★ chk_msg_failed_has_reason 保证 FAILED 必填（与 kb_document 的 chk_failure 同一条口径）；'
  '★ 失败路径上**已经发生**的模型调用用量仍计入 prompt_tokens / completion_tokens（10 §8.4）';

comment on column cv_message.fail_hint is
  '★ 失败后的"下一步怎么办"。**02 §5.2 的 DDL 里没有这一列**，而冻结契约的 ChatMessage 同时有 failReason 与 failHint'
  '（api/conversations.ts:34-35），stream-types.ts:15 也把 error 帧定成"失败原因与下一步" ⇒ 只落 fail_reason 会让这句话无处可存。'
  '★ 与 status 的取值域是同一类偏差（契约优先），已在交付报告中点名请 Lead 裁';

comment on column cv_message.model_id is
  '实际使用的模型（模型目录的 id，与 ai_model 同口径）。★ 本批只落列、没有任何写路径 ——'
  '"这一轮用的是哪个模型"这个上下文属第 17 周的生成链路';
