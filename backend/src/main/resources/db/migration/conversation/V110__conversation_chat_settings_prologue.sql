-- 通用问答的「开场白」。
--
-- ★ 这是给**用户**看的一段话（出现在对话页空态），不是给模型的系统提示词 ——
--   系统提示词是同一行的 `system_prompt` 列，两者不能混用。
--   之所以要单独一列：通用问答没有应用，也就没有 `as_assistant.prologue` 可用，
--   于是"打开就能看到自己是什么、能做什么"这件事在通用问答里一直是空的；
--   而这段话是会随产品口径变的文案，写死在页面里迟早要返工。
alter table cv_chat_settings
  add column if not exists prologue text;

comment on column cv_chat_settings.prologue is
  '通用问答空态显示的开场白（给用户看，不进 prompt）；空 = 用平台默认文案';
