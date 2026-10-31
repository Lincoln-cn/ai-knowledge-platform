package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 运行记录的 Spring Data 仓储（只给适配器用）。
 *
 * <p>★ 与 {@code JpaWorkflowVersionRepository} 同一条口径：它是<strong>基础设施细节</strong>，不实现领域接口、 也不被领域看见 ——
 * 领域端口在 {@code assistant.domain.WorkflowRecordRepository}。
 *
 * <p>★ 没有 {@code save} 调用方：这张表今天没有写入方（见 {@link
 * cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository} 的类注释）。
 */
interface JpaWorkflowRecordRepository extends JpaRepository<WorkflowRecord, UUID> {

  /** 某应用的一页记录（排序由适配器传进来的 {@link Pageable} 决定 —— 顺序只有一个真源）。 */
  Page<WorkflowRecord> findByAssistantId(UUID assistantId, Pageable pageable);

  /** 某应用、某种终态的一页记录。 */
  Page<WorkflowRecord> findByAssistantIdAndStatus(
      UUID assistantId, String status, Pageable pageable);

  /** 某应用的记录总数（与上面两条<strong>同一份谓词</strong>，只是不带分页）。 */
  long countByAssistantId(UUID assistantId);

  /** 某应用、某种终态的记录总数。 */
  long countByAssistantIdAndStatus(UUID assistantId, String status);
}
