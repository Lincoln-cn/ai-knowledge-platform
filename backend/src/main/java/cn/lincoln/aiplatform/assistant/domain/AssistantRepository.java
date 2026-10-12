package cn.lincoln.aiplatform.assistant.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 应用的仓储（领域端口）—— 实现在 {@code assistant.infrastructure}。
 *
 * <p>★ 与 {@code KnowledgeBaseRepository} 同一条口径：领域用一个**窄接口**表达需要什么， Spring Data / JPA 的类型 （{@code
 * Pageable} / {@code Specification} / {@code EntityManager}）留在适配器里。 理由不是洁癖：那些类型一旦进来， 领域类就没法在不起
 * Spring 的情况下被测。
 */
public interface AssistantRepository {

  /** 新增或整体保存（{@code @ElementCollection} 的子表由 Hibernate 处理）。 */
  Assistant save(Assistant assistant);

  Optional<Assistant> findById(UUID id);

  /**
   * 幂等创建用：按 {@code (client_request_id, owner_id)} 找既有应用。
   *
   * <p>★ 与知识库同一条口径（{@code V12}）：幂等是"同一个调用方的重试"，不是"全平台一个命名空间"。
   */
  Optional<Assistant> findByClientRequestIdAndOwnerId(String clientRequestId, UUID ownerId);
}
