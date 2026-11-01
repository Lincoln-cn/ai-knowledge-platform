package cn.lincoln.aiplatform.platform.infrastructure.retention;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.AuditQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 审计的留存期清理（`#75` 判据 ② 的调度那一半）。
 *
 * <p>`#257`/`#75` 已经给了**手动**通道（`POST /api/v1/audit-logs/purge`，带 `dryRun`）。但"保留 12 个月"
 * 这句话如果只靠人记得去点一下，它就不是一条策略，而是一次性的动作。这个类把同一个动作放进调度里。
 *
 * <p>★ <strong>默认关闭</strong>（`app.audit.retention.enabled=false`）。理由不是"以后再说"：
 * 审计的删除是<strong>不可逆</strong>的，而"这个环境到底规定了多长留存期"是<strong>客户事实</strong>、 不是代码事实 —— 由代码替客户拍一个 365
 * 天然后开始删，比不删更危险。开关与天数都从配置来。
 *
 * <p>★ <strong>失败只记日志、不抛给调度器</strong>：Spring 的调度线程会吞掉异常并只打一条 WARN， 而这条 WARN
 * 与"本次没有过期数据"在运维眼里长得一样。这里显式 `log.error` 一条带天数的记录，
 * 让"清理没跑成"在日志里可检索；同时<strong>不减天数、不重试</strong>（重试一个连不上库的清理只会连坏更多次）。
 */
@Component
public class AuditRetentionSweeper {

  private static final Logger log = LoggerFactory.getLogger(AuditRetentionSweeper.class);

  /** 保留天数的下限：低于一天等于关掉，却看起来像开着。 */
  static final int MIN_RETAIN_DAYS = 1;

  private final boolean enabled;
  private final int retainDays;
  private final AuditQueryPort auditQuery;
  private final AuditLogPort auditLog;
  private final Clock clock;

  public AuditRetentionSweeper(
      @Value("${app.audit.retention.enabled:false}") boolean enabled,
      @Value("${app.audit.retention.retain-days:365}") int retainDays,
      AuditQueryPort auditQuery,
      AuditLogPort auditLog,
      Clock clock) {
    this.enabled = enabled;
    this.retainDays = Math.max(MIN_RETAIN_DAYS, retainDays);
    this.auditQuery = auditQuery;
    this.auditLog = auditLog;
    this.clock = clock;
    if (enabled && retainDays < MIN_RETAIN_DAYS) {
      log.warn(
          "app.audit.retention.retain-days={} 会被当成 {} 天 —— 配 0 或负数等于每次清理都删光，" + "而这与「保留期策略」是两件事",
          retainDays,
          MIN_RETAIN_DAYS);
    }
  }

  /**
   * 这次该清到哪个时间点。空 = 开关没开，本次不动。
   *
   * <p>★ 纯计算，不发命令 —— 判据能只验"算没算对"，不必起容器也不必等调度。
   */
  public Optional<Instant> cutoff() {
    if (!enabled) {
      return Optional.empty();
    }
    return Optional.of(clock.instant().minus(Duration.ofDays(retainDays)));
  }

  /**
   * 执行一次清理，返回真正删掉的行数（0 = 没开、或没有过期数据）。
   *
   * <p>调用方是调度器；手动通道走 `AuditLogController#purge`（那里带 `dryRun`，能先数后删）。
   */
  public long sweep() {
    Optional<Instant> maybeCutoff = cutoff();
    if (maybeCutoff.isEmpty()) {
      log.debug("留存期清理未开启（app.audit.retention.enabled=false），本次跳过");
      return 0;
    }
    Instant cutoff = maybeCutoff.get();
    try {
      long removed = auditQuery.purgeOlderThan(cutoff);
      if (removed > 0) {
        // ★ 清理自己也要留痕：与手动通道同一个动作码、同一张表；
        //   多一个 trigger 字段，报表里能分开"人点的"与"调度删的"。
        auditLog.record(
            AuditAction.AUDIT_PURGE,
            "AUDIT_LOG",
            "pf_audit_log",
            null,
            Map.of("beforeAt", cutoff.toString(), "deleted", removed, "trigger", "retention"));
        log.info("留存期清理：删除 {} 条早于 {} 的审计记录（保留 {} 天）", removed, cutoff, retainDays);
      }
      return removed;
    } catch (RuntimeException failure) {
      log.error(
          "留存期清理失败：保留 {} 天、截止 {} —— 这次一条都没删，下次调度会再试；" + "若持续失败请先查库连通性，不要调小 retain-days 来「绕过」",
          retainDays,
          cutoff,
          failure);
      return 0;
    }
  }
}
