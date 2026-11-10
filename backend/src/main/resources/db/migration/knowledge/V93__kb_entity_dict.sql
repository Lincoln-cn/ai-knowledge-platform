-- 0.6.1 实体抽取字典：per-库的词表，改一行就能生效，不用重启
--
-- ★ 这张表存在的理由（用户 2026-11-10 的要求：「字典放到数据库里，不是必须要重启才能生效的」）：
--
-- 抽取器的判定依据是"这个后缀算机构"、"这个标记词映射到 BELONGS_TO"，而这些东西
-- **每个库都不一样**（人事库有"处/科"，法务库有"委员会/办公室"）。写死在代码里意味着
-- 任何一次调整都要发版重启，而这是一个内容运营会天天改的东西。
--
-- 为什么不建议灌 HanLP 的 CustomDictionary：
--   上游 javadoc 明写"动态增删改不是线程安全的"，且"通过代码动态增删不会保存到词典文件"。
--   更要紧的是我们的字典是 per-库的 —— 灌进全局词典会让 A 库的词影响 B 库的切分，
--   那是**静默的跨库污染**。所以这里只存"判定用的词表"，不参与分词。
--
-- ★ 也是"不是知识图谱"的那一套：这张表服务的是实体抽取，不是图查询。

create table kb_entity_dict (
    id          uuid primary key default gen_random_uuid(),
    kb_id       uuid        not null references kb_knowledge_base (id),
    -- ORG_SUFFIX  机构后缀（部/局/处/中心…）—— 判定，不参与分词
    -- ORG_NAME    具名机构（财务资产部…）—— 词典命中直接收，不给后缀判定的机会
    -- RELATION_MARK 关系标记词（隶属/归口/下设…）—— 必须同时给出映射到哪个受控关系
    -- TERM        术语（可与别名表配合）
    kind        varchar(24) not null,
    value       varchar(64) not null,
    -- 只有 RELATION_MARK 才有，取值见 EntityExtractor 的受控关系集合
    relation    varchar(32),
    source      varchar(16) not null default 'MANUAL',
    created_at  timestamptz not null default now(),

    -- ★ 标记词必须说清映射到哪个受控关系，其它 kind 不许带 relation
    --   这条约束把"字段填错"从运行期的静默忽略变成写入时失败
    constraint kb_entity_dict_relation_shape check (
        (kind = 'RELATION_MARK' and relation is not null)
        or (kind <> 'RELATION_MARK' and relation is null)
    ),
    constraint kb_entity_dict_kind check (
        kind in ('ORG_SUFFIX', 'ORG_NAME', 'RELATION_MARK', 'TERM')
    ),
    constraint kb_entity_dict_source check (source in ('BUILTIN', 'IMPORT', 'MANUAL'))
);

-- 同一库里同一个词只出现一次（两行同义词会让"哪个生效"取决于查询顺序）
create unique index uq_kb_entity_dict_kb_kind_value
    on kb_entity_dict (kb_id, kind, value);

create index idx_kb_entity_dict_kb_kind
    on kb_entity_dict (kb_id, kind);

comment on table kb_entity_dict is
    '0.6.1 实体抽取字典（per-库）。改一行即生效，不参与分词，只参与判定。不是知识图谱的一部分。';
comment on column kb_entity_dict.relation is
    '仅 RELATION_MARK 使用：映射到哪个受控关系（BELONGS_TO / CONTAINS / RESPONSIBLE_FOR / DEFINES）';
