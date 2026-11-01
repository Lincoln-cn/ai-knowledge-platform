package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写一个已发布版本（事务边界在这里，{@link WorkflowPublishService} 只管判据与重试）。
 *
 * <p>★ 为什么必须单独一个 bean：并发冲突的"重试一次"要<strong>跨事务</strong>。 唯一约束冲突发生时，当前事务已被标记为 rollback-only，同一个事务里再取号
 * + 插入只会得到 {@code UnexpectedRollbackException}； 只有让这次调用<strong>整个回滚</strong>、 由外层重新进入一个新事务，重试才有意义。
 *
 * <p>★ 版本行与审计<strong>同一个事务</strong>（{@link AuditLogPort} 的口径）：审计写不进去 ⇒ 版本也不该落库。
 * 反过来说，"发布成功但审计里查不到"这种状态在本实现里不可能出现。
 */
@Service
public class WorkflowVersionStore {

  private final WorkflowVersionRepository versions;
  private final AuditLogPort audit;

  public WorkflowVersionStore(WorkflowVersionRepository versions, AuditLogPort audit) {
    this.versions = versions;
    this.audit = audit;
  }

  /**
   * 取号、插入、记审计，三件事一次事务。
   *
   * @param note 发布说明；{@code null} 归一成空串（建表默认值是空串，不是 null）
   * @throws org.springframework.dao.DataIntegrityViolationException 版本号被并发请求先占了
   */
  @Transactional
  public WorkflowVersion append(
      UUID assistantId, String graph, String note, UUID actorId, int nodeCount) {
    int version = versions.nextVersion(assistantId);
    WorkflowVersion row =
        new WorkflowVersion(
            UUID.randomUUID(),
            assistantId,
            version,
            graph,
            note == null ? "" : note,
            actorId,
            Instant.now());
    versions.append(row);
    // ★ 图原文不进明细：它已经在 graph 列里有一份，抄第二份只会让两处不一致时有得选。
    audit.record(
        AuditAction.WORKFLOW_PUBLISH,
        "ASSISTANT",
        assistantId.toString(),
        actorId,
        Map.of("version", version, "nodeCount", nodeCount));
    return row;
  }
}
