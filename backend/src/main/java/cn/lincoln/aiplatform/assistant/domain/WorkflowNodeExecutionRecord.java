package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 一次轻引擎执行里、单个节点的执行记录（{@code #113} 判据 4 的 L1 检查点）。
 *
 * <p>★ <strong>L1 = 每节点执行记录与业务同事务</strong>：节点执行抛异常 ⇒ 整个事务回滚 ⇒ 该次 {@code executionId} 下不留下任何 {@code
 * status=SUCCEEDED} 的节点记录。判据是「故意让某节点抛异常 ⇒ 该次执行的节点记录<strong>不得</strong>显示成功」。
 *
 * <p>★ 一张表、一节点一行：判据要的是「<strong>每节点</strong>执行记录」，所以不是一次运行一条（那是 {@link WorkflowRecord} 的 {@code
 * nodeTrail}），而是每节点一行、{@code status} 一列 —— 这样 L1 的读数是最硬的 {@code countByExecutionIdAndStatus ==
 * 0}，不用去解析 JSON。
 */
@Entity
@Table(name = "as_workflow_node_execution")
public class WorkflowNodeExecutionRecord {

  @Id
  @Column(name = "id")
  private UUID id;

  /** 同一次执行的所有节点共享这个 id；L1 就是按它查「这次执行有没有节点显示成功」。 */
  @Column(name = "execution_id", nullable = false)
  private UUID executionId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "node_type", nullable = false)
  private String nodeType;

  /** 拓扑序里的第几步（0 起）。 */
  @Column(name = "step_order", nullable = false)
  private int stepOrder;

  /** {@code SUCCEEDED} / {@code FAILED} —— L1 只认 {@code SUCCEEDED}。 */
  @Column(name = "status", nullable = false)
  private String status;

  protected WorkflowNodeExecutionRecord() {}

  public WorkflowNodeExecutionRecord(
      UUID id, UUID executionId, String nodeId, String nodeType, int stepOrder, String status) {
    this.id = id;
    this.executionId = executionId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.stepOrder = stepOrder;
    this.status = status;
  }

  public UUID id() {
    return id;
  }

  public UUID executionId() {
    return executionId;
  }

  public String nodeId() {
    return nodeId;
  }

  public String nodeType() {
    return nodeType;
  }

  public int stepOrder() {
    return stepOrder;
  }

  public String status() {
    return status;
  }
}
