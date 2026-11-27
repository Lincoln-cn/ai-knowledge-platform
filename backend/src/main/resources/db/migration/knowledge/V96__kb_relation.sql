-- 0.6.4 实体关系表（受控四类关系）。
--
-- 设计取舍（与 `docs/ROADMAP.md` §11.26.2 的 0.6.4 一致）：
-- · 只存**抽取到**的关系，不存"没抽到"；表里没有某条关系不等于它不存在。
-- · 两端都是 kb_entity 的外键 —— 关系必须落在**已经入库的实体**上，
--   否则这条边在图里没有端点，比没有这条边更糟（javadoc 同 EntityExtractor）。
-- · segment_id 记的是**取证位置**：这条关系是从哪一段读出来的。
--   没有它，用户点开引用看到的是整篇文档，无法核对。
-- · type 只允许四类，用 check 约束钉死。放开到任意字符串会让
--   "受控关系"变成"自由关系"，下游没法做任何按类型的推理。
create table kb_relation (
  id            uuid primary key default gen_random_uuid(),
  kb_id         uuid        not null,
  from_entity   uuid        not null references kb_entity (id),
  to_entity     uuid        not null references kb_entity (id),
  type          varchar(32) not null,
  document_id   uuid,
  segment_id    uuid,
  confidence    numeric(3, 2) not null default 0.8,
  created_at    timestamptz not null default now(),
  constraint chk_kb_relation_type
    check (type in ('BELONGS_TO', 'CONTAINS', 'RESPONSIBLE_FOR', 'DEFINES')),
  constraint chk_kb_relation_not_self check (from_entity <> to_entity),
  constraint uq_kb_relation unique (from_entity, to_entity, type)
);

comment on table kb_relation is
  '实体间的受控关系（四类）。不是知识图谱：检索链路今天不消费它（同 §11.27.5 的触发条件）。';
comment on column kb_relation.segment_id is
  '取证位置。没有它，引用只能定位到文档、无法核对这句话到底在哪。';
comment on column kb_relation.confidence is
  '规则抽取的置信度。固定值不假装是模型概率（同 HanlpEntityRecognizer 的 0.8）。';

create index idx_kb_relation_from on kb_relation (kb_id, from_entity);
create index idx_kb_relation_to on kb_relation (kb_id, to_entity);
