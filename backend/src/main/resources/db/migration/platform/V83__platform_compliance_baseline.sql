-- #83 合规要件四件套 · 能力 1：投诉举报工单（第十五条）+ 合规设置表基线
--
-- ★ 处置判定（ROADMAP §11.7.1 逐条判）：投诉举报 = 【真做】。理由：① 工单数据无法回填
--   （历史投诉没有功能就没有记录）② 表晚建要补迁移、状态机与处理入口挂点会写死在调用点上。
--   而「算法备案登记」= 【留缝】（本迁移先立合规设置表，备案号/评估结论列随后 ALTER 加，
--   不建申报/变更/注销流程）。
--
-- ★ 反馈时限的天数是【运营口径】：issue #83 引用的《生成式人工智能服务管理暂行办法》第十五条
--   只要求「公布处理流程与反馈时限」，未规定具体数字。缺省 15 天需业务方/法务确认后调整，
--   不是法条数字 —— 不许把它当法条依据引用。

create table platform_complaint_ticket
(
  id             uuid         not null,
  ticket_no      varchar(32)  not null,
  kind           varchar(16)  not null,
  subject        varchar(200) not null,
  content        text         not null,
  contact        varchar(200),
  status         varchar(16)  not null,
  handler_id     uuid,
  handler_name   varchar(100),
  handler_note   text,
  handled_at     timestamptz,
  due_at         timestamptz  not null,
  requester_id   uuid         not null,
  requester_name varchar(100) not null,
  created_at     timestamptz  not null,
  updated_at     timestamptz  not null,
  primary key (id)
);

create unique index ux_platform_complaint_ticket_no on platform_complaint_ticket (ticket_no);
create index ix_platform_complaint_status on platform_complaint_ticket (status);
create index ix_platform_complaint_requester on platform_complaint_ticket (requester_id);

create table platform_compliance_settings
(
  id                      varchar(36)  not null,
  complaint_feedback_days int          not null,
  updated_at              timestamptz  not null,
  updated_by              uuid,
  primary key (id)
);

insert into platform_compliance_settings (id, complaint_feedback_days, updated_at)
values ('1', 15, now());
