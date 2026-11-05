package cn.lincoln.aiplatform.assistant.domain;

import java.util.UUID;

/**
 * 节点执行记录的领域端口（执行器写、L1 测试读）。
 *
 * <p>★ 与 {@code WorkflowRecordRepository} 同一条口径：执行器只依赖这个端口，JPA 细节在 {@code
 * assistant.infrastructure.JpaWorkflowNodeExecutionRepository}。
 */
public interface WorkflowNodeExecutionRepository {

  WorkflowNodeExecutionRecord save(WorkflowNodeExecutionRecord record);

  /** L1 判据的读数：某次执行里、某种状态的节点记录条数（{@code SUCCEEDED} 应当为 0 如果这次执行失败了）。 */
  long countByExecutionIdAndStatus(UUID executionId, String status);
}
