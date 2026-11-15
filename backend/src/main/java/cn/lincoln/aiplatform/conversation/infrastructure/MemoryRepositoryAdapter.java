package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * {@link MemoryRepository} 的仓储适配器（{@code #296}）。
 *
 * <p>★ 这一层只做三件事：把"条数上限"翻译成 {@link PageRequest}、把会话层与 {@code scopeId} 的一致性守住、 在向量读写这种 JPA
 * 表达不了的地方走原生查询。业务判断（谁能看、要不要记）都在 Service 与领域对象里。
 */
@Component
public class MemoryRepositoryAdapter implements MemoryRepository {

  private final JpaMemoryRepository jpa;

  public MemoryRepositoryAdapter(JpaMemoryRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Memory save(Memory memory) {
    return jpa.save(memory);
  }

  @Override
  public Optional<Memory> findById(UUID id, UUID ownerId) {
    return jpa.findOwned(id, ownerId);
  }

  @Override
  public Optional<Memory> findByDedupKey(
      UUID ownerId, MemoryLevel level, MemoryKind kind, String dedupKey) {
    if (dedupKey == null || dedupKey.isBlank()) {
      return Optional.empty();
    }
    return jpa.findLiveByDedupKey(ownerId, level, kind, dedupKey);
  }

  @Override
  public List<Memory> findRecalls(
      UUID ownerId, MemoryLevel level, UUID scopeId, int minImportance, int limit) {
    requireScopeMatches(level, scopeId);
    return jpa.findRecalls(ownerId, level, scopeId, minImportance, Instant.now(), page(limit));
  }

  @Override
  public List<Memory> findAllForOwner(UUID ownerId, int limit) {
    return jpa.findAllForOwner(ownerId, page(limit));
  }

  @Override
  public void softDelete(UUID id, Instant now) {
    jpa.softDelete(id, now);
  }

  @Override
  public long countByLevel(UUID ownerId, MemoryLevel level) {
    return jpa.countByLevel(ownerId, level);
  }

  @Override
  public List<Memory> findForgettable(Instant before, int maxImportance, int limit) {
    return jpa.findForgettable(before, maxImportance, page(limit));
  }

  @Override
  public List<Memory> findMergeCandidates(
      UUID ownerId, MemoryLevel level, MemoryKind kind, int limit) {
    return jpa.findMergeCandidates(ownerId, level, kind, page(limit));
  }

  @Override
  public List<UUID> findOwnersWithLevel(MemoryLevel level, int limit) {
    return jpa.findOwnersWithLevel(level, page(limit));
  }

  /**
   * 读出有向量的记忆与向量文本。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（非会话层 {@code null}）
   * @return 每行 {@code [id, embeddingText]}
   */
  public List<Object[]> findEmbeddings(UUID ownerId, MemoryLevel level, UUID scopeId) {
    requireScopeMatches(level, scopeId);
    return jpa.findEmbeddings(ownerId, level, scopeId);
  }

  /**
   * 写一条记忆的向量。
   *
   * @param id 记忆 id
   * @param embeddingText {@code [0.1,0.2,...]} 形式
   */
  public void writeEmbedding(UUID id, String embeddingText) {
    jpa.updateEmbedding(id, embeddingText);
  }

  /**
   * ★ 会话层必须带 {@code scopeId}、其余三层必须不带。
   *
   * <p>这条断言放在仓储而不是 Service：{@code findRecalls} 有一个 caller 是做梦任务、一个 caller 是对话召回， 前者很容易忘了传 ——
   * 而"忘了传"的表现是<strong>把别人的会话细节混进这次回答</strong>，不会报错。
   *
   * @param level 层级
   * @param scopeId 会话 id
   */
  private static void requireScopeMatches(MemoryLevel level, UUID scopeId) {
    if (level == null) {
      throw new IllegalArgumentException("层级不能为空");
    }
    if (level.conversationScoped() != (scopeId != null)) {
      throw new IllegalArgumentException(
          "层级与 scope 不匹配：" + level.code() + (scopeId == null ? " 必须带会话 id" : " 不该带会话 id"));
    }
  }

  /**
   * @param limit 条数上限
   * @return 第一页、大小为 limit 的分页参数
   */
  private static PageRequest page(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("条数上限必须为正，现在写的是 " + limit);
    }
    return PageRequest.of(0, limit);
  }
}
