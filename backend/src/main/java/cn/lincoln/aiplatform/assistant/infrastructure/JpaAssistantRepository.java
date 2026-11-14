package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的应用仓储。
 *
 * <p>★ 纯 Spring Data 接口，<strong>不同时继承</strong>领域的 {@code AssistantRepository} —— 双继承会让 {@code
 * Pageable} / {@code Specification} 漏进领域层（与 {@code JpaKnowledgeBaseRepository} / {@code
 * JpaModelRepository} 同一条判断）。
 *
 * <p>列表查询将用 {@link JpaSpecificationExecutor}（范围谓词与筛选合进同一条 SQL，{@code 08 §4.4.3} 纪律 1）—— 本批先只用到
 * {@code findById} / {@code save} 与一次条件查询（幂等键）。
 */
public interface JpaAssistantRepository
    extends JpaRepository<Assistant, UUID>, JpaSpecificationExecutor<Assistant> {

  /**
   * 到期的定时触发（{@code #295}）。
   *
   * <p>★ 谓词写成 {@code triggerNextAt is not null and <= :now}：完整匹配 {@code V99} 的部分索引 {@code
   * idx_as_assistant_trigger_due}，否则"绝大多数应用没开触发器"时这次扫描照样要读全表。
   */
  @Query(
      """
      select a from Assistant a
      where a.triggerNextAt is not null
        and a.triggerNextAt <= :now
        and a.deletedAt is null
      order by a.triggerNextAt asc
      """)
  List<Assistant> findDueTriggers(@Param("now") Instant now, Pageable pageable);

  /**
   * 抢占一次触发：只有 {@code trigger_next_at} 仍是刚才读到的那个值时才改得动。
   *
   * <p>★ {@code clearAutomatically = true}：批量 update 绕过持久化上下文，不清的话同一事务里
   * 再读这些实体拿到的还是旧值（"明明已经顺延了，下一轮又看到它到期"）。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update Assistant a
      set a.triggerNextAt = :nextAt,
          a.triggerLastAt = :firedAt,
          a.triggerMissed = :missed
      where a.id = :id
        and a.triggerNextAt = :expected
      """)
  int claimTrigger(
      @Param("id") UUID id,
      @Param("expected") Instant expected,
      @Param("firedAt") Instant firedAt,
      @Param("nextAt") Instant nextAt,
      @Param("missed") int missed);
}
