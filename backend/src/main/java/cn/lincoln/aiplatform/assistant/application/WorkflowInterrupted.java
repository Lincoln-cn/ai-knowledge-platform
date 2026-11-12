package cn.lincoln.aiplatform.assistant.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 跑到人工介入节点时的中断信号。
 *
 * <p>★ 它不是「失败」。调用方必须把它与 {@code RuntimeException} 分开处理：失败意味着「这次执行废了」，中断意味着「这次执行停在这里、 状态还在、可以接着跑」。
 *
 * <p>★ 为什么用异常而不是返回值：执行器的主循环只有一条出口（{@code return state}），
 * 把「停下来了」编码成中间返回值会让每一步之后都要判断一次，而实际上只有中断点那一步会停。 异常在这里正好表达「从循环里跳出去」。
 *
 * <p>★ 它必须让事务**不回滚** —— 已写入的节点执行记录是「这次执行走过哪些节点」的唯一证据， 而恢复时要接着它们往下跑。执行器上用的是
 * {@code @Transactional(noRollbackFor = WorkflowInterrupted.class)}。
 */
public class WorkflowInterrupted extends RuntimeException {

  private final UUID executionId;
  private final String nodeId;
  private final String nodeType;
  private final int stepsDone;
  private final Map<String, String> stateSnapshot;

  /** 构造时对状态做一次快照：异常抛出后原 {@link FlowState} 可能被复用或被回收。 */
  public WorkflowInterrupted(
      UUID executionId, String nodeId, String nodeType, int stepsDone, FlowState state) {
    super("停在人工介入节点：" + nodeType + "（" + nodeId + "）");
    this.executionId = executionId;
    this.nodeId = nodeId;
    this.nodeType = nodeType;
    this.stepsDone = stepsDone;
    this.stateSnapshot = new LinkedHashMap<>(state.asMap());
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

  /** 到中断点为止走过多少步（含被分支跳过的），用于恢复时校验计划没变。 */
  public int stepsDone() {
    return stepsDone;
  }

  /** 暂停那一刻的变量快照（只读副本）。 */
  public Map<String, String> stateSnapshot() {
    return Map.copyOf(stateSnapshot);
  }
}
