-- 推荐追问加一个显式开关
--
-- ★ 为什么不是"继续用条数 0 表示关闭"（用户要求：「推荐追问 做一个开关」）
--
--   用 0 表示关闭是"把关闭藏在一个数量里"：界面上只有一个数字框，
--   用户想关掉时得知道"填 0"，而填 0 长得像"这一项没配好"。
--   开关是**一眼能看懂的状态**，条数是**开着之后才需要决定的事** —— 两者不是同一个东西。
--
-- ★ 数据口径（迁移顺便把旧数据对齐，避免"开着但条数是 0"这种自相矛盾的行）：
--   旧语义下 `suggestion_count = 0` 就是关闭 ⇒ 回填时把 enabled 设成 count > 0，
--   并把那些 0 归一到默认 3。之后约束收紧为 1~10（0 不再是合法值）。
alter table cv_chat_settings
    add column if not exists suggestion_enabled boolean not null default true;

-- 旧数据回填：count = 0 的那行视为"关闭"，条数归一成默认 3
update cv_chat_settings set suggestion_enabled = false where suggestion_count = 0;
update cv_chat_settings set suggestion_count = 3 where suggestion_count = 0;

-- 约束收紧：条数只在"开着"时有意义，且必须是正数
alter table cv_chat_settings drop constraint if exists chk_chat_settings_suggestion_count;
alter table cv_chat_settings
    add constraint chk_chat_settings_suggestion_count
        check (suggestion_count between 1 and 10);

comment on column cv_chat_settings.suggestion_enabled is
    '是否生成推荐追问（关掉 ⇒ 不调模型、不落库、界面上不出现那一排）';
