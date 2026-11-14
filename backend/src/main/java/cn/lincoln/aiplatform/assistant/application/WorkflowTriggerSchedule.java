package cn.lincoln.aiplatform.assistant.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时触发的调度壳（{@code #295} / 0.7.3）。
 *
 * <p>★ 与 {@code DeletedDocumentCleanupSchedule} / {@code StuckPendingParseReaper} 同一个模式：
 * {@code @Configuration} + {@code @EnableScheduling} + 一个 {@code @Scheduled} 方法，真正的工作在 {@link
 * WorkflowTriggerSweeper} 里（这样它可以被测试直接调用，不必等定时器）。
 *
 * <p>★ <strong>默认开</strong>（{@code matchIfMissing = true}），与"回收站清理默认关"相反 —— 区别在
 * 这两件事的语义：清理是"装了就开始删数据"，而触发器是"用户明确配了 cron 才有事可做"， 没配的应用一个都不会被碰。关掉它的场景是多副本部署时只想让一个实例扫（但即便都开着也安全：
 * 抢占是乐观锁，{@code trigger_next_at} 相等的 UPDATE 只有一个能成功）。
 *
 * <p>★ 用 {@code fixedDelay} 而不是 {@code cron}：扫描频率与"用户配的 cron"是两件事。固定延迟保证
 * **上一轮跑完才开始计时**，不会因为某一轮遇到慢图而在队列里堆任务。
 */
@Configuration
@ConditionalOnProperty(name = "app.trigger.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class WorkflowTriggerSchedule {

  private final WorkflowTriggerSweeper sweeper;

  public WorkflowTriggerSchedule(WorkflowTriggerSweeper sweeper) {
    this.sweeper = sweeper;
  }

  /** 默认每 10 秒扫一次：这是"发现到期"的精度上限，不是 cron 的最小粒度（cron 最小到分钟）。 */
  @Scheduled(
      fixedDelayString = "${app.trigger.scan-ms:10000}",
      initialDelayString = "${app.trigger.first-scan-ms:10000}")
  public void run() {
    sweeper.sweep();
  }
}
