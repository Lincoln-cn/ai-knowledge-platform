package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AssistantFavorite;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的收藏仓储。
 *
 * <p>★ 与 {@link JpaAssistantRepository} 同一条判断：纯 Spring Data 接口，<strong>不继承</strong>领域的 {@code
 * AssistantFavoriteRepository} —— 否则 Spring Data 的类型会漏进领域层。
 *
 * <p>★ 写入口刻意只有三个：幂等插入、整行删除、两个只读查询。收藏没有"改"这个动作（改收藏人 = 换一个人收藏）。
 */
public interface JpaAssistantFavoriteRepository extends JpaRepository<AssistantFavorite, UUID> {

  /**
   * 幂等收藏：唯一键冲突时什么都不做。
   *
   * <p>★ 为什么用原生 SQL 而不是"先查再插"：两个并发请求都先查不到、再各插一行， 唯一键会让后到的那个抛 {@code
   * DataIntegrityViolationException} ⇒ 页面的乐观更新被回滚。 {@code on conflict do nothing}
   * 把"并发重复收藏"变成与"顺序重复收藏"相同的结果。
   *
   * @return 真正插入的行数（0 = 已经收藏过）
   */
  @Modifying
  @Query(
      value =
          """
          insert into as_assistant_favorite (id, assistant_id, user_id, created_at)
          values (:id, :assistantId, :userId, now())
          on conflict (assistant_id, user_id) do nothing
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id, @Param("assistantId") UUID assistantId, @Param("userId") UUID userId);

  /** 整行删除。返回删除行数（0 = 本来就没收藏）。 */
  long deleteByAssistantIdAndUserId(UUID assistantId, UUID userId);

  boolean existsByAssistantIdAndUserId(UUID assistantId, UUID userId);

  /** 页内批量：只取 assistantId，避免把整行（含 created_at）读回来。 */
  @Query(
      """
      select f.assistantId from AssistantFavorite f
      where f.userId = :userId and f.assistantId in :assistantIds
      """)
  Set<UUID> findFavoritedAssistantIds(
      @Param("userId") UUID userId, @Param("assistantIds") Collection<UUID> assistantIds);
}
