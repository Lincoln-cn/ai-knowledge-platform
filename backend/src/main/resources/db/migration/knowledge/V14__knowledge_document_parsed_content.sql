-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_document.parsed_content —— 解析出的 Markdown（第 6 周 0.2.1）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 解析链（12 §2.1）产物是 Markdown。失败时也可能是**部分内容**（Tika 的
--   ToMarkdownContentHandler 在 parse 中途失败时已缓冲的片段）——「解析失败
--   = 整篇丢弃」会让用户对着空文档猜内容。
--
-- ★ 与 fail_reason 的关系：失败态下 fail_reason 必填（DOC-I2，V13 的
--   chk_doc_failure 已钉）；parsed_content 可空串，表示「连半个字都没解出来」。
--
-- ★ 为什么单独一列而不是塞进 fail_reason：两者语义不同 —— 一个是给人看的
--   失败说明，一个是给分块器用的正文。混在一列迟早分叉。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_document
  add column parsed_content text;

comment on column kb_document.parsed_content is
  '解析出的 Markdown（12 §2.1）。失败时可能是部分内容（handler 已缓冲片段）';
