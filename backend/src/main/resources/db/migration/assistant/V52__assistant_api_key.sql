-- 应用 API Key（#195 的「API Key」子项）。
--
-- 口径：
--   · 一个应用可以有多个 Key（轮换不吊销旧的；页面文案就是"旧 Key 保留可单独停用"）。
--   · key 明文落库，因为契约的 ApiKeyItem.key 是明文、管理页要能复制。这一条与
--     as_access_credential.token 同口径：它们是"要展示给管理员一次/多次的凭据"，
--     不是用户口令；后者才走 bcrypt（iam_user.password_hash）。
--   · 删除应用时 Key 随级联消失；不做软删 —— 停用（enabled=false）就是"保留但不再通过鉴权"。
--   · last_used_at 允许为空（新生成的 Key 还没用过），由使用侧在鉴权成功时回写。
create table as_assistant_api_key
(
    id           uuid         not null,
    assistant_id uuid         not null references as_assistant (id) on delete cascade,
    key_value    varchar(64)  not null,
    enabled      boolean      not null default true,
    created_at   timestamptz  not null default now(),
    last_used_at timestamptz,
    primary key (id)
);

-- 轮换出来的 Key 必须全局唯一：鉴权侧只拿 key 值来解析应用。
create unique index uq_as_api_key on as_assistant_api_key (key_value);

-- 管理页按应用倒序列出。
create index idx_as_api_key_assistant on as_assistant_api_key (assistant_id, created_at desc);

comment on table as_assistant_api_key is '应用 API Key（程序化调用凭据；#195）';
comment on column as_assistant_api_key.key_value is '明文 Key；鉴权侧按此唯一解析应用';
comment on column as_assistant_api_key.enabled is '停用的 Key 保留在列表里，但不再通过鉴权';
