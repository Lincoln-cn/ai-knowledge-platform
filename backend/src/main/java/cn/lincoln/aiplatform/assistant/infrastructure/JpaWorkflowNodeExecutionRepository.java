package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 节点执行记录的 Spring Data 仓储（实现 {@link WorkflowNodeExecutionRepository} 端口）。
 *
 * <p>★ {@code countByExecutionIdAndStatus} 由 Spring Data 按方法名推导；{@code save} 来自 {@link
 * JpaRepository}。
 */
public interface JpaWorkflowNodeExecutionRepository
    extends JpaRepository<WorkflowNodeExecutionRecord, UUID>, WorkflowNodeExecutionRepository {}
