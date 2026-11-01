package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EmbeddingConcurrencyGate} 的断言 —— {@code #227} 判据 ③「闸门与无限并发的区分点」。
 *
 * <p>★ 这里测的是**闸门本身**（峰值并发不超容量、异常也归还许可、容量下限 1）。它不需要 Testcontainers、不需要上游模型 —— 那两条要真栈的判据（70
 * 份一次不重试全成功、活跃连接数不超池 上限）留给真栈长跑（{@code #223} 判据 4）。
 */
class EmbeddingConcurrencyGateTest {

  @Test
  @DisplayName("★ 容量 2 时 8 个任务的峰值并发不超过 2 —— 这就是「变慢而不是失败」的上半句")
  void peakConcurrencyNeverExceedsCapacity() throws Exception {
    EmbeddingConcurrencyGate gate = new EmbeddingConcurrencyGate(2);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    int tasks = 8;

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch done = new CountDownLatch(tasks);
      for (int i = 0; i < tasks; i++) {
        pool.submit(
            () ->
                gate.run(
                    () -> {
                      int now = running.incrementAndGet();
                      peak.accumulateAndGet(now, Math::max);
                      try {
                        Thread.sleep(20);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      } finally {
                        running.decrementAndGet();
                        done.countDown();
                      }
                    }));
      }
      assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
    }

    // 没有闸门时这个值会是 8（虚拟线程一个都不会等）—— 所以这条断言真的能红
    assertThat(peak.get()).isLessThanOrEqualTo(2);
    assertThat(gate.inFlight()).isZero();
  }

  @Test
  @DisplayName("★ 任务抛异常也要归还许可 —— 漏一次就永久少一格，几十次后闸门变 0 容量")
  void permitIsReleasedEvenWhenTaskThrows() {
    EmbeddingConcurrencyGate gate = new EmbeddingConcurrencyGate(1);

    for (int i = 0; i < 5; i++) {
      try {
        gate.run(
            () -> {
              throw new IllegalStateException("这一份炸了");
            });
      } catch (IllegalStateException expected) {
        // 每个任务都该炸一次；炸了之后下一个必须还能进（容量 1 也不会被卡死）
      }
    }

    assertThat(gate.inFlight()).isZero();
    assertThat(gate.capacity()).isEqualTo(1);
  }

  @Test
  @DisplayName("★ 配成 0 / 负数时按 1 处理 —— 否则表现是「点了没反应」，日志里什么错都没有")
  void capacityHasALowerBoundOfOne() {
    assertThat(new EmbeddingConcurrencyGate(0).capacity()).isEqualTo(1);
    assertThat(new EmbeddingConcurrencyGate(-5).capacity()).isEqualTo(1);
  }

  @Test
  @DisplayName("公平锁：先提交的先跑 —— 一次 70 份的批里，最后提交的那份不许饿到最后")
  void permitsAreGrantedInSubmissionOrder() throws Exception {
    EmbeddingConcurrencyGate gate = new EmbeddingConcurrencyGate(1);
    List<Integer> order = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch first = new CountDownLatch(1);
      // 先占住唯一的那格
      pool.submit(
          () ->
              gate.run(
                  () -> {
                    try {
                      first.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      Thread.sleep(50); // 让上面那个真的拿到许可

      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        int index = i;
        futures.add(pool.submit(() -> gate.run(() -> order.add(index))));
        Thread.sleep(10); // 保证提交顺序确定（虚拟线程提交是异步的）
      }
      first.countDown();
      for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    assertThat(order).containsExactly(0, 1, 2, 3, 4);
  }
}
