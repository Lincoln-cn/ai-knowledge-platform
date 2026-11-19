-- 提示词评测：4 张表（ROADMAP §11.63.3 的最小形态）
--
-- 为什么是 4 张表而不是一张宽表：
--   · set / item 是**输入**（运营编的题），run / answer 是**产出**（跑出来的结果）——
--     混在一张表里，改一次题就会把历史答案的语义改掉（"这道题"变成另一道题了）。
--   · run.draft_text 整段存草稿正文（不存版本号）：评测常常发生在"还没保存"的那一版上，
--     而一旦保存就变成新版本；存正文才能复现当时到底测了什么。
--
-- 全部 on delete cascade 到 as_prompt_config：提示词删了，它的评测集与历史无从查起
-- （列表入口随提示词消失），留着只会变成永远读不到的行 —— 与 V112 的
-- as_prompt_config_version 同一条理由。
--
-- 判据（§11.63.5 第 5 条）：删掉评测集时级联删掉题、运行、答案，不留孤儿。

create table if not exists as_prompt_eval_set (
    id               uuid        primary key,
    prompt_config_id uuid        not null references as_prompt_config (id) on delete cascade,
    name             varchar(120) not null,
    created_at       timestamptz not null default now(),
    created_by       uuid
);

-- 同一条提示词下评测集名字不许重复：两套同名会让"这次测的是哪套"变成猜的
create unique index if not exists uq_as_prompt_eval_set_name
    on as_prompt_eval_set (prompt_config_id, lower(name));
create index if not exists idx_as_prompt_eval_set_config
    on as_prompt_eval_set (prompt_config_id);

create table if not exists as_prompt_eval_item (
    id       uuid          primary key,
    set_id   uuid          not null references as_prompt_eval_set (id) on delete cascade,
    -- 顺序显式存：评测结果要按题的原始顺序看，不能靠 id 或插入时间
    ordinal  int           not null,
    question varchar(1000) not null,
    unique (set_id, ordinal)
);

create table if not exists as_prompt_eval_run (
    id             uuid        primary key,
    set_id         uuid        not null references as_prompt_eval_set (id) on delete cascade,
    -- 基线 = 跑之前已保存的那一版版本号；null 表示"用当时 as_prompt_config 的当前值"
    base_version_no int,
    -- ★ 草稿正文整段存：这一版可能永远不会被保存
    draft_text     text        not null,
    status         varchar(16) not null check (status in ('RUNNING', 'DONE', 'FAILED')),
    -- 题数在开始时定死（题中途被删不该让 run 的进度条失真）
    item_total     int         not null,
    item_done      int         not null default 0,
    fail_reason    varchar(500),
    started_at     timestamptz not null default now(),
    finished_at    timestamptz,
    started_by     uuid
);

create index if not exists idx_as_prompt_eval_run_set
    on as_prompt_eval_run (set_id, started_at desc);

create table if not exists as_prompt_eval_answer (
    id         uuid          primary key,
    run_id     uuid          not null references as_prompt_eval_run (id) on delete cascade,
    item_id    uuid          not null references as_prompt_eval_item (id) on delete cascade,
    -- 同一次运行里，每道题恰好两版各一条
    side       varchar(8)    not null check (side in ('SAVED', 'DRAFT')),
    answer     text          not null default '',
    elapsed_ms int,
    -- 第 1 层：可机械断言的项（§11.63.2）。pass/fail 是计数不是布尔 ——
    -- 一条设定要求"必须标出第几条"，答案可能标了 3 处、漏了 1 处
    pass_count int           not null default 0,
    fail_count int           not null default 0,
    -- 第 2 层：人工盲评三档。★ 必须留「差不多」（SAME）——
    -- 只给更好/更差会逼人瞎选，数据反而更脏
    verdict    varchar(8)    check (verdict in ('BETTER', 'SAME', 'WORSE')),
    note       varchar(500),
    scored_by  uuid,
    scored_at  timestamptz,
    unique (run_id, item_id, side)
);

create index if not exists idx_as_prompt_eval_answer_run
    on as_prompt_eval_answer (run_id);

comment on table as_prompt_eval_set is '提示词评测集：一条提示词的一组问题（§11.63）';
comment on table as_prompt_eval_item is '评测集里的问题；ordinal 是给结果表用的原始顺序';
comment on table as_prompt_eval_run is '一次评测运行：基线版 × 草稿正文 × 全部问题；draft_text 存正文不存版本号';
comment on table as_prompt_eval_answer is '一次运行里每道题的两版产出与评分：pass/fail 是第 1 层机械断言，verdict 是第 2 层人工三档';
comment on column as_prompt_eval_run.draft_text is '跑评测时输入框里那段正文；它可能永远不会被保存，所以整段存';
comment on column as_prompt_eval_answer.pass_count is '第 1 层：满足的断言条数（不是"是否通过"）';
