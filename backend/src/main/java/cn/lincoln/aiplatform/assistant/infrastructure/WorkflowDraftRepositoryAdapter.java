package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowDraft;
import cn.lincoln.aiplatform.assistant.domain.WorkflowDraftRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 编排草稿仓储实现（JPA）。 */
@Component
public class WorkflowDraftRepositoryAdapter implements WorkflowDraftRepository {

  private final EntityManager entityManager;

  public WorkflowDraftRepositoryAdapter(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Optional<WorkflowDraft> findByAssistantId(UUID assistantId) {
    return Optional.ofNullable(entityManager.find(WorkflowDraft.class, assistantId));
  }

  @Override
  public WorkflowDraft save(WorkflowDraft draft) {
    return entityManager.merge(draft);
  }
}
