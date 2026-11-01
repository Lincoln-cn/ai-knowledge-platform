-- 会话附件（`#187` 的 `POST /files` 那一条）。
--
-- 形状取「只管元数据、字节在文件系统」：
--   * storage_key 是 FileStorePort 给的相对键，**不含用户原始文件名**（原始名可以带路径、带换行、带 200 个字）；
--   * name / mime 只用于展示与将来的解析分派，不参与定位文件；
--   * owner_id 是上传者，**今天只写不读** —— 附件真正被谁用掉，是绑到消息上那一刻决定的（下一批），
--     现在加一条"只有未绑定的人才删得掉"的规则等于给一个还不存在的动作写权限。
--   * 刻意**不做**外键到 cv_message：上传先行、绑定后至（03 机制八「先存文件、后落业务记录」），
--     上传时还没有消息。
create table cv_file
(
    id          uuid        primary key,
    name        text        not null,
    size_bytes  bigint      not null,
    mime        text,
    storage_key text        not null,
    owner_id    uuid        not null,
    created_at  timestamptz not null default now(),
    constraint chk_cv_file_size check (size_bytes > 0)
);

create unique index uq_cv_file_storage_key on cv_file (storage_key);

create index idx_cv_file_owner on cv_file (owner_id, created_at desc);

comment on table cv_file is
    '会话附件元数据（#187 的 POST /files）。字节在文件系统（platform 的 FileStorePort），本表只存元数据 + 存储键。';

comment on column cv_file.storage_key is
    'FileStorePort 返回的相对键（uuid + 扩展名），不含用户原始文件名 —— 原始名可以是任意字节，不能进路径。';

comment on column cv_file.owner_id is
    '上传者。今天只写不读：附件被谁用掉由"绑到哪条消息"决定，那一批才做使用侧口径。';

comment on column cv_file.size_bytes is
    '字节数，check > 0 —— 空文件在上传时就被拒（一条指向 0 字节文件的消息，用户点开只会看到空白）。';
