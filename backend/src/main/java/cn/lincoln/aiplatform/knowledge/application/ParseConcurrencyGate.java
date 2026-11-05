package cn.lincoln.aiplatform.knowledge.application;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 解析/分段的并发闸门（{@code #303}）—— 把「一次上传 20 份」摊平成「同时最多 N 份在解析」。
 *
 * <h3>★ 为什么需要它（{@code #303} 实测证据）</h3>
 *
 * <p>{@link ParseRunner} 每份一个虚拟线程、<b>没有并发上限</b>（与 {@code #227} 的向量化同病）。池压到 2、20 份并发上传时先炸的是解析：4 份
 * SEGMENT_FAILED、8 份卡在 SEGMENTING 十几分钟不再动（{@code updated_at} 全停、{@code pg_stat_activity} 里 {@code
 * active=0} —— 没有线程在跑，是真卡死）、1 份上传直接 500。{@link EmbeddingConcurrencyGate} 只管向量化那一段，解析路径完全裸奔。
 *
 * <h3>★ 容量取值：默认 {@code min(逻辑核数, 池上限 / 2)}（配置 0 = auto）</h3>
 *
 * <p><b>不抄向量化的 4</b> —— 两者的资源瓶颈根本不是同一种：
 *
 * <ol>
 *   <li><b>解析/分段是 CPU 密集</b>：Tika 解析 + Tesseract OCR 全是纯计算（实测 3 页扫描件全量 OCR 约 11 秒，每页约 3.6 秒，见
 *       {@link ParseRunner} 类注释）。并发超过逻辑核数不会增加吞吐，只有上下文抢占 —— 每份都变慢、总时长反而变长。⇒ 上限取 <b>逻辑核数</b>。
 *   <li><b>向量化是 IO 密集</b>：瓶颈在上游 HTTP 往返 + 批写，CPU 基本空闲，所以它可以高于核数、也可以低于核数（{@code #227} 取 4）。
 *   <li><b>两段都会周期性占连接</b>：每份解析任务要拿 3~4 次连接（{@code progress.start}、逐页 {@code reportOcrPage}
 *       回写、{@code finishParse}/{@code failParse} 收尾），全是短事务但全程并发 ⇒ 并发数必须给池留余量，沿 {@code #227} 的容量口径取
 *       <b>≤ 池上限的一半</b>（另一半留给 SSE 对话、检索等交互请求）。解析与向量化共用同一套"池上限推容量"的口径，启动自检在 {@code
 *       StartupSettingsCheck} 同一处校验两者。
 * </ol>
 *
 * <p>⇒ auto = {@code min(逻辑核数, max(1, 池上限 / 2))}。配 0 或负数走 auto；显式配置大于 0 时照声明值用（越界由启动自检 WARN，不静默改值）。
 *
 * <h3>★ 闸门在任务体里获取、而不是在 {@code submit} 之前</h3>
 *
 * <p>同 {@link EmbeddingConcurrencyGate}：{@code onUploaded} 是从 HTTP 请求线程调的（上传接口），提交前 {@code acquire}
 * 会把第 N+1 个上传请求卡在用户线程上。放任务体内：上传立刻返回，任务在虚拟线程里排队（不占平台线程）。
 */
@Component
public class ParseConcurrencyGate {

  /** 允许同时在跑的解析/分段任务数。 */
  private final int capacity;

  /** 公平锁：先提交的先跑 —— 否则一次 20 份的批里，最后提交的那份可能饿到最后。 */
  private final Semaphore permits;

  public ParseConcurrencyGate(
      @Value("${app.ingestion.parse-concurrency:0}") int concurrency,
      @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
    this.capacity =
        resolveCapacity(concurrency, poolSize, Runtime.getRuntime().availableProcessors());
    this.permits = new Semaphore(this.capacity, true);
  }

  /**
   * 容量推导（纯函数，测试直接验）。
   *
   * @param configured 配置值；0 或负数 = auto
   * @param poolSize 数据库连接池上限
   * @param availableProcessors 逻辑核数
   * @return 实际容量（下限 1）
   */
  static int resolveCapacity(int configured, int poolSize, int availableProcessors) {
    if (configured > 0) {
      return configured;
    }
    return Math.max(1, Math.min(availableProcessors, Math.max(1, poolSize / 2)));
  }

  /** 容量（日志与测试读数用）。 */
  public int capacity() {
    return capacity;
  }

  /** 当前在跑的个数。★ 没有它就没法验证「闸门真的限住了」。 */
  public int inFlight() {
    return capacity - permits.availablePermits();
  }

  /**
   * 在闸门内跑一段任务：**拿到许可才跑，跑完（含抛异常）一定归还**。
   *
   * <p>★ 归还写在 {@code finally} 里不是礼貌问题：漏归还一次，容量就永久少一格，几十次之后闸门 变成 0 容量 —— 表现是「上传后永远停在待解析」，而日志里什么错都没有。
   *
   * @param task 任务
   */
  public void run(Runnable task) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("解析排队被打断（应用正在关闭？）", e);
    }
    try {
      task.run();
    } finally {
      permits.release();
    }
  }
}
