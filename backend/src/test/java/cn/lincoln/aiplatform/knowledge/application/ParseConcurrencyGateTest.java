package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ParseConcurrencyGate} 的边界（{@code #303}）。
 *
 * <p>★ 与 {@code EmbeddingConcurrencyGateTest} 同形：峰值并发 ≤ 容量、异常也归还、容量下限 1 —— 但容量<b>取值</b>不同：解析是 CPU
 * 密集，auto = {@code min(逻辑核数, max(1, 池上限/2))}（不抄向量化的 4），这条由 {@code resolveCapacity} 的用例钉住。
 */
class ParseConcurrencyGateTest {

  @Test
  @DisplayName("★ auto 容量 = min(逻辑核数, max(1, 池上限/2)) —— 不许抄向量化的固定 4")
  void autoCapacityDerivation() {
    // 核数是瓶颈：8 核池 10 ⇒ min(8, 5) = 5
    assertThat(ParseConcurrencyGate.resolveCapacity(0, 10, 8)).isEqualTo(5);
    // 池是瓶颈：8 核池 2 ⇒ min(8, 1) = 1（#303 现场形状：池 2 时解析最多 1 份并发）
    assertThat(ParseConcurrencyGate.resolveCapacity(0, 2, 8)).isEqualTo(1);
    // 核数是瓶颈（小机器）：2 核池 10 ⇒ min(2, 5) = 2
    assertThat(ParseConcurrencyGate.resolveCapacity(0, 10, 2)).isEqualTo(2);
    // ★ 下限 1：池 1 ⇒ max(1, 0) = 1 —— 0 容量等于「什么都不许解析」，那比卡住更坏
    assertThat(ParseConcurrencyGate.resolveCapacity(0, 1, 16)).isEqualTo(1);
  }

  @Test
  @DisplayName("显式配置照声明值用（越界由启动自检 WARN，闸门不静默改值）")
  void explicitConfigurationIsUsedAsIs() {
    assertThat(ParseConcurrencyGate.resolveCapacity(3, 10, 64)).isEqualTo(3);
  }

  @Test
  @DisplayName("★ 峰值并发不超过容量 —— 闸门真的限住了")
  void peakConcurrencyNeverExceedsCapacity() throws Exception {
    ParseConcurrencyGate gate = new ParseConcurrencyGate(2, 10);
    int tasks = 8;
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    CountDownLatch done = new CountDownLatch(tasks);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < tasks; i++) {
      Thread t =
          new Thread(
              () -> {
                gate.run(
                    () -> {
                      int now = running.incrementAndGet();
                      peak.accumulateAndGet(now, Math::max);
                      try {
                        Thread.sleep(30);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      } finally {
                        running.decrementAndGet();
                      }
                    });
                done.countDown();
              });
      threads.add(t);
      t.start();
    }
    done.await();
    assertThat(peak.get()).as("8 个任务挤进容量 2 的闸门，同时在跑的不得超过 2").isLessThanOrEqualTo(2);
    assertThat(gate.inFlight()).as("全部跑完后必须一颗都不剩（漏归还 = 容量永久少一格）").isZero();
  }

  @Test
  @DisplayName("★ 抛异常也归还 —— 归还写在 finally 里，否则几十次后闸门变成 0 容量")
  void exceptionStillReleasesPermit() {
    ParseConcurrencyGate gate = new ParseConcurrencyGate(2, 10);
    assertThatThrownBy(
            () ->
                gate.run(
                    () -> {
                      throw new IllegalStateException("解析炸了");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(gate.inFlight()).as("异常路径归还许可").isZero();
  }
}
