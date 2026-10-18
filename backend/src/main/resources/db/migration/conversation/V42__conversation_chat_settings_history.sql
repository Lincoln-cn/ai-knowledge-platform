-- 多轮对话带多少条历史消息 —— 从代码常量变成可配置
--
-- ★ 为什么需要它（用户要求）
--
--   「多轮对话的轮数配置，也尽量在对话配置中可以设置」。
--   此前它是 `ChatService.MAX_HISTORY_MESSAGES = 20` 的**硬编码常量**：
--   想改只能改代码重启，而"带多少历史"直接决定三件事：
--     ① 上下文长度（越大越容易撑爆小模型的 window）；
--     ② 推理成本与首字延迟（每条历史都要重新 prefill）；
--     ③ 跨话题的串味风险（带太多，模型会把很久以前的内容混进当前问题）。
--
-- ★ 单位是**消息条数**（user + assistant 各算一条），所以 20 = 约 10 轮。
--   界面上按"条"展示并注明"约 N 轮"，避免"轮/条"两种口径混用。
alter table cv_chat_settings
    add column if not exists history_max_messages integer not null default 20;

alter table cv_chat_settings
    add constraint chk_chat_settings_history_max
        check (history_max_messages between 0 and 200);

comment on column cv_chat_settings.history_max_messages is
    '多轮对话带的历史消息条数上限（user+assistant 各算一条，20 ≈ 10 轮）。★ 0 = 不带历史（等价单轮）';
