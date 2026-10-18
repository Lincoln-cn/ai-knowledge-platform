-- 通用会话的「初始提示词」（系统提示词）—— 平台级，对所有通用问答生效
--
-- ★ 为什么需要它：模型没有系统提示词时会"自由发挥"（示例：用户问代码，它把知识库里的
--   隐私声明当上下文复述一遍）。这一列让管理员能定一条"你是谁、怎么回答"的口径。
--
-- ★ 为什么放在 cv_chat_settings 而不是又加进 yaml：它是**运行时设置**
--   （页面上改、立刻对新会话生效），而 yaml 改了要重启。
--
-- ★ 只对「通用问答」生效（appId 为空）。应用有自己的 role_prompt（as_assistant.role_prompt），
--   两者不叠加 —— 否则同一个回答会被两条口径同时约束，冲突时谁赢没人说得清。
alter table cv_chat_settings
    add column if not exists system_prompt text;

comment on column cv_chat_settings.system_prompt is
    '通用问答的初始提示词（系统提示词）。★ 空 = 不加前缀；应用会话不读这一列（应用有 as_assistant.role_prompt）';
