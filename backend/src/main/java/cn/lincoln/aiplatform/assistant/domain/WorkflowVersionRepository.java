package cn.lincoln.aiplatform.assistant.domain;

import java.util.UUID;

/**
 * 已发布版本的仓储（领域端口）—— 实现见 {@code assistant.infrastructure.WorkflowVersionRepositoryAdapter}。
 *
 * <p>★ 没有 {@code update} / {@code delete}：版本是<strong>只能追加</strong>的账。
 * 允许改一条已发布记录，等于允许悄悄改线上行为而不留新版本号。
 */
public interface WorkflowVersionRepository {

  /**
   * 下一个版本号 = {@code coalesce(max(version), 0) + 1}。
   *
   * <p>★ 它只是<strong>预期值</strong>，不是权威：两个请求同时取到 2 是可能的，正确的读数由 {@code unique (assistant_id, version)}
   * 在插入时给（违反者会抛出来，由调用方重试或报冲突）。
   */
  int nextVersion(UUID assistantId);

  /**
   * 追加一条版本并<strong>立即 flush</strong>。
   *
   * <p>★ 必须立即 flush：唯一约束冲突要在这次调用里暴露成 {@code DataIntegrityViolationException}，
   * 调用方才能"重试一次"。等到事务提交时才炸，调用点已经返回了成功读数。
   */
  WorkflowVersion append(WorkflowVersion version);
}
