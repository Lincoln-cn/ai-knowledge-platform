-- 业务流程执行的中断与恢复（2026-11-12，0.7.4 第 1 步）。
--
-- 现状核对：`ExecutionPlan.interruptNodeIds` 这个字段从 0.7.0 起就有，编译器一直在填它，
-- 但执行器 `grep interrupt` 零命中 —— **从来没有人读过**。所以「人工介入节点」在画布上
-- 能拖、能配，跑到它就当普通节点执行完、继续往下走，用户看不出中间少了什么。
--
-- 这一次补的是「跑到中断点就停下来，把当时的状态存住」。
-- 恢复（解冻后继续跑）不在这一版 —— 它要新端点与新前端表单，单独一批。

-- ① 运行记录的状态域加一个 `PAUSED`。
--    ★ 原约束是 `check (status in ('SUCCEEDED', 'FAILED'))`，是**库层面**的拒绝：
--    应用里写 'PAUSED' 会当场被库拒（`chk_workflow_record_status`），不会静默成功。
--    这也正是本仓栽过的 `chk_msg_status` 那一课 —— 有约束时改枚举必须同步改约束。
alter table as_workflow_record
  drop constraint if exists chk_workflow_record_status;

alter table as_workflow_record
  add constraint chk_workflow_record_status
  check (status in ('SUCCEEDED', 'FAILED', 'PAUSED'));

-- ② 暂停态本身的存放处。
--    为什么单独一张表而不是往 as_workflow_record 上加列：
--    暂停状态是**一次执行中途的快照**（可能是几百 KB 的变量表），
--    而运行记录是**列表页要按行扫**的东西 —— 把大字段挂上去会让每次翻页都拖着它。
create table as_workflow_pause (
  execution_id   uuid        primary key,
  assistant_id   uuid        not null,
  node_id        text        not null,
  node_type      text        not null,
  -- 暂停时的变量快照（FlowState 的内容）。jsonb 而不是 text：
  -- 恢复时要按字段名读，存成字符串就得在处理侧再解一次。
  state          jsonb       not null,
  -- 到中断点为止已经走过的节点数，用于恢复时校验「同一张图、同一份计划」。
  steps_done     integer     not null,
  created_at     timestamptz not null default now(),
  -- 已经恢复过的暂停态不删，只标记时间：删掉后审计里就查不到「这次执行中途停过」。
  resumed_at     timestamptz
);

create index idx_workflow_pause_assistant on as_workflow_pause (assistant_id, created_at desc);

comment on table as_workflow_pause is
  '业务流程执行在人工介入节点处的中断快照（0.7.4）。恢复时按 execution_id 取回。';
comment on column as_workflow_pause.resumed_at is
  '恢复时间。null = 仍在等待；不删除行，因为「这次执行中途停过」本身是审计要回答的问题。';
