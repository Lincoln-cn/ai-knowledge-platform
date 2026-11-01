-- ════════════════════════════════════════════════════════════════════════════
-- #257：审计能力的第一块地基 —— 表与写入点（2026-10-31）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么现在建：`13 §七` 早就把审计写成完整设计，`02 §5.2` 连建表语句都写好了，
--   但**代码里一行都没有、表也没建**。于是所有「变更要留痕」的判据都无处落地 ——
--   最近一次撞上是 `#247` 判据 ③（改密级要写审计），实现方只能如实记账"前置条件不成立"。
--   这跟 `#73` 的指标是同一类病（横切能力，永远"下一批再做"），区别是指标先补了缝、审计连缝都没有。
--
-- ★ 字段按 `02 §5.2` 的既有定义（id / actor_id / action / resource_type / resource_id /
--   detail / created_at），并按 `13 §7.1` 补四列：
--   * actor_name    —— ★ **快照**，不放外键。用户改名或离职之后，"当时是谁"仍要答得出来。
--   * classification_level / clearance_at_action —— ★ 13 §7.1 点名"事后能不能说清"的关键。
--                      本批只建列；`clearance_at_action` 的写入点等有了用户上下文再补（见 issue 评论）。
--   * result        —— 预留"拒绝/失败也要留痕"（今天恒为 SUCCESS）。
--
-- ★ 纪律（`13 §7.2` 第 3 条）：**只追加，不提供删除接口**。
--   这里用 trigger 把它变成数据库层面的约束，而不是靠"我们的代码里没写 delete"
--   —— 后者是纪律，前者才是保证。
--
-- ★ 关于"审计日志自身的密级 ≥ 被审计数据的密级"（13 §7.2 第 1 条）：
--   本表所在的库与业务同库、同一套访问控制，所以它今天不单独设密级列；
--   真要拆库或对外导出审计时，这条必须重新裁量 —— 记在这里避免被读成"已经想过了"。
-- ════════════════════════════════════════════════════════════════════════════

create table pf_audit_log
(
  id                     uuid         not null
    primary key,
  -- ★ 允许为空：系统自动动作（定时重算、迁移回填）没有"谁"
  actor_id               uuid,
  -- ★ 快照。用户改名/离职后仍要能回答"当时是谁"
  actor_name             varchar(128),
  -- 闭集，见 cn.lincoln.aiplatform.platform.domain.AuditAction
  action                 varchar(64)  not null,
  resource_type          varchar(64)  not null,
  resource_id            varchar(128) not null,
  -- 动作发生时的资源密级（分类分级的最小留痕单位，13 §7.1）
  classification_level   varchar(32),
  -- 动作发生时当事人的 clearance（今天未写入，列先留着）
  clearance_at_action    varchar(32),
  detail                 jsonb,
  result                 varchar(16)  not null default 'SUCCESS',
  created_at             timestamptz  not null default now()
);

comment on table pf_audit_log is
  '#257 审计日志（只追加）。13 §七 的设计落地点；02 §5.2 的字段 + actor_name 快照 + 密级两列';
comment on column pf_audit_log.actor_name is
  '★ 快照而非外键：用户改名/离职后，"当时是谁"仍要答得出来';
comment on column pf_audit_log.clearance_at_action is
  '动作发生时当事人的 clearance；本批只建列，写入点待用户上下文接入';
comment on column pf_audit_log.detail is
  '动作相关的结构化补充（如密级变更的 from/to）；键名属契约，不要随手改名';

-- 按对象查（"这份文档被谁动过"）与按人查（"这个人做过什么"）各一条
create index idx_pf_audit_log_resource on pf_audit_log (resource_type, resource_id, created_at desc);
create index idx_pf_audit_log_actor on pf_audit_log (actor_id, created_at desc);
create index idx_pf_audit_log_created on pf_audit_log (created_at desc);

-- ★ 只追加：数据库层面拒绝改写与删除。
--   为什么用 trigger 而不是"代码里不写 delete"：后者是纪律，前者才是保证 ——
--   任何一次手滑的 update/delete 都会在这里报错，而不是安静地把留痕改掉。
create or replace function pf_audit_log_append_only() returns trigger as
$$
begin
  raise exception 'pf_audit_log 只允许插入：审计记录不可修改、不可删除（13 §7.2 第 3 条）';
end;
$$ language plpgsql;

create trigger trg_pf_audit_log_append_only
  before update or delete
  on pf_audit_log
  for each row
execute function pf_audit_log_append_only();
