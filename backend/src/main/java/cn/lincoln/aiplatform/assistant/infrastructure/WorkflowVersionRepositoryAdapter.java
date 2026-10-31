package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** 已发布版本的仓储实现（JPA）。 */
@Component
public class WorkflowVersionRepositoryAdapter implements WorkflowVersionRepository {

  private final EntityManager entityManager;
  private final JpaWorkflowVersionRepository versions;

  public WorkflowVersionRepositoryAdapter(
      EntityManager entityManager, JpaWorkflowVersionRepository versions) {
    this.entityManager = entityManager;
    this.versions = versions;
  }

  @Override
  public int nextVersion(UUID assistantId) {
    Integer next = versions.nextVersion(assistantId);
    // coalesce 保证非 null；这里兜成 1 只是为了让"取号失败"不变成一个 NPE（比 500 更难读）。
    return next == null ? 1 : next;
  }

  /**
   * ★ flush 必须在<strong>这次调用里</strong>发生，而且要把持久层异常翻译成 Spring 的 {@link
   * DataIntegrityViolationException}。
   *
   * <p>两个理由：① 调用方要靠"这一下抛了冲突"决定重试一次 —— 若异常拖到事务提交才出现，调用方已经拿着成功读数返回了； ② 本类是 {@code @Component} 而不是
   * {@code @Repository}，Hibernate 的 {@link PersistenceException} 不会被自动翻译，
   * 不在这里转一手，调用方就得依赖一个具体厂商的异常类型。
   */
  @Override
  public WorkflowVersion append(WorkflowVersion version) {
    entityManager.persist(version);
    try {
      entityManager.flush();
    } catch (PersistenceException e) {
      throw new DataIntegrityViolationException(
          "写 workflow 版本失败（很可能是唯一约束 uq_workflow_version 冲突）：assistant="
              + version.assistantId()
              + " version="
              + version.version(),
          e);
    }
    return version;
  }
}
