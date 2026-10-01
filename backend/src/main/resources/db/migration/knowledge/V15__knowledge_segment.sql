-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_segment —— 文档分段（第 7 周 · 0.2.2 批 2b）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 分段是**子实体**（文档是聚合根，02 §5.1）⇒ **没有 deleted_at**。
--   软删只属于聚合根；分段随文档软删级联（文档删了分段自然不可见），
--   给子实体加 deleted_at 会制造「文档已删、分段还在」的孤儿态。
--
-- ★ content 与 search_text **必须分两列**（12 §4.2 纪律 1）：
--   - content = 原文片段（引用展示时用户看到的必须是干净原文，前缀绝不进这里）
--   - search_text = 结构化前缀（文种·部门·文档名｜章节路径）+ 分隔 + 正文
--     —— 向量化的输入 + 全文索引源，一列两用
--   混成一列的后果：引用展示会把「制度·采购部·报销规定｜第一章」当成正文显示，
--   用户以为文档里真有这句话 —— 信任一旦破了就再也修不回来。
--
-- ★ content_hash（varchar(64)，SEG-I7）：sha256(文种|部门|标题|章节路径|正文)。
--   改了前缀组成 ⇒ hash 必须变 ⇒ 重向量判定才有依据。
--
-- ★ embedding_status 四态与契约 SegmentEmbeddingStatus 一字不差
--   （frontend/src/api/ingestion.ts）：PENDING_EMBEDDING / EMBEDDING / EMBEDDED / FAILED。
--   初值 PENDING_EMBEDDING —— 向量化还没做（那是下一周），不是「已成功」。
--
-- ★ (document_id, ordinal) 唯一：同一文档内分段序号不重复，
--   重复分块（先删后写）也不会产生脏行。
-- ════════════════════════════════════════════════════════════════════════════

create table kb_segment
(
  id                uuid         not null,
  -- 所属文档（聚合根）
  document_id       uuid         not null references kb_document (id),
  -- 所属知识库（冗余列，查询时不必每次 join 文档表）
  kb_id             uuid         not null references kb_knowledge_base (id),
  -- 文档内序号（从 0 起）
  ordinal           integer      not null,
  -- 分段标题（12 §3.4）
  title             varchar(255),
  -- 章节路径 H1 > H2 > H3
  title_path        text,
  -- ★ 原文片段 —— 前缀绝不进这一列（12 §4.2 纪律 1）
  content           text         not null,
  -- ★ 前缀 + 正文 —— 向量输入 + 全文索引源（12 §4.2）
  search_text       text         not null,
  -- SEG-I7：重向量判定依据（sha256 十六进制 64 字符）
  content_hash      varchar(64)  not null,
  char_count        integer      not null,
  is_table          boolean      not null default false,
  is_code           boolean      not null default false,
  -- L4 硬切标记（12 §3.2 规则 4）
  oversized_risk    boolean      not null default false,
  enabled           boolean      not null default true,
  -- 向量化状态四态（契约 SegmentEmbeddingStatus）
  embedding_status  varchar(32)  not null default 'PENDING_EMBEDDING',
  created_at        timestamptz  not null,
  created_by        uuid,
  updated_at        timestamptz  not null,
  updated_by        uuid,
  primary key (id),

  -- ★ 同一文档内序号唯一 —— 重复分块（先删后写）的兜底
  constraint uq_segment_doc_ordinal unique (document_id, ordinal),
  constraint chk_segment_embedding_status
    check (embedding_status in ('PENDING_EMBEDDING', 'EMBEDDING', 'EMBEDDED', 'FAILED'))
);

-- 按文档查分段（有序）
create index idx_segment_document on kb_segment (document_id);
-- 按库筛分段（GET /segments 的 kbId 过滤）
create index idx_segment_kb on kb_segment (kb_id);
-- SEG-I7 重向量判定
create index idx_segment_content_hash on kb_segment (content_hash);

comment on table kb_segment is
  '文档分段（子实体，02 §5.1）。★ 没有 deleted_at —— 软删仅聚合根，分段随文档软删级联';
comment on column kb_segment.content is
  '原文片段。★ 前缀绝不进这一列（12 §4.2 纪律 1）：引用展示要看到干净原文';
comment on column kb_segment.search_text is
  '结构化前缀 + 分隔 + 正文（12 §4.2）。向量化输入 + 全文索引源，一列两用。与 content 分两列的理由见表注释';
comment on column kb_segment.content_hash is
  'SEG-I7：sha256(文种|部门|标题|章节路径|正文)。改了前缀组成 ⇒ hash 必须变 ⇒ 重向量判定有依据';
comment on column kb_segment.embedding_status is
  '向量化状态四态（SCREAMING_SNAKE_CASE，与契约 SegmentEmbeddingStatus 一字不差）。初值 PENDING_EMBEDDING';
comment on column kb_segment.oversized_risk is
  'L4 硬切标记（12 §3.2 规则 4）：被兜底层硬切过的分段，质量存疑但不拒绝入库';
