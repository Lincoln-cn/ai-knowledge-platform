package cn.lincoln.aiplatform.conversation.domain.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 并发写者压力测试（GitHub issue #146）—— 传输层有<strong>两个写者</strong>：模型线程调 {@link StreamAssembler#delta}、心跳线程调
 * {@link StreamAssembler#heartbeat}，两者并发往 {@code emitted} 里 add。本测试钉住"监视器串行化之后事件一条不丢、顺序不乱"。
 *
 * <p>★ <strong>它必须能红</strong>：去掉 {@code synchronized} 后，非线程安全的 {@code ArrayList}
 * 会丢事件（或并发扩容抛异常），下面三条断言至少一条失败。A/B 验证见 issue #146 的评论。
 */
class StreamAssemblerConcurrencyTest {

  private static final int WRITERS = 6;
  private static final int DELTAS_PER_WRITER = 1000;
  private static final int HEARTBEATS = 1000;

  /**
   * 先 {@code begin()} ⇒ 流里还有一条 {@code meta}。
   *
   * <p>★ 与 issue #146 原文"1(refs) + 6000(delta) + 1000(heartbeat) + 1(done) = 7002"的差异在这里： {@code
   * begin()} 是被要求先调的，而它按契约产出 {@code meta}，所以总量是 7003。A/B 读数见 issue #146 评论。
   */
  private static final int META_EVENTS = 1;

  @Test
  void deltaWritersAndHeartbeatThreadKeepEveryEventInOrder() throws Exception {
    StreamAssembler assembler =
        new StreamAssembler("m-146", new StreamMeta("m-146", "trace-146", "model-146"));
    assembler.begin();
    assembler.references(List.of());

    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    List<Thread> threads = new ArrayList<>();
    for (int w = 0; w < WRITERS; w++) {
      int writer = w;
      threads.add(
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < DELTAS_PER_WRITER; i++) {
                    assembler.delta("w" + writer + "-" + i);
                  }
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              },
              "delta-writer-" + writer));
    }
    threads.add(
        new Thread(
            () -> {
              try {
                start.await();
                for (int i = 0; i < HEARTBEATS; i++) {
                  assembler.heartbeat();
                }
              } catch (Throwable t) {
                failure.compareAndSet(null, t);
              }
            },
            "heartbeat"));

    threads.forEach(Thread::start);
    start.countDown();
    for (Thread t : threads) {
      t.join(30_000);
    }
    for (Thread t : threads) {
      assertThat(t.isAlive())
          .as("写线程 %s 必须在超时前结束（join 超时会把'线程卡死'伪装成'事件丢失'）", t.getName())
          .isFalse();
    }
    assertThat(failure.get()).as("写线程不应抛异常").isNull();
    assembler.finish(StreamTerminationPath.COMPLETE, 1);

    List<StreamEvent> events = assembler.events();

    // meta + refs + 6000(delta) + 1000(heartbeat) + done
    assertThat(events)
        .as(
            "事件总数 = %d meta + 1 refs + %d delta + %d heartbeat + 1 done",
            META_EVENTS, WRITERS * DELTAS_PER_WRITER, HEARTBEATS)
        .hasSize(META_EVENTS + 1 + WRITERS * DELTAS_PER_WRITER + HEARTBEATS + 1);

    // 每个 writer 的 1000 个 delta 相对顺序严格递增（允许与其他 writer 交错）
    for (int w = 0; w < WRITERS; w++) {
      final String prefix = "w" + w + "-";
      List<Integer> seen = new ArrayList<>();
      for (StreamEvent e : events) {
        if (e instanceof StreamEvent.Delta d && d.text().startsWith(prefix)) {
          seen.add(Integer.parseInt(d.text().substring(prefix.length())));
        }
      }
      List<Integer> expected = new ArrayList<>();
      for (int i = 0; i < DELTAS_PER_WRITER; i++) {
        expected.add(i);
      }
      assertThat(seen)
          .as("writer %d 的 %d 个 delta 不许丢、不许乱序", w, DELTAS_PER_WRITER)
          .isEqualTo(expected);
    }

    // 所有 heartbeat 都在 done 之前
    int doneIndex = -1;
    long heartbeatsBeforeDone = 0;
    for (int i = 0; i < events.size(); i++) {
      StreamEvent e = events.get(i);
      if (e instanceof StreamEvent.Heartbeat) {
        assertThat(doneIndex).as("heartbeat 必须在 done 之前（下标 %d）", i).isEqualTo(-1);
        heartbeatsBeforeDone++;
      }
      if (e instanceof StreamEvent.StreamDoneEvent) {
        doneIndex = i;
      }
    }
    assertThat(doneIndex).as("done 必须存在").isNotEqualTo(-1);
    assertThat(heartbeatsBeforeDone).as("heartbeat 全部在 done 之前").isEqualTo(HEARTBEATS);
  }
}
