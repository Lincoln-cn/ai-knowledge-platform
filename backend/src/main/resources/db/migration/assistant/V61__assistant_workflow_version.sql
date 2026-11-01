-- 编排应用的发布版本（`#198` 后半段：发布固化）。
--
-- ★ 为什么先建表、暂不接端点：
--   发布的核心不是"多一个端点"，而是**把草稿那一刻的图固定下来**——之后草稿继续改，
--   已经发布出去的应用行为不能跟着变。这张表就是这个"固定"的落点：graph 是发布那一刻的
--   完整快照，不是外键指向草稿。外键会让"改了草稿 ⇒ 线上行为悄悄变了"变成可能，
--   而那正是编排这类产品最难查的一类事故。
--
-- ★ version 从 1 开始、同一应用内递增且唯一（唯一约束而不是"由应用层保证"）：
--   两个人同时点发布时，应用层先查后插会双双拿到同一个号；唯一约束让第二条直接失败，
--   前端拿到冲突而不是两条记录都写着 version=2。
--
-- ★ published_by 只存 id，不存名字：名字会改，而"谁发布的"要能一直查到人。
--   展示用的名字在页面侧按 id 反查（与审计表刻意不同——审计记的是**当时**的操作人快照，
--   版本记的是**当前**可解析到的责任人，两者不是一件事）。

create table as_workflow_version (
  id            uuid primary key default gen_random_uuid(),
  assistant_id  uuid not null references as_assistant(id) on delete cascade,
  version       int  not null,
  graph         jsonb not null,
  note          text not null default '',
  published_by  uuid not null,
  published_at  timestamptz not null default now(),
  constraint uq_workflow_version unique (assistant_id, version),
  constraint ck_workflow_version_positive check (version >= 1)
);

create index idx_workflow_version_app on as_workflow_version (assistant_id, version desc);

comment on table as_workflow_version is '编排应用的发布版本（发布那一刻的图快照，#198）';
comment on column as_workflow_version.graph is '发布时的完整图快照：草稿后续改动不影响已发布版本';
comment on column as_workflow_version.note is '发布说明（可为空串）';
comment on column as_workflow_version.published_by is '发布人 iam_user.id；名字会改，这里只存 id';
