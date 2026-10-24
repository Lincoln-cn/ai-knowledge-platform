-- 反馈处理状态（`#194`）：赞踩本身落在 `cv_message.feedback`（V35）；
-- 这里只存“运营侧处理到哪一步”，不复制问题/答案，避免同一事实两个家。
--
-- ★ 为什么主键是 message_id 而不是独立 feedback_id：
--   一条助手消息最多一条赞踩，`cv_message.feedback` 就是反馈的天然身份；
--   列表/详情直接用 message id 作为 FeedbackItem.id，历史赞踩不需要回填。
-- ★ 为什么允许没有本表行：存量赞踩已经存在，状态默认 PENDING，不要求先跑迁移回填。
create table cv_feedback_state
(
    message_id          uuid         not null references cv_message (id) on delete cascade,
    status              varchar(16)  not null default 'PENDING',
    -- 处理人显示名（契约 FeedbackItem.assignee）；批量入口传用户 id，服务层解析成显示名
    assignee            varchar(128),
    resolution          varchar(1000) not null default '',
    -- 沉淀目标知识库：只存 id，名称查询时再取；不建外键，知识库删除不应连带删反馈
    promoted_to_kb_id   uuid,
    handled_at          timestamptz,
    updated_at          timestamptz  not null default now(),
    primary key (message_id),
    constraint chk_feedback_state_status
        check (status in ('PENDING', 'PROCESSING', 'RESOLVED', 'IGNORED'))
);

create index idx_feedback_state_status on cv_feedback_state (status);

comment on table cv_feedback_state is
    '反馈处理状态（#194）。赞踩数据仍在 cv_message.feedback；本表只承载状态/指派/处理说明/沉淀目标。没有本表行 = PENDING。';
comment on column cv_feedback_state.message_id is
    '一条助手消息最多一条赞踩，message_id 即反馈身份；删除消息时连带删状态。';
comment on column cv_feedback_state.handled_at is
    '进入 RESOLVED/IGNORED 的时间；重开（回到 PROCESSING）时清空。';
