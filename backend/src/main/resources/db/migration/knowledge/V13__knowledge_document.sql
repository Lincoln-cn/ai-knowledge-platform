-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_document —— 文档建档元数据（第 5 周 · 0.2.0 批 2）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 建档必填三项（DOC-I6 / 12 §5.1）：doc_type（文种）· department（责任部门）
--   · classification（密级）。这三项分别决定「召回前缀」与「安全过滤」，
--   **不能有默认值** —— 密级默认「未知」要么全放行（泄密）要么全拦（不可用）。
--   列全部 NOT NULL，从库层掐死「先建后补」的偷懒路径。
--
-- ★ classification 是**文档密级**，入库时必须 ≤ 所属库的 max_classification
--   （ceiling，13 §6）。校验在应用层给出可读原因；这里只负责把值存住。
--
-- ★ status 取值一律 SCREAMING_SNAKE_CASE（14 §2.1），与契约
--   frontend/src/api/ingestion.ts 的 DocumentStatus 七态一字不差。
--   初值是 PENDING_PARSE（未解析那一态）—— 不是「成功」，否则界面会把
--   还没加工的文档显示成可检索。
--
-- ★ fail_reason / fail_hint：DOC-I2 —— 失败时必须写入**可读原因**与下一步建议。
--   原项目的失败原因字段一直存在却从不展示，是典型的静默失效。
--
-- ★ 解析/分块/向量化的进度列（char_count / segment_count / processed_segments）
--   本批只落列、恒为 0 —— 真实进度随第 6–7 周加工链回填（02 §5.2 的形状先占住，
--   后加列 ⇒ 已入库文档要逐一回填）。
-- ════════════════════════════════════════════════════════════════════════════

create table kb_document
(
  id                   uuid         not null,
  -- 所属知识库（02 §5.2 叫 knowledge_base_id；此处从任务口径用 kb_id，与契约 kbId 对齐）
  kb_id                uuid         not null references kb_knowledge_base (id),
  -- 文件显示名（可重命名）
  name                 varchar(255) not null,
  -- 来源。契约 DocumentItem.source 是自由串（mock 用 'local'）—— 14 §2.4 特意留小写
  source               varchar(32)  not null default 'local',
  -- ★ 建档必填三项（DOC-I6）
  doc_type             varchar(32)  not null,
  department           varchar(64)  not null,
  classification       varchar(16)  not null,
  -- 加工状态七态（DocumentStatus）。初值 PENDING_PARSE
  status               varchar(32)  not null,
  -- DOC-I2：失败必填可读原因 + 下一步建议
  fail_reason          text,
  fail_hint            text,
  -- 进度/规模列：本批只落列，随加工链回填
  char_count           bigint       not null default 0,
  segment_count        integer      not null default 0,
  processed_segments   integer      not null default 0,
  enabled              boolean      not null default true,
  created_at           timestamptz  not null,
  created_by           uuid,
  updated_at           timestamptz  not null,
  updated_by           uuid,
  -- 软删：仅聚合根使用（02 §5.1）；文档是聚合根（分段挂在它下面）
  deleted_at           timestamptz,
  primary key (id),

  constraint chk_doc_status
    check (status in ('PENDING_PARSE', 'SEGMENTING', 'PENDING_EMBEDDING',
                      'EMBEDDING', 'SUCCEEDED', 'SEGMENT_FAILED', 'EMBEDDING_FAILED')),
  constraint chk_doc_classification
    check (classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL')),
  -- DOC-I3：0 ≤ processed_segments ≤ segment_count
  constraint chk_doc_progress
    check (processed_segments between 0 and segment_count),
  -- DOC-I2：状态是失败时 fail_reason 必须非空 —— 消灭「静默失败」
  constraint chk_doc_failure
    check (status not like '%FAILED' or fail_reason is not null)
);

-- 列表按库过滤 + 分页（契约 listDocuments 的 kbId 维度）
create index idx_doc_kb on kb_document (kb_id) where deleted_at is null;
-- 列表页按状态筛（契约 DocumentQuery.status）
create index idx_doc_status on kb_document (status) where deleted_at is null;

comment on table kb_document is
  '文档聚合根。建档必填三项（文种/责任部门/密级）+ ceiling 校验（13 §6）；解析分段是第 6–7 周的事';
comment on column kb_document.doc_type is
  '文种（合同/制度/报销规定…）。★ 建档必填：进召回前缀（12 §5.2），不能有默认值';
comment on column kb_document.department is
  '责任部门。★ 建档必填：进召回前缀，不能有默认值';
comment on column kb_document.classification is
  '文档密级。★ 建档必填：进安全过滤；入库时必须 ≤ 库的 max_classification（ceiling）';
comment on column kb_document.status is
  '加工状态七态（SCREAMING_SNAKE_CASE，14 §2.1）。初值 PENDING_PARSE（未解析）';
comment on column kb_document.fail_reason is
  '失败的可读原因（DOC-I2）。状态为 *FAILED 时必填 —— 不许静默失败';
comment on column kb_document.fail_hint is
  '失败时的下一步建议（08 §4.1：只给原因不给下一步 = 让用户停在原地）';
