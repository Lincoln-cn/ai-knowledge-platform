package cn.lincoln.aiplatform.knowledge.application;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 向量化的并发闸门（{@code #227}）—— 把「一次触发 70 份」摊平成「同时最多 N 份」。
 *
 * <h3>★ 为什么需要它（实测证据，不是推断）</h3>
 *
 * <p>2026-10-28 本地真栈，一次触发 69 份文档的向量化（{@code corpus/synthetic} 70 份）：
 *
 * <ul>
 *   <li>57 SUCCEEDED / 12 EMBEDDING_FAILED / 1 触发时 500；
 *   <li>其中 10 份的失败原因是 {@code Failed to obtain JDBC Connection}；
 *   <li>分三批（每批 ≤3 份）重试后 12/12 成功，最终 70/70 SUCCEEDED。
 * </ul>
 *
 * <p>根因是两件事相乘：{@link EmbeddingRunner} 用 {@code Executors.newVirtualThreadPerTaskExecutor()}
 * 提交任务（<b>没有并发上限</b>），而数据源用 Hikari 的默认池（{@code maximumPoolSize=10}）。于是「触发 69 份」= 69 个虚拟线程同时抢 10
 * 条连接：每个任务要读分段（一条 pending 查询）、每 20 段批量写向量、再回写进度，连接需求远超池容量， 部分线程等不到连接就抛 {@code
 * CannotGetJdbcConnectionException}。
 *
 * <p>★ 这个失败的形状特别坏：<b>与文档内容无关</b>，同一批文件同样顺序重试就过 ⇒ 用户会以为是 「文件坏了」。所以它不能靠"多试几次"掩盖，必须把并发**限制在池容量以内**。
 *
 * <h3>★ 为什么闸门在任务体里获取、而不是在 {@code submit} 之前</h3>
 *
 * <p>{@code startAsync} 是从 HTTP 请求线程里调的（用户点「开始向量化」）。如果在提交前 {@code acquire}，第 5
 * 个请求会被**卡在用户线程上**等前面几份跑完 —— 那是把后台任务做成了前台排队， 页面会转圈到超时。所以闸门放在任务体内：请求立刻返回，任务在虚拟线程里排队（虚拟线程排队不占平台线程）。
 *
 * <h3>★ 容量取值</h3>
 *
 * <p>默认 4：Hikari 默认池是 10，而一个正在跑的任务同时占用 1 条连接做 pending 查询/批写、外加 进度回写；4
 * 份并发给池留了余量，也让流式对话（SSE，同样吃连接）不至于被入库饿死。可用 {@code app.ingestion.embedding-concurrency}（或环境变量 {@code
 * APP_INGESTION_EMBEDDING_CONCURRENCY}）调整。
 */
@Component
public class EmbeddingConcurrencyGate {

  /** 允许同时在跑的向量化任务数。 */
  private final int capacity;

  /** 公平锁：先提交的先跑 —— 否则一次 70 份的批里，最后提交的那份可能饿到最后。 */
  private final Semaphore permits;

  public EmbeddingConcurrencyGate(
      @Value("${app.ingestion.embedding-concurrency:4}") int concurrency) {
    // ★ 下限 1：配成 0 或负数会让向量化永远不跑（而且表现为「触发了但没有任何进展」这样的静默失败）
    this.capacity = Math.max(1, concurrency);
    this.permits = new Semaphore(this.capacity, true);
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
   * <p>★ 归还写在 {@code finally} 里不是礼貌问题：漏归还一次，容量就永久少一格，几十次之后闸门 变成 0 容量 —— 表现是「向量化点了没反应」，而日志里什么错都没有。
   *
   * @param task 任务
   */
  public void run(Runnable task) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("向量化排队被打断（应用正在关闭？）", e);
    }
    try {
      task.run();
    } finally {
      permits.release();
    }
  }
}
