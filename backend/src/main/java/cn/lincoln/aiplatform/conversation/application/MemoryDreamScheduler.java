package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 做梦任务的运行期调度器（{@code 0.9.0 · #296} 的"将来"，{@code #6} 接上）。
 *
 * <h3>★ 它替掉了什么</h3>
 *
 * <p>原来 {@link MemoryDreamJob#dream()} 上挂的是 {@code @Scheduled(cron =
 * "${app.memory.dream-cron:…}")}： Spring 在<strong>注册 bean 时</strong>求值，之后只认部署配置。于是记忆页上的「做梦时间」改了库里的
 * {@code cv_memory_setting.dream_cron} 却没有任何东西会读它 —— 界面上如实写着"只记设定"。
 *
 * <p>现在改成：库里那一行是<strong>唯一权威</strong>，保存即重排（{@link MemorySettingsChanged} 事件， 事务提交之后才重排），进程重启后由
 * {@link ApplicationReadyEvent} 首次注册。部署配置 {@code app.memory.dream-cron} 不再参与 —— 它只作为"迁移 V101
 * 那一行的初值"存在。
 *
 * <h3>★ 为什么不自己写一个 {@code @Scheduled(fixedDelay = 60_000)} 的 tick</h3>
 *
 * <p>tick 方案要自己回答"这一分钟是不是该跑"：跨重启怎么算、错过一轮要不要补、改了 cron 之后今天还跑不跑。这些正是 {@link CronTrigger} 已经回答过的语义 ——
 * 和一个每 30 秒醒一次、 与库里时刻对表的循环相比，直接用 {@code CronTrigger} 既准确又不会在整点附近漏跑。
 *
 * <p>★ 同一个 cron 不重排：{@link ThreadPoolTaskScheduler#schedule} 的下一次触发是从"现在"算的，
 * 如果用户只是改了召回条数就重排，当天的做梦时刻会被悄悄推后。判据是 cron 字符串没变且旧计划还活着。
 */
@Component
public class MemoryDreamScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryDreamScheduler.class);

  private final MemoryDreamJob job;
  private final MemorySettingsStore settings;

  /** 单线程就够：做梦任务内部串行，且我们不想让两次做梦并发改同一批记忆。 */
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  /** {@code true} 表示 {@link #start()} 已经 initialize 过 —— 重复 initialize 会泄漏线程池。 */
  private boolean started;

  /** 当前已注册的 cron（{@code null} = 没有注册，例如总开关关着）。 */
  private String activeCron;

  /** 当前计划；{@link #reschedule()} 里整体替换，{@link #stop()} 里取消。 */
  private java.util.concurrent.ScheduledFuture<?> future;

  public MemoryDreamScheduler(MemoryDreamJob job, MemorySettingsStore settings) {
    this.job = job;
    this.settings = settings;
  }

  /**
   * 应用起来之后注册第一份计划。
   *
   * <p>★ 不用 {@code @PostConstruct}：那一刻 Flyway 与数据源就绪与否取决于启动顺序，而 {@link MemorySettingsStore#load()}
   * 在表缺失时会回落默认值 —— 那会把"表还没建好" 读成"设定就是默认值"。{@link ApplicationReadyEvent} 之后读库才是安全的。
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (started) {
      return;
    }
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("memory-dream-");
    /* 停机时等这一轮做完：做梦会写 cv_memory，半途被杀等于留下半套合并结果。 */
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(10);
    scheduler.initialize();
    started = true;
    reschedule();
  }

  /** 停机：先取消，再关线程池（顺序反了会让正在跑的那一轮看不到取消）。 */
  @PreDestroy
  public synchronized void stop() {
    cancel();
    if (started) {
      scheduler.shutdown();
    }
  }

  /**
   * 设定改完之后重排（事务提交后才执行 —— 事务里读到的 cron 可能是随后回滚的那一份）。
   *
   * @param event 新设定
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSettingsChanged(MemorySettingsChanged event) {
    reschedule();
  }

  /**
   * 按库里的设定重排。
   *
   * <p>★ 三种不注册的情形都只记日志、不抛：调度是<strong>增强</strong>，它坏掉不该让保存设定失败 （与 {@link MemorySettingsStore}
   * 读不到就回落默认值是同一条口径）。
   */
  public synchronized void reschedule() {
    if (!started) {
      LOG.debug("调度器还没 start（应用未就绪）—— 本次不注册记忆做梦任务");
      return;
    }
    MemorySettingsView current = settings.load();
    if (!current.enabled()) {
      cancel();
      LOG.info("记忆总开关是关的 ⇒ 不排做梦任务");
      return;
    }
    String cron = current.dreamCron().trim();
    if (cron.equals(activeCron) && future != null && !future.isCancelled()) {
      LOG.debug("做梦时间没变（{}）—— 不重排，避免把今天的执行点往后推", cron);
      return;
    }
    CronTrigger trigger;
    try {
      trigger = new CronTrigger(cron);
    } catch (IllegalArgumentException error) {
      /* 保存路径已经校验过；能走到这里的是手工改库。保留旧计划比"把记忆维护停掉"更安全。 */
      LOG.warn("库里的做梦时间 {} 解析失败（{}）—— 保留原计划", cron, error.getMessage());
      return;
    }
    cancel();
    future = scheduler.schedule(job::dream, trigger);
    activeCron = cron;
    LOG.info("记忆做梦任务注册完成：{}（按 {} 解释）", cron, ZoneId.systemDefault().getId());
  }

  /**
   * 下一次计划的触发时刻（给测试与排障用）。
   *
   * @return 没有注册（总开关关着 / cron 非法）时返回 {@code null}
   */
  public synchronized Instant nextRunAt() {
    if (!started || activeCron == null) {
      return null;
    }
    ZonedDateTime next = CronExpression.parse(activeCron).next(ZonedDateTime.now());
    return next == null ? null : next.toInstant();
  }

  /**
   * 当前已注册的 cron（{@code null} = 没有注册）。
   *
   * @return cron 字符串
   */
  synchronized String activeCron() {
    return activeCron;
  }

  private void cancel() {
    if (future != null) {
      future.cancel(false);
      future = null;
    }
    activeCron = null;
  }
}
