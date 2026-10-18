-- 推荐追问的条数 —— 从代码常量变成可配置，且支持 0 = 关闭
--
-- ★ 为什么值得可配（用户要求：「推荐问题的数量可以配置」）
--
--   这个数直接决定两件事：
--     ① 用户的选择负担 —— 3 条是"刚好能选"，6 条就变成一排按钮墙；
--     ② 生成成本与等待 —— 每多一条都要模型多写一句（1.5B 约 +1~2 秒）。
--
--   而且它是一个**纯口味**参数：不同团队对"给多少提示"的偏好差别很大，
--   没有理由由代码写死。
--
-- ★ 0 的含义是「关掉」而不是「生成 0 条」：不调模型、不落库、界面上不出现那一排。
--   关掉这件事必须能用一个数字表达，否则就只能改代码 —— 这跟"推荐问题"这种可选项的
--   定位不符（有些团队反感"AI 替我猜我想问什么"）。
alter table cv_chat_settings
    add column if not exists suggestion_count integer not null default 3;

alter table cv_chat_settings
    add constraint chk_chat_settings_suggestion_count
        check (suggestion_count between 0 and 10);

comment on column cv_chat_settings.suggestion_count is
    '推荐追问条数（0 = 关闭；默认 3；上限 10 是"再多就不叫推荐、叫菜单了"）';
