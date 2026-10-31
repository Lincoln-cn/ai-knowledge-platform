package cn.lincoln.aiplatform.platform.domain;

import java.util.Map;
import java.util.UUID;

/**
 * 审计留痕端口（`#257`）。设计口径见 {@code 13 §七}，表见 {@code V56__platform_audit_log.sql}。
 *
 * <p>★ <strong>写入与业务同事务</strong>：实现方直接 INSERT，不做 {@code REQUIRES_NEW}。
 * 理由：审计的价值就是"发生了就一定有记录"，若审计单独提交，业务成功 + 审计失败时 会出现一条**没有痕迹的变更**，事后既查不到也无法重放。代价是审计写不进去时业务跟着失败 ——
 * 这是刻意选择的严格方向（等保 2.0 的"可追溯"要求）。
 *
 * <p>★ <strong>不许吞异常</strong>：本端口不提供"尽力而为"的重载。要不要降级是业务方的决定， 但端口本身不给一个悄悄失败的口子 ——
 * 本工程的头号病就是静默失败（{@code silent-failure}）。
 */
public interface AuditLogPort {

  /**
   * 记一条审计。
   *
   * @param action 动作（闭集，见 {@link AuditAction}）
   * @param resourceType 资源类型，如 {@code DOCUMENT}
   * @param resourceId 资源主键（字符串形式，便于将来容纳非 UUID 键）
   * @param actorId 操作人；系统自动动作可传 {@code null}
   * @param detail 结构化补充（如密级变更的 {@code from}/{@code to}）；键名属契约，别随手改名
   */
  void record(
      AuditAction action,
      String resourceType,
      String resourceId,
      UUID actorId,
      Map<String, Object> detail);
}
