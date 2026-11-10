-- ════════════════════════════════════════════════════════════════════════════
--  V92 · 实体库（0.6.1）
-- ════════════════════════════════════════════════════════════════════════════
--
--  ★ 它今天**只是实体库**，不是知识图谱：没有图查询、检索链路不消费它。
--    名字刻意不叫 graph —— 叫 graph 会让人以为"图已经接上了"。
--
--  为什么现在做（`ROADMAP §11.7.1` 的准入判据）：
--    · §二 的 V0.2 清单里「知识图谱」这一行一直说不清状态（全仓 grep 只 §九 一处，
--      `04` 零设计、后端零代码、无 issue）；
--    · 而实体识别的**收益与图无关**：查询里的实体名可以扩召回，改善的是 B1。
--    ⇒ 先把实体落成数据，图等触发条件（`§11.27.5` 的 T1/T2/T3）。
--
--  ★★ `code` 一旦写入永不改变（与 `07 §十一` 的"身份面只许放不可变的东西"一致）：
--     合并、改名、移动都不得让 `code` 变 —— 实体归并靠的就是这个不可变键。
--
--  ★★ 别名表而不是就地改：合并 = 加一行别名，**原实体行不删**。
--     理由与 `ConversationShareService` 的"撤销不删行"相同 —— 要能拆回来、要能审计。
-- ════════════════════════════════════════════════════════════════════════════

create table kb_entity (
    id              uuid primary key default gen_random_uuid(),
    kb_id           uuid not null,

    -- 实体类型：ORG（机构）/ PERSON（人员）/ DOC（制度文书）/ TERM（术语）/ OTHER
    -- ★ 用 varchar 不用 enum：新增类型不该需要一次迁移（同 `kb_document.status` 的取舍）
    type            varchar(16) not null,

    name            text not null,

    -- ★ 可空：绝大多数实体第一次是从文档抽出来的，那时没有 code。
    --   有 code 的（AD/HR 导入）才有精确合并的可能。
    code            text,

    -- 描述性属性（成立日期、地址、职责范围…）。不同实体类型属性不同，
    -- 不硬造宽表；强键仍单独成列，好建索引。
    attributes      jsonb not null default '{}'::jsonb,

    -- 来源：IMPORT（结构化导入）/ RULE（规则抽取）/ LLM（模型抽取）
    -- ★ 可信度不同 ⇒ 合并时权重不同。两个都叫"财务部"、一个有 code 一个没有，
    --   那 code 说了算；都没有 code 但都有成立日期，日期一致就是强证据。
    source          varchar(16) not null,
    confidence      numeric(3, 2) not null default 1.00,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    deleted_at      timestamptz,

    constraint kb_entity_type_check
        check (type in ('ORG', 'PERSON', 'DOC', 'TERM', 'OTHER')),
    constraint kb_entity_source_check
        check (source in ('IMPORT', 'RULE', 'LLM')),
    constraint kb_entity_confidence_check
        check (confidence >= 0 and confidence <= 1)
);

-- 按库查是唯一的主路径（检索侧永远带着 kb_id）
create index idx_kb_entity_kb on kb_entity (kb_id, type) where deleted_at is null;

-- 名字精确查：别名表是主入口，这一条用于"实体表里直接按名字找"
create index idx_kb_entity_name on kb_entity (kb_id, name) where deleted_at is null;

-- ★ 强键唯一：同一个库里同一个 code 只能是一个实体。
--   这条约束是"第一档合并（code 相等 ⇒ 自动合并）"的数据库侧保证 ——
--   代码写错了也插不进去第二条。
create unique index uq_kb_entity_code
    on kb_entity (kb_id, code)
    where deleted_at is null and code is not null;


-- ── 别名表 ──────────────────────────────────────────────────────────────────

create table kb_entity_alias (
    id              uuid primary key default gen_random_uuid(),
    entity_id       uuid not null references kb_entity (id) on delete cascade,
    kb_id           uuid not null,

    alias_name      text not null,

    -- 这条别名是怎么来的：RULE（简称/全半角归一/编辑距离）/ MANUAL（人确认）/ IMPORT
    source          varchar(16) not null,
    confidence      numeric(3, 2) not null default 1.00,

    created_at      timestamptz not null default now(),

    constraint kb_entity_alias_source_check
        check (source in ('IMPORT', 'RULE', 'MANUAL'))
);

-- ★★ 这条唯一约束是整个合并机制的地基：**同一库里一个别名只指向一个实体**。
--    没有它，合并就是一笔糊涂账 —— 同一个名字在两次查询里可能指向不同实体。
create unique index uq_kb_entity_alias_name
    on kb_entity_alias (kb_id, alias_name);

create index idx_kb_entity_alias_entity on kb_entity_alias (entity_id);

comment on table kb_entity is
    '实体库（0.6.1）：不是知识图谱。检索链路今天不消费它，图查询等 §11.27.5 的触发条件。';
comment on column kb_entity.code is
    '强键，一旦写入永不改变。实体归并依赖它 —— 改名、合并、移动都不得让它变。';
comment on table kb_entity_alias is
    '别名表：合并 = 加一行别名，原实体行不删（可拆、可审计，同 ConversationShareService 的"撤销不删行"）。';
