-- 会话评分（设计册 `05 §6.2`：`POST /conversations/{id}/feedback`）
--
-- 为什么是**独立一张表**，而不是往 `cv_conversation` 上加两列：
--   `02:415` 把 `Feedback` 定成一个**聚合**（消息、会话、用户、赞踩、评分、评论），
--   行为是「切换赞踩（互斥可撤销）、**提交评分**」，用途是 `04:233` 的「反馈列表（只读）」。
--   ⇒ 管理端要能把"评分"当成**一条条反馈**列出来；一列 `rating smallint` 列不出来。
--   ★ 这也是它与 `cv_message.feedback` 的关系：那是"逐条答案的赞踩"，这是"整场会话的评分"，
--     两者粒度不同、消费方不同（前者给用户回看，后者给管理端统计）。
--
-- 口径（写在这里，免得下一个人猜）：
--   * `score` 1–5 整数。★ 范围检查**同时**在应用层与 DB 做：
--     应用层给可读的 400（"评分需为 1–5"），DB 的 CHECK 兜住"绕过应用层"的写入。
--   * 一个用户对一场会话**只有一条**评分 ⇒ `unique (conversation_id, user_id)`，
--     再次提交是**覆盖**（`提交评分`这个行为在设计册里只有"提交"，没有"追加"）。
--   * `comment` 可空、最多 500 字（与 `cv_message.feedback` 同类的"可选补充"）。
--   * ★ 没有软删除：撤销评分 = 把 score 改回去？**不做** —— 设计册只写了"提交评分"，
--     没写"撤销评分"。★ 多做一个没被要求的状态，等于让统计口径多一种可能。

create table cv_conversation_feedback
(
    id              uuid primary key,
    conversation_id uuid        not null references cv_conversation (id) on delete cascade,
    user_id         uuid        not null,
    score           smallint    not null,
    comment         varchar(500),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint uq_conv_feedback_user unique (conversation_id, user_id),
    constraint chk_conv_feedback_score check (score between 1 and 5)
);

comment on table cv_conversation_feedback is
    '会话评分（设计册 05 §6.2：POST /conversations/{id}/feedback）。与 cv_message.feedback 不同：那是逐条答案的赞踩，这是整场会话的 1–5 分评分。';
comment on column cv_conversation_feedback.score is
    '1–5 整数。应用层先给可读的 400，DB 的 chk_conv_feedback_score 兜住绕过应用层的写入。';
comment on column cv_conversation_feedback.comment is
    '可选补充，最多 500 字。';

-- 管理端"反馈列表"按时间倒序取；也支持按会话查。
create index idx_conv_feedback_created on cv_conversation_feedback (created_at desc);
create index idx_conv_feedback_conversation on cv_conversation_feedback (conversation_id);
