package cn.lincoln.aiplatform.knowledge.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 回收站超期清理的调度壳（`#259`）。
 *
 * <p>★ {@code @ConditionalOnProperty} 而不是在作业里判开关：默认环境下应用**不会有调度线程** ——
 * "装好就有个定时任务在删数据"是这类功能最不该有的默认值。
 *
 * <p>★ 与 {@code AuditRetentionSchedule} 同一个模式（默认关、cron 可配、开关只管装配）。
 */
@Configuration
@ConditionalOnProperty(name = "app.knowledge.cleanup.enabled", havingValue = "true")
@EnableScheduling
public class DeletedDocumentCleanupSchedule {

  private final DeletedDocumentSweeper sweeper;

  public DeletedDocumentCleanupSchedule(DeletedDocumentSweeper sweeper) {
    this.sweeper = sweeper;
  }

  /** 默认每天 04:10（审计留存清理是 03:30 —— 错开一小时，别同时压库）。 */
  @Scheduled(cron = "${app.knowledge.cleanup.cron:0 10 4 * * *}")
  public void run() {
    sweeper.sweep();
  }
}
