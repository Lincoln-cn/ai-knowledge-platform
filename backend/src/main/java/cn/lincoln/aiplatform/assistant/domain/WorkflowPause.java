package cn.lincoln.aiplatform.assistant.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 执行在人工介入节点处的中断快照。
 *
 * <p>★ 与 {@link WorkflowRecord} 分开：运行记录是**列表页要按行扫**的东西，快照是**一次执行中途的变量表**（可能是几百 KB）。
 * 挂在同一张表上会让每次翻页都拖着它。
 *
 * <p>★ 恢复之后**不删行**，只把 {@code resumedAt} 填上 —— 「这次执行中途停过」本身是审计要回答的问题。
 */
public record WorkflowPause(
    UUID executionId,
    UUID assistantId,
    String nodeId,
    String nodeType,
    Map<String, String> state,
    int stepsDone,
    Instant createdAt,
    Instant resumedAt) {

  /** 还没被恢复过。 */
  public boolean waiting() {
    return resumedAt == null;
  }

  /** 恢复时把这一行标记成已恢复。 */
  public WorkflowPause resumed(Instant at) {
    return new WorkflowPause(
        executionId, assistantId, nodeId, nodeType, state, stepsDone, createdAt, at);
  }
}
