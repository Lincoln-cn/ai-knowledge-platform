package cn.lincoln.aiplatform.platform.infrastructure.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 留存期清理的调度外壳（`#75` 判据 ②）。
 *
 * <p>★ 只有 `app.audit.retention.enabled=true` 时这个配置类才生效，`@EnableScheduling` 也随之才开 ——
 * 默认情况下应用里<strong>没有任何调度线程</strong>。理由：一个"每天凌晨跑一次、结果不可逆"的作业， 不应该在没被要求开启的环境里悄悄存在。
 *
 * <p>★ 调度与执意分开（逻辑在 {@link AuditRetentionSweeper}）：这样判据能只验"算没算对、删没删对"， 不必等一个 cron 时间点，也不会让测试依赖时钟。
 */
@Configuration
@ConditionalOnProperty(name = "app.audit.retention.enabled", havingValue = "true")
@EnableScheduling
public class AuditRetentionSchedule {

  private static final Logger log = LoggerFactory.getLogger(AuditRetentionSchedule.class);

  private final AuditRetentionSweeper sweeper;

  public AuditRetentionSchedule(AuditRetentionSweeper sweeper) {
    this.sweeper = sweeper;
  }

  /** 默认每天 03:30 —— 放在备份窗口之后（`deploy/README.md` 第七节），避免与 `pg_dump` 抢 IO。 */
  @Scheduled(cron = "${app.audit.retention.cron:0 30 3 * * *}")
  public void runDaily() {
    log.debug("留存期清理开始（调度）");
    sweeper.sweep();
  }
}
