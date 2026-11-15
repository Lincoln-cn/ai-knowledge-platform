package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Memory} 的 Spring Data 仓储（{@code #296}）。
 *
 * <p>★ 所有查询都把"已删 / 已被取代"写进条件，而不是让调用方自己过滤： 这两条是<strong>记忆的可见性定义</strong>，漏一处就会让用户看到他已经删掉的记忆。
 */
public interface JpaMemoryRepository extends JpaRepository<Memory, UUID> {

  /**
   * 按 id + 归属取（★ 归属必须进条件：只按 id 取等于任何登录用户都能读别人的记忆）。
   *
   * @param id 记忆 id
   * @param ownerId 归属用户
   * @return 记忆
   */
  @Query("select m from Memory m where m.id = :id and m.ownerId = :ownerId")
  Optional<Memory> findOwned(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

  /**
   * 按去重键取。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param kind 种类
   * @param dedupKey 去重键
   * @return 记忆
   */
  @Query(
      "select m from Memory m where m.ownerId = :ownerId and m.level = :level"
          + " and m.kind = :kind and m.dedupKey = :dedupKey"
          + " and m.deletedAt is null and m.supersededBy is null")
  Optional<Memory> findLiveByDedupKey(
      @Param("ownerId") UUID ownerId,
      @Param("level") MemoryLevel level,
      @Param("kind") MemoryKind kind,
      @Param("dedupKey") String dedupKey);

  /**
   * 召回候选：按层粗筛。
   *
   * <p>会话层的 {@code scopeId} 由调用方保证非空（{@code MemoryRepositoryAdapter} 里有断言）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（非会话层传 {@code null}）
   * @param minImportance 重要度下限
   * @param now 当前时间（判过期）
   * @param limit 条数上限
   * @return 候选
   */
  @Query(
      "select m from Memory m where m.ownerId = :ownerId and m.level = :level"
          + " and (:scopeId is null or m.scopeId = :scopeId)"
          + " and m.importance >= :minImportance"
          + " and m.deletedAt is null and m.supersededBy is null"
          + " and (m.expiresAt is null or m.expiresAt > :now)"
          + " order by m.importance desc, m.updatedAt desc")
  List<Memory> findRecalls(
      @Param("ownerId") UUID ownerId,
      @Param("level") MemoryLevel level,
      @Param("scopeId") UUID scopeId,
      @Param("minImportance") int minImportance,
      @Param("now") Instant now,
      Pageable pageable);

  /**
   * 页面列表（含已被取代的，但已删的不显示）。
   *
   * @param ownerId 归属用户
   * @param limit 条数上限
   * @return 列表
   */
  @Query(
      "select m from Memory m where m.ownerId = :ownerId and m.deletedAt is null"
          + " order by m.updatedAt desc")
  List<Memory> findAllForOwner(@Param("ownerId") UUID ownerId, Pageable pageable);

  /**
   * 某层条数。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @return 条数
   */
  @Query(
      "select count(m) from Memory m where m.ownerId = :ownerId and m.level = :level"
          + " and m.deletedAt is null and m.supersededBy is null")
  long countByLevel(@Param("ownerId") UUID ownerId, @Param("level") MemoryLevel level);

  /**
   * 可遗忘候选（{@code hitCount = 0} 是硬条件）。
   *
   * @param before 更新时间上限
   * @param maxImportance 重要度上限
   * @param limit 最多几条
   * @return 候选
   */
  @Query(
      "select m from Memory m where m.hitCount = 0 and m.importance <= :maxImportance"
          + " and m.updatedAt < :before"
          + " and m.deletedAt is null and m.supersededBy is null")
  List<Memory> findForgettable(
      @Param("before") Instant before,
      @Param("maxImportance") int maxImportance,
      Pageable pageable);

  /**
   * 同一 owner + level + kind 的候选（去重合并用）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param kind 种类
   * @param limit 最多几条
   * @return 候选
   */
  @Query(
      "select m from Memory m where m.ownerId = :ownerId and m.level = :level"
          + " and m.kind = :kind and m.deletedAt is null and m.supersededBy is null"
          + " order by m.importance desc, m.updatedAt desc")
  List<Memory> findMergeCandidates(
      @Param("ownerId") UUID ownerId,
      @Param("level") MemoryLevel level,
      @Param("kind") MemoryKind kind,
      Pageable pageable);

  /**
   * 取"某一层有活记忆"的归属人（做梦任务按人重算画像用）。
   *
   * @param level 层级
   * @param pageable 分页
   * @return 归属人
   */
  @Query(
      "select distinct m.ownerId from Memory m"
          + " where m.level = :level and m.deletedAt is null and m.supersededBy is null")
  List<UUID> findOwnersWithLevel(@Param("level") MemoryLevel level, Pageable pageable);

  /**
   * 有向量的那批 id 与向量（召回时做相似度排序用；用原生 SQL 读 pgvector）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（非会话层传 {@code null}）
   * @return 每行 {@code [id, embeddingText]}
   */
  @Query(
      value =
          "select id, embedding::text from cv_memory"
              + " where owner_id = :ownerId and level = :level"
              + " and (:scopeId is null or scope_id = :scopeId)"
              + " and embedding is not null"
              + " and deleted_at is null and superseded_by is null",
      nativeQuery = true)
  List<Object[]> findEmbeddings(
      @Param("ownerId") UUID ownerId,
      @Param("level") MemoryLevel level,
      @Param("scopeId") UUID scopeId);

  /**
   * 写向量（原生 SQL：{@code embedding} 没有 JPA 类型）。
   *
   * @param id 记忆 id
   * @param embeddingText {@code [0.1,0.2,...]} 形式
   * @return 影响行数
   */
  @org.springframework.data.jpa.repository.Modifying
  @Query(
      value = "update cv_memory set embedding = cast(:embedding as vector) where id = :id",
      nativeQuery = true)
  int updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingText);

  /**
   * 软删（原生 update：需要绕过 JPA 的自动 flush 语义并保持幂等）。
   *
   * @param id 记忆 id
   * @param now 当前时间
   * @return 影响行数
   */
  @org.springframework.data.jpa.repository.Modifying
  @Query(
      value =
          "update cv_memory set deleted_at = :now, updated_at = :now"
              + " where id = :id and deleted_at is null",
      nativeQuery = true)
  int softDelete(@Param("id") UUID id, @Param("now") Instant now);
}
