package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AssistantFavoriteRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的收藏仓储适配成领域的 {@link AssistantFavoriteRepository}。
 *
 * <p>★ 空集合在<strong>这一层</strong>被挡掉：JPQL 的 {@code in ()} 是语法错误，而"这一页没有应用"是完全合法的
 * 输入（空市场、筛选后无结果）。让调用方每次先判空，早晚会有人漏。
 */
@Component
public class AssistantFavoriteRepositoryAdapter implements AssistantFavoriteRepository {

  private final JpaAssistantFavoriteRepository jpa;

  public AssistantFavoriteRepositoryAdapter(JpaAssistantFavoriteRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void add(UUID assistantId, UUID userId) {
    // ★ 主键在这里生成：库侧没有 gen_random_uuid() 依赖，迁移脚本也不引入扩展
    jpa.insertIfAbsent(UUID.randomUUID(), assistantId, userId);
  }

  @Override
  public void remove(UUID assistantId, UUID userId) {
    jpa.deleteByAssistantIdAndUserId(assistantId, userId);
  }

  @Override
  public boolean exists(UUID assistantId, UUID userId) {
    return jpa.existsByAssistantIdAndUserId(assistantId, userId);
  }

  @Override
  public Set<UUID> favoritedAmong(UUID userId, Collection<UUID> assistantIds) {
    if (assistantIds == null || assistantIds.isEmpty()) {
      return Set.of();
    }
    return jpa.findFavoritedAssistantIds(userId, assistantIds);
  }
}
