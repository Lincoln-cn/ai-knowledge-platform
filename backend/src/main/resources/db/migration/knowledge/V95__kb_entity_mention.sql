-- 实体与分段的关联（0.6.2 扩召回的地基）
--
-- 为什么现在就要这张表：`kb_entity` 只记「这个库里有哪些实体」，
-- 不知道实体**出现在哪一段**。没有这一格，扩召回无从下手
-- （查询侧命中实体名之后，拿不到"这个实体在哪几段里"），
-- 而 `kb_entity` 已经在真栈上落了 4 行 —— 越晚加，这些行越没有出处。
--
-- ★ 与 `#51` 同族的教训：一条边/一条命中，如果不可溯源，排错比没有更糟。
-- 有了这张表，"为什么这一段被召回"就能回答"因为它提到了财务资产部"。
--
-- ★ 一实体一段一行（`unique (entity_id, segment_id)`）：
-- 同一段里「财务资产部」出现三次，是同一个事实，不是三条证据。
-- 频次想用时会另开一列，而不是靠行数。
create table kb_entity_mention (
    id          uuid primary key default gen_random_uuid(),
    entity_id   uuid        not null references kb_entity (id) on delete cascade,
    kb_id       uuid        not null,
    document_id uuid        not null,
    segment_id  uuid        not null,
    created_at  timestamptz not null default now(),
    constraint kb_entity_mention_unique unique (entity_id, segment_id)
);

-- 扩召回的查询方向是「给定实体 → 找它的段」
create index idx_kb_entity_mention_entity on kb_entity_mention (entity_id);

-- 删除文档/知识库时要按这两个维度清
create index idx_kb_entity_mention_document on kb_entity_mention (document_id);
create index idx_kb_entity_mention_kb on kb_entity_mention (kb_id);

comment on table kb_entity_mention is
    '实体出现在哪一段。0.6.2 扩召回从这里取「实体 → 段」；当前写入方尚未接入（V95 只建表）。';
