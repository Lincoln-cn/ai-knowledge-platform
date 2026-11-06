-- #299 第二半 · 附件彻底删除（判据 1 的追溯链）
--
-- 为什么要有这一列：会话彻底删除时要把该会话上传附件的磁盘文件（backend/data/files/）也删掉，
-- 而"附件文档 → 上传文件"今天没有任何映射 —— kb_document 只有 (scope_id, name) 幂等键，
-- 同会话两份同名不同内容会被当同一份；cv_file 又没有 conversation_id（上传先行、绑定后至）。
-- 追溯链由此闭合：kb_document.attachment_id → cv_file.id → storage_key → 磁盘文件。
--
-- ★ 存量行无值且**不回填**：登记链路在此之前不落 attachmentId，库里没有可靠的旧映射可抄；
--   猜一个（按 name 匹配）会把"彻底删除"变成"删错的文件"—— 宁可留空，由清理路径如实跳过。
--   库层不加 NOT NULL：库域文档（scope_kind='KB'）天然没有附件来源。
-- ★ 一文件可被多个会话引用（同一 cv_file 跨会话复用），所以删除侧必须做引用计数
--   （countByAttachmentId == 0 才删 cv_file 与磁盘字节），单列 conversation_id 表达不了这个关系。

ALTER TABLE kb_document
    ADD COLUMN attachment_id uuid;

COMMENT ON COLUMN kb_document.attachment_id IS
    '来源上传文件（cv_file.id）—— 仅 scope_kind=CONVERSATION 的附件行有值；彻底删除时按它追溯磁盘文件（#299）';

CREATE INDEX idx_doc_attachment
    ON kb_document (attachment_id)
    WHERE attachment_id IS NOT NULL;
