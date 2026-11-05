-- V55: 轻引擎「每节点执行记录」表（#113 判据 4 的 L1 检查点）。
--
-- ★ L1 = 每节点执行记录与业务同事务：节点执行抛异常 ⇒ 整个事务回滚 ⇒ 该次 execution_id 下
--   不留下任何 status='SUCCEEDED' 的行。判据读数 = count(execution_id=X and status='SUCCEEDED') == 0。
--
-- ★ 一节点一行（区别于 as_workflow_record 的 nodeTrail JSON 数组）：L1 要的是「每节点」记录，
--   这样断言不必解析 JSON。
CREATE TABLE as_workflow_node_execution (
  id UUID PRIMARY KEY,
  execution_id UUID NOT NULL,
  node_id VARCHAR(255) NOT NULL,
  node_type VARCHAR(255) NOT NULL,
  step_order INT NOT NULL,
  status VARCHAR(32) NOT NULL
);

CREATE INDEX idx_as_wf_node_execution ON as_workflow_node_execution (execution_id);
