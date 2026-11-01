package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 版本的 Spring Data 仓储（只给适配器用）。
 *
 * <p>★ 与 {@code JpaAssistantRepository} 同一条口径：它是<strong>基础设施细节</strong>，不实现领域接口， 也不被领域看见 —— 领域端口在
 * {@code assistant.domain.WorkflowVersionRepository}。
 */
interface JpaWorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

  /**
   * 同一个应用内的下一个版本号。
   *
   * <p>★ 用 {@code coalesce(..., 0) + 1} 而不是 {@code count(*) +
   * 1}：版本号是<strong>单调递增的号</strong>，不是"第几条" —— 中间删过（本表不删，但别的路径可能会）时 count 会给出已占用的号。 与 {@code V61}
   * 的建表注释同一口径。
   */
  @Query(
      value =
          "select coalesce(max(version), 0) + 1 from as_workflow_version where assistant_id ="
              + " :assistantId",
      nativeQuery = true)
  Integer nextVersion(@Param("assistantId") UUID assistantId);

  /**
   * 该应用的历史版本，新的在前。
   *
   * <p>★ 方法名里的 {@code OrderByVersionDesc} 就是排序的真源（Spring Data 由名字生成排序） —— 不在调用方排， 也不在 SQL
   * 里手写，两处都写就等于两处都能被改歪。
   */
  List<WorkflowVersion> findByAssistantIdOrderByVersionDesc(UUID assistantId);

  /** 该应用里某个版本号的那一行（版本号只在应用内唯一，所以必须带上 {@code assistantId}）。 */
  Optional<WorkflowVersion> findByAssistantIdAndVersion(UUID assistantId, int version);
}
