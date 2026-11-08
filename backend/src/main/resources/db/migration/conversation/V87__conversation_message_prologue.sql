-- V87：给消息加「种类」列 —— 应用开场白成为会话的第一条助手消息（#286）
--
-- ★ 为什么需要它：开场白原先只是空态页上的一段静态文案（前端 emptyText 读 appBrief.prologue），
--   用户发出第一句后它就消失，历史记录里也查不到。把它落成一条 ASSISTANT 消息之后，
--   必须能与「模型真实生成的回答」区分开 —— 否则「重新生成」「引用溯源」「赞/踩」会去处理它
--   （#286 判据 ①：turnCount 不含它、反馈按钮不出现在它上面、引用为空）。
--
-- ★ 默认值 'ANSWER'：存量行全部是用户提问或模型回答，语义上都属于「正常消息」，
--   不需要回填脚本；新写入的提问/回答也沿用默认值，只有开场白显式写 'PROLOGUE'。
--
-- ★ 不加索引：`kind` 只用于单行判读（`ChatMessageView.of` 与两个领域守卫），
--   没有任何查询按它过滤 —— 会话消息的读取路径永远先按 conversation_id 走 idx_msg_conv。
alter table cv_message
    add column kind varchar(16) not null default 'ANSWER';

alter table cv_message
    add constraint chk_msg_kind check (kind in ('ANSWER', 'PROLOGUE'));

comment on column cv_message.kind is
    '消息种类：ANSWER=正常消息（用户提问或模型回答）；PROLOGUE=应用开场白（#286，不是模型回答，不计入 turnCount）';
