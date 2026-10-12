package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的应用仓储适配成领域的 {@link AssistantRepository}。
 *
 * <p>★ 存在的理由与 {@code KnowledgeBaseRepositoryAdapter} 一样：<strong>框架类型不许进领域层</strong>。 本批适配的
 * 方法只有三个（保存 / 按 id 取 / 按幂等键取），所以它现在很短 —— 列表与计数会随列表端点一起长在这里， 那时才需要 {@code
 * Specification}（范围谓词与筛选共用一份，避免"列表 3 条、total 却是 10"）。
 */
@Component
public class AssistantRepositoryAdapter implements AssistantRepository {

  private final JpaAssistantRepository jpa;

  public AssistantRepositoryAdapter(JpaAssistantRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Assistant save(Assistant assistant) {
    return jpa.save(assistant);
  }

  @Override
  public Optional<Assistant> findById(UUID id) {
    return jpa.findById(id).filter(assistant -> !assistant.isDeleted());
  }

  @Override
  public Optional<Assistant> findByClientRequestIdAndOwnerId(String clientRequestId, UUID ownerId) {
    return jpa
        .findAll(
            (root, query, cb) ->
                cb.and(
                    cb.equal(root.get("clientRequestId"), clientRequestId),
                    cb.equal(root.get("ownerId"), ownerId),
                    cb.isNull(root.get("deletedAt"))))
        .stream()
        .findFirst();
  }
}
