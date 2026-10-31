package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.Optional;
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

  /**
   * 该应用的<strong>全部</strong>已发布版本，按版本号<strong>降序</strong>（新的在前）。
   *
   * <p>★ 顺序放在仓储而不是调用方：调用方自己排序时，一旦哪天加了分页，就会变成"先截断再排序" （只排了第一页，第二页永远排在后面）。今天这张表一版一行、不截断，但顺序只该有一个真源。
   */
  List<WorkflowVersion> history(UUID assistantId);

  /**
   * 按<strong>主键</strong>取一条版本 —— 全局查，<strong>不</strong>按应用过滤。
   *
   * <p>★ 这是本端口上唯一一条能读到<em>别的应用</em>的行的路：调用方必须自己拿 {@link WorkflowVersion#assistantId()} 与应用 id
   * 比一次，比不过就当成"不存在"（404）。 拦截不放在这里，是因为"没有这一版"与"不是你的"要给同一句 404 文案，而那句话属于应用层。
   */
  Optional<WorkflowVersion> findById(UUID id);

  /**
   * 该应用里版本号为 {@code version} 的那一条。
   *
   * <p>★ 与 {@link #findById(UUID)} 相对：它<strong>在查询里就按应用收窄</strong>，别的应用的同号版本读不到。
   * 能写成"按应用收窄的查询"时就不要写成"先全局查、再自己校验"—— 后者多一个可能被忘掉的判定。
   */
  Optional<WorkflowVersion> findVersion(UUID assistantId, int version);
}
