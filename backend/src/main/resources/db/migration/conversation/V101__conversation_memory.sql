-- 长期记忆（`#296` 第一条）
--
-- 为什么要有这张表
-- ================
-- 现在对话上下文是「按条数截断」：`ChatService` 取本轮之前最近 N 条（N 来自「对话设置」，
-- 默认 20）。窗口外的内容就彻底消失 —— 用户上周说过"我们公司预算上限 500 万"，
-- 这周再问相关问题时模型不知道。
--
-- 四个层级（用户在 2026-11-16 明确要求分层）：
--   USER          用户层       这个人是谁（部门、角色、职责范围）—— 变化最慢
--   CONVERSATION  对话层       这一次会话在谈什么（scope_id = conversation_id）
--   PERSONA       人格化层     表达偏好、关注领域、禁忌 —— 由记忆聚合而来
--   CROSS_SESSION 跨会话层     跨多个会话的事实与结论
--
-- 为什么要分层而不是一个大池子
-- ============================
-- 因为「召回时机」与「可见范围」都不一样：
--   · 对话层只在**当前会话**召回，跨会话召回它等于把别人的话题塞进这一轮
--   · 用户/人格/跨会话三层在**所有会话**召回
--   · 对话层随会话删除而失效，其余三层不随会话走
-- 一个不分层的池子做不到"这一条只在这次会话里有效"。
--
-- 为什么原文（`cv_message`）一行不删
-- ==================================
-- `source_message_ids` 让每条记忆都能回溯到"它是从哪几句话来的"。做梦（夜间整合）
-- 只改记忆、不改消息：一旦压缩出错，还能从原文重算。删原文等于把可追溯性一起删掉。

create table cv_memory (
    id                 uuid        primary key,
    -- 记忆属于谁。★ 只有本人可见（跨人共享必须是显式动作，见 `cv_memory_grant` 的规划）。
    owner_id           uuid        not null,
    -- USER / CONVERSATION / PERSONA / CROSS_SESSION
    level              varchar(16) not null,
    -- 仅 CONVERSATION 层有值；其余三层为 null（它们不是"某一次会话的记忆"）
    scope_id           uuid,
    -- FACT / PREFERENCE / CONSTRAINT / GOAL / EVENT / SUMMARY
    kind               varchar(16) not null,
    -- 一句话说清。★ 不是原文摘录，是**提炼结果**：原文在 cv_message 里。
    content            text        not null,
    -- 它从哪几条消息来的（可追溯性的落点；做梦合并后取并集）
    source_message_ids uuid[]      not null default '{}',
    -- 1..5。抽取出这条时的主观重要性，也是遗忘的排序依据之一
    importance         smallint    not null default 3,
    -- 0..1。抽取器对这条的自评置信度；推断类记忆天然低于明确陈述
    confidence         real        not null default 1.0,
    -- 去重键：同一 owner + level + kind 下，归一化后的内容片段的哈希。
    -- ★ 只用于"完全相同"的合并；语义相近但措辞不同的合并交给做梦任务。
    dedup_key          text,
    hit_count          integer     not null default 0,
    last_hit_at        timestamptz,
    -- 到期时间。做梦的"遗忘"动作填它；到期即软删，不物理删
    expires_at         timestamptz,
    -- 被哪条新记忆取代（冲突消解的结果）。被取代的记忆仍然可查，只是不再召回
    superseded_by      uuid        references cv_memory (id),
    -- 384 维，与当前唯一启用的本地 embedding 模型一致（multilingual-e5-small）。
    -- ★ 可空：抽取时向量化失败不应让记忆写不进去（召回时退回按 importance 排序）
    embedding          vector(384),
    deleted_at         timestamptz,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    constraint chk_cv_memory_level check (level in ('USER', 'CONVERSATION', 'PERSONA', 'CROSS_SESSION')),
    constraint chk_cv_memory_kind check (kind in ('FACT', 'PREFERENCE', 'CONSTRAINT', 'GOAL', 'EVENT', 'SUMMARY')),
    constraint chk_cv_memory_importance check (importance between 1 and 5),
    constraint chk_cv_memory_confidence check (confidence between 0 and 1),
    -- 对话层必须有归属会话，其余三层不许有（否则同一个字段有两种含义）
    constraint chk_cv_memory_scope check (
        (level = 'CONVERSATION' and scope_id is not null)
        or (level <> 'CONVERSATION' and scope_id is null)
    )
);

comment on column cv_memory.level is
    'USER=这个人是谁 / CONVERSATION=这一次会话在谈什么 / PERSONA=表达偏好与关注领域 / CROSS_SESSION=跨会话的事实';
comment on column cv_memory.embedding is
    '384 维，与当前启用的本地模型一致。可空：向量化失败不应阻断记忆写入，召回时退回按 importance 排';
comment on column cv_memory.superseded_by is
    '冲突消解：被哪条新记忆取代。被取代的仍可查，但不再参与召回';
comment on column cv_memory.source_message_ids is
    '可追溯性落点：点开一条记忆要能看到它是从哪几句话来的。做梦只改记忆、不改消息';

-- 召回主路径：某人的某一层里、未删、未被取代、未过期，按 importance 与新鲜度排
create index idx_cv_memory_recall
    on cv_memory (owner_id, level, importance desc, updated_at desc)
    where deleted_at is null and superseded_by is null;

-- 对话层召回：当前会话内
create index idx_cv_memory_conversation
    on cv_memory (scope_id, importance desc)
    where deleted_at is null and superseded_by is null;

-- 去重：同一 owner + level + kind 下的 dedup_key 唯一（同一条事实不该存两遍）
create unique index uq_cv_memory_dedup
    on cv_memory (owner_id, level, kind, dedup_key)
    where dedup_key is not null and deleted_at is null;

-- 做梦的遗忘扫描：到期且未删
create index idx_cv_memory_expiring
    on cv_memory (expires_at)
    where deleted_at is null and expires_at is not null;

-- 向量召回（记忆量小，HNSW 可选；先建上，规模上来不用再改表）
create index idx_cv_memory_embedding
    on cv_memory using hnsw (embedding vector_cosine_ops)
    with (m = 16, ef_construction = 64)
    where embedding is not null and deleted_at is null;

-- 人格化层：从记忆聚合而来，单独一张表
--
-- ★ 为什么不把它当成"level=PERSONA 的记忆"：它的字段是**结构化的**（语气 / 领域 / 禁忌），
-- 召回时整段注入 system prompt，而不是按相似度挑几条。形态不同，表就分开。
create table cv_user_profile (
    user_id    uuid        primary key,
    -- 表达偏好，如"偏好要点式、先说结论"
    tone       text,
    -- 关注领域（如"合规期限""预算审批"）
    domains    text[]      not null default '{}',
    -- 禁忌（如"不要用比喻""不要提竞品名"）
    taboos     text[]      not null default '{}',
    -- 一段可整段注入的人格化摘要
    summary    text,
    -- 它由哪些记忆聚合而来（可追溯）
    source_memory_ids uuid[] not null default '{}',
    updated_at timestamptz not null default now()
);

comment on table cv_user_profile is
    '人格化层：从 cv_memory 聚合出的结构化画像。召回时整段注入，不按相似度挑';

-- 记忆的开关与旋钮（页面上的设定）。单行表，写法同 cv_chat_settings
create table cv_memory_setting (
    id                boolean     primary key check (id),
    -- 总开关。关 = 不抽取、不召回（已有记忆保留）
    enabled           boolean     not null default true,
    -- 抽取门槛：importance 低于它的不写入（1..5）
    min_importance    smallint    not null default 3,
    -- 每次召回最多注入几条
    recall_limit      integer     not null default 5,
    -- 是否把人格化画像注入 system prompt
    inject_profile    boolean     not null default true,
    -- 做梦时刻（Spring 6 段 cron）
    dream_cron        varchar(64) not null default '0 40 3 * * *',
    -- 遗忘：importance 低且这么多天零命中的记忆，做梦时标到期
    forget_after_days integer     not null default 90,
    -- 上次做梦的时刻与结果摘要（界面上"上次做梦"那一行读这里）
    last_dream_at     timestamptz,
    last_dream_note   varchar(200),
    updated_at        timestamptz not null default now(),
    updated_by        uuid,

    constraint chk_cv_memory_setting_importance check (min_importance between 1 and 5),
    constraint chk_cv_memory_setting_recall check (recall_limit between 1 and 20),
    constraint chk_cv_memory_setting_forget check (forget_after_days between 7 and 3650)
);

comment on column cv_memory_setting.min_importance is
    '抽取门槛。定 3 的理由：1-2 是"顺带提到的"，收进来会让召回被噪声占满';
comment on column cv_memory_setting.recall_limit is
    '每次注入几条。★ 与知识检索的 topN 是两回事：记忆是"关于用户的"，条数多会挤掉知识上下文';
comment on column cv_memory_setting.forget_after_days is
    '零命中多少天后标到期。★ 只标到期不删行：到期后仍可查，只是不再召回';

insert into cv_memory_setting (id) values (true);
