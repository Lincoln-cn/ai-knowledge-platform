-- 会话分享链接（#187 的 shares 三条）。
--
-- 形状取「能长的」那种：一条链接 = 一个受限的读凭证，不是内容快照。
--   * scope = APP / CONVERSATION / MESSAGE（契约 ConversationShare / CreateShareBody）
--   * max_classification 在**创建时冻结** = 创建者当时的 clearance（13 §5.2 ①），
--     与 as_access_credential.max_classification 同源同义；读取时用它重新过滤（13 §5.2 ②）。
--   * message_id 只在 scope = MESSAGE 时非空（check 约束保证）。
--   * token 是链接里那一串；唯一索引让"重复生成"不会撞到同一行（撞到会 500，而不是悄悄复用）。
--   * 撤销是终态：revoked_at 只写一次；过期是 expires_at 之后不可用。两者都**不删行**（审计要能回答"谁生成过"）。
create table cv_share
(
    id                 uuid         primary key,
    token              varchar(64)  not null,
    conversation_id    uuid         not null references cv_conversation (id) on delete cascade,
    message_id         uuid         references cv_message (id) on delete cascade,
    scope              varchar(16)  not null,
    max_classification varchar(16)  not null,
    expires_at         timestamptz,
    revoked_at         timestamptz,
    created_by         uuid         not null,
    created_at         timestamptz  not null default now(),
    constraint chk_cv_share_scope check (scope in ('APP', 'CONVERSATION', 'MESSAGE')),
    constraint chk_cv_share_target check (
        (scope = 'MESSAGE' and message_id is not null)
        or (scope <> 'MESSAGE' and message_id is null)
    ),
    constraint chk_cv_share_classification check (
        max_classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL')
    )
);

create unique index uq_cv_share_token on cv_share (token);

create index idx_cv_share_conversation on cv_share (conversation_id, created_at desc);

comment on table cv_share is
    '会话分享链接（#187）。一条链接 = 一个受限的读凭证：冻结密级 + 范围 + 有效期 + 撤销；不存内容快照。';

comment on column cv_share.max_classification is
    '创建时冻结的密级上限（= 创建者当时的 clearance）。读取时用它重新过滤，不是复用创建时命中的结果。';

comment on column cv_share.revoked_at is
    '撤销是终态；不删行 —— 审计要能回答"谁生成过、谁撤销的"。过期与撤销是两件事：过期可再生成，撤销只能重新生成。';

comment on column cv_share.message_id is
    '只在 scope = MESSAGE 时非空；check 约束保证"消息链接一定指到一条消息，会话链接一定不指"。';
