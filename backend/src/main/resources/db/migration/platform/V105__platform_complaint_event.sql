-- 投诉举报的事件流与附件（`#356`）。
--
-- ★ 为什么新开两张表，而不是往 `platform_complaint_ticket` 上加列：
--   现状是「办理」直接覆盖 `handler_note`，一单办三次就只剩最后一句话 ——
--   用户看到的是「整个过程看不见」，根子在**数据模型只存了终态**，不是界面上少画一个组件。
--   事件流是追加写（append-only）：谁在什么时候从哪个状态改到哪个状态、说了什么，各一行。
--
-- ★ 与 `pf_audit_log` 的分工：审计回答「这个对象被谁动过」（面向合规核查，可被 purge），
--   事件流回答「这张单办到哪一步了」（面向当事人，是工单本身的一部分，不给删）。
create table platform_complaint_event (
    id          uuid primary key,
    ticket_id   uuid not null references platform_complaint_ticket (id) on delete cascade,
    at          timestamptz not null,
    -- 谁做的：null = 系统（例如状态机自动流转）
    actor_id    uuid,
    actor_name  varchar(128) not null,
    -- 哪一侧做的：投诉人补充说明 vs 处理人办理。同一张单上两种口吻不能混成一个"操作人"字段
    side        varchar(16) not null check (side in ('REQUESTER', 'HANDLER', 'SYSTEM')),
    -- CREATED = 提交；NOTE = 补充说明/回复；STATUS_CHANGED = 状态流转；ATTACHMENT = 只挂了个附件
    kind        varchar(24) not null check (kind in ('CREATED', 'NOTE', 'STATUS_CHANGED', 'ATTACHMENT')),
    from_status varchar(24),
    to_status   varchar(24),
    note        text
);

-- 一张单的事件按时间正序读（时间线），也是唯一会用的访问路径
create index idx_complaint_event_ticket on platform_complaint_event (ticket_id, at);

-- 附件：截图、日志文件。★ 字节存 FileStorePort，这里只存键与元数据 ——
--   与 `cv_file` 同一套约定（原始名只用于展示，绝不参与定位文件，否则这端口就有路径穿越）。
create table platform_complaint_attachment (
    id            uuid primary key,
    ticket_id     uuid not null references platform_complaint_ticket (id) on delete cascade,
    -- 挂在哪条事件上；null = 直接挂在工单上（提交时一次性带上的材料）
    event_id      uuid references platform_complaint_event (id) on delete set null,
    file_name     varchar(255) not null,
    content_type  varchar(128) not null,
    size_bytes    bigint not null,
    storage_key   varchar(512) not null,
    uploaded_at   timestamptz not null,
    uploaded_by   uuid,
    uploaded_name varchar(128) not null,
    side          varchar(16) not null check (side in ('REQUESTER', 'HANDLER'))
);

create index idx_complaint_attachment_ticket on platform_complaint_attachment (ticket_id, uploaded_at);

-- ★ 不写 retention / 不做 purge：投诉举报是第十五条的举证材料，
--   保留期口径归 `#75`（数据生命周期三缺）统一定，这里先不自己发明一个。
