-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_qa_pair —— 问答对（Issue #78「数据层留缝」，2026-10-13）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 本批只做**数据层留缝**：建表 + 服务层最小入口（导入 / 列出）+
--   一条「检索链路现在不消费它」的断言。**检索侧一行不接** —— 这不是"还没写完"，
--   而是**这一批刻意不做**（判据 ③ 就是钉这件事的，见下）。
--
-- ── 为什么是 V31 而不是 V30 ─────────────────────────────────────────────────
--
-- ★ `V30` 已经被 `assistant/V30__assistant_access_credential.sql` 占用了
--   （免登录访问凭证，第 16 周 · 0.4.4，**刚刚**在同一个工作树里落地）。
--   Flyway 的 `locations: classpath:db/migration` 只按 **版本号** 排序、
--   **不按模块目录** 分段 ⇒ 全库（八个模块目录）共用一条版本号链：
--   已有的链末 = `assistant/V30`，所以本表的下一号就是 **31**。
--   ★ 更不能去改 V30 或任何更早的迁移：已应用的迁移一旦改动，
--     Flyway 的 checksum 就对不上，后端起不来（本项目已经踩过一次，是硬纪律）。
--   ★ 也就是说「knowledge 模块的迁移号跳过了 24–26、28–30」是**正常现象**，
--     不是漏号：24/26 归 `platform`、25/28 归 `identity`、29/30 归 `assistant`。
--
-- ── 这张表是干什么的（`10 §7.4`） ───────────────────────────────────────────
--
-- ★ 问答对是「**B1 不达标时的补救手段**」，不是前置条件：
--   `10 §7.4` 把它定为「策略层唯一的**确定性**召回手段」—— 改写 / 扩展 / 多跳
--   都是"赌模型"，问答对是**人工标定的精确映射**。补上它，将来检索时有第 3 路
--   （与向量 / 全文并列为一路，仍走 RRF 融合，**不新增算法**）。
--   ⇒ 但也正因为如此，它**不进** `ROADMAP §二` 那三张清单：B1 ≥70% 是**结果指标**，
--     而问答对是"结果不达标时回机制二"的补救（`§二` 末尾自己就写明这一条）。
--
-- ── 为什么现在建表（而不是等真要用时再建） ─────────────────────────────────
--
-- ★ `04:235` 那句「晚做要**补迁移**」是这里的全部理由。它要修正一下当时的理解：
--   错过的是「**表结构与文档 / 分段同批迁移的便利**」，**不是数据** ——
--   问答对的来源（第 17 周的会话消息、第 18 周的赞踩）**现在还不存在**，
--   所以即便今天建表也没有历史可回填。
--   ★ 与 `V22`（分段冗余密级）是同一个形状的判断：现在开发库只有几百行，
--     加列 / 建表是一次没人看得见的秒级操作；等量产数据上再做就是要停机的事。
--
-- ── ★★ 密级为什么现在就要有（而且是**冗余列 + 谓词**） ──────────────────────
--
-- ★ `13 §6.3` 第 3 点 / `13 §6.4` 把「分段密级是冗余列，且必须能在 segment 表上
--   直接生效」定为**最贵的两处耦合点之一**：后加 ⇒ 回填全部历史行 + 全量巡检一致性。
--   ★ 问答对**将来要进检索**（`10 §7.4` 第 3 路）⇒ 它必然要带同一个密级谓词 ⇒
--     这一列**现在就必须在**，否则将来要么回填、要么让它成为"检索路里唯一没有
--     密级过滤的那一条"（后者正是 `13 §三` 闸门②点名的、本项目最贵的病）。
--
-- ★ `classification_rank` 与 `V21`（`kb_document`）/ `V22`（`kb_segment`）
--   **逐字同一个 CASE**：`JdbcSegmentSearchQuery` 里的谓词是
--   `s.classification_rank <= ?`，绑定值来自 Java 的 `ClassificationLevel.ordinal()`。
--   两边一旦不同序，后果**不是报错**，而是"某些人莫名看不到了"或更糟的
--   "某些人莫名看得见了"。★ 未知取值落 **99 = 对所有人都不可见**（fail-closed）。
--   ⇒ 本列**只建列与索引，不写任何消费它的查询** —— 那是留缝，不是接入。
--
-- ── 归属（`#78` 判据 2 要求的那个字段） ─────────────────────────────────────
--
-- ★ 归属 = `kb_id`（外键 → `kb_knowledge_base`），与 `kb_document` / `kb_segment`
--   **同一个表达**：问答对是**知识库里的一段材料**，谁能看它由所属知识库的共享
--   决定（`07 §11.2` / `V27` 的注释原文：「这是**归属**，不是可见性」）。
--   ★ 为什么不另加 `owner_id` / `visibility`（`kb_knowledge_base` 那种
--     `OwnedResource` 三件套）：那是「**资源本身**能不能被看见」的载体。
--     问答对没有独立的所有权生命周期（它不单独分享、不单独转让），
--     放一份等于允许"问答对的可见性与它的知识库不一致"这个状态存在 ——
--     而那种状态**没有人能说清该怎么判**（与 `V30` 不放 owner_id 是同一条理由）。
--
-- ── `origin` 为什么是"来源枚举"而不是自由串 ────────────────────────────────
--
-- ★ `10 §7.4` 那套用途三条（会话消息沉淀 / 赞踩沉淀 / 人工导入）各自决定
--   「这条问答对凭什么可信、被质疑时怎么追溯」。写成自由串的话这三种来源
--   在库里长得一样，而"这条是模型编的还是人标定的"是**判据级**的差别。
--   ★ 取值一律 SCREAMING_SNAKE_CASE（`14 §2.1`：标识符 = 线上值 = 库存值）。
--
-- ★ 明确**不**做（写给将来接入的人，免得把这里读成"已接入"）：
--   · 本表**没有** `search_text` / `content_tsv` / 向量列 —— 全文与向量两路都不认识它；
--   · 本表**没有**任何地方被 `JdbcSegmentSearchQuery` / `RetrievalService` 读到；
--   · `hit_count` / `last_hit_at` 只落列、**恒为 0 / null**（`10 §7.4` 要求命中计数，
--     但计数只能由检索写入，而检索这一批不接）。
-- ════════════════════════════════════════════════════════════════════════════

create table kb_qa_pair
(
  id                 uuid         not null,
  -- ★ 归属：所属知识库。可见性继承它（与 kb_document / kb_segment 同一个表达）
  kb_id              uuid         not null references kb_knowledge_base (id),
  -- 问题（人真正会问出来的那句话）
  question           text         not null,
  -- 答案（人工标定的那个回答）
  answer             text         not null,
  -- 「命中来源」：这条问答对指向哪一段原文。
  -- ★ 可空：人工导入的问答对可以没有出处；而凑一段假的分段 id 比空更糟
  --   （引用展示会指到一篇不相关的文档上，且不报错）。有值时由服务层校验
  --   「这一段真的属于 kb_id」—— 归属错位的问答对将来会静默污染检索。
  segment_id         uuid         references kb_segment (id),
  -- ★ 密级：冗余列 + 谓词（见文件头）。NOT NULL 且**没有默认值**，
  --   漏传是插入时的一次报错，而不是"静默落一个 PUBLIC"（那是一条不会报警的越权）。
  classification     varchar(16)  not null,
  -- 排序映射，与 V21 / V22 **逐字同一个 CASE**
  classification_rank smallint    generated always as (
    case classification
      when 'PUBLIC' then 0
      when 'INTERNAL' then 1
      when 'SECRET' then 2
      when 'CONFIDENTIAL' then 3
      else 99
    end) stored,
  -- ★ 来源：CONVERSATION（会话消息沉淀）/ FEEDBACK（赞踩沉淀）/ MANUAL（人工导入）/
  --   DOCUMENT（文档加工沉淀，预留）
  origin             varchar(32)  not null,
  -- 来源的定位串：会话消息 id / 反馈 id / 导入批次号。**故意不加外键** ——
  -- 它是"从哪来的"这条线索，不是归属；第 17–18 周的消息表还不存在。
  source_ref         varchar(128),
  -- ★ 命中计数（`10 §7.4` 要的那个数）。★ 本批**恒为 0**：只有检索能写它，
  --   而检索侧这一批一行不接 —— 列先占住位置，免得将来加列回填。
  hit_count          bigint       not null default 0,
  last_hit_at        timestamptz,
  created_at         timestamptz  not null,
  created_by         uuid,
  updated_at         timestamptz  not null,
  updated_by         uuid,
  -- 软删：它是自己那一行的聚合根（问答对没有子实体）。列存在 ≠ 已经有删除入口 ——
  -- 本批服务层只做导入 / 列出，删除入口留给将来（见交付报告「明确没做的」）。
  deleted_at         timestamptz,
  primary key (id),

  constraint chk_qa_pair_classification
    check (classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL')),
  constraint chk_qa_pair_origin
    check (origin in ('CONVERSATION', 'FEEDBACK', 'MANUAL', 'DOCUMENT')),
  -- 空问题 / 空答案是一条"看着有、其实没用"的行：它参与不了召回，却会被计数、
  -- 被列出、被人当成已经标定过
  constraint chk_qa_pair_question_not_blank check (btrim(question) <> ''),
  constraint chk_qa_pair_answer_not_blank check (btrim(answer) <> ''),
  constraint chk_qa_pair_hit_count check (hit_count >= 0)
);

-- 按库列出（服务层的 list 就走这一条）+ 密级谓词的支撑索引，与 V22 的
-- idx_segment_kb_classification **同一个列序**：将来的过滤永远是
-- 「kb_id in (…) and classification_rank <= ?」，前导列是 kb_id 才有选择性
-- （密级只有 4 档，低基数，单独给 rank 建索引不会被用到）。
-- ★ partial（where deleted_at is null）与 idx_doc_kb / idx_kb_owner 同口径。
create index idx_qa_pair_kb_classification on kb_qa_pair (kb_id, classification_rank)
  where deleted_at is null;

-- 按出处回溯（有 segment_id 的那些）：巡检 / 引用展示要走索引
create index idx_qa_pair_segment on kb_qa_pair (segment_id) where deleted_at is null;

-- 按来源筛（三种沉淀渠道各有多少条 —— 这是"这套机制值不值"的读数入口）
create index idx_qa_pair_origin on kb_qa_pair (origin) where deleted_at is null;

comment on table kb_qa_pair is
  '问答对（Issue #78 数据层留缝）。★ 现在**只建表 + 服务层导入/列出**：检索侧一行不接 —— '
  '它是 10 §7.4 的「B1 不达标时的补救手段」，不是前置。归属继承所属知识库（kb_id），密级是冗余列 + 谓词';

comment on column kb_qa_pair.kb_id is
  '归属：所属知识库。★ 可见性继承它 —— 与 kb_document / kb_segment 同一个表达（07 §11.2「这是归属，不是可见性」）。'
  '本表不放 owner_id / visibility：问答对不单独分享、不单独转让，放一份等于允许"它的可见性与它的库不一致"这种没人能判的状态';

comment on column kb_qa_pair.segment_id is
  '命中来源：这条问答对指向哪一段原文。★ 可空（人工导入可以没有出处；凑一个假的分段 id 比空更糟）。'
  '有值时服务层校验它真的属于 kb_id —— 归属错位将来会静默污染检索';

comment on column kb_qa_pair.classification is
  '问答对密级。★★ 冗余列 + 谓词（13 §6.3 第 3 点 / §6.4）：问答对将来要进检索，'
  '所以这一列**现在**就要在 —— 后加要回填历史行并全量巡检一致性。★ NOT NULL 无默认值：漏传是插入报错，不是静默落 PUBLIC';

comment on column kb_qa_pair.classification_rank is
  '密级排序（0=PUBLIC … 3=CONFIDENTIAL，与 Java ClassificationLevel.ordinal() 逐位一致，与 V21/V22 逐字同一个 CASE）。'
  '★ 未知取值落 99 = 对所有人都不可见（fail-closed）。★ 本批只建列与索引，不写任何消费它的查询';

comment on column kb_qa_pair.origin is
  '来源：CONVERSATION（会话消息沉淀，第 17 周）/ FEEDBACK（赞踩沉淀，第 18 周）/ MANUAL（人工导入）/ DOCUMENT（预留）。'
  '★ 三种来源决定"这条凭什么可信、被质疑时怎么追溯"，所以是枚举而不是自由串';

comment on column kb_qa_pair.hit_count is
  '命中计数（10 §7.4 要的那个数）。★★ 本批恒为 0：只有检索能写它，而检索侧不接 —— 列先占位，免得将来加列回填';
