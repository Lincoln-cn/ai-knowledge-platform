package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.EventKind;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamStatus;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code ChatService} 层的<strong>事件读数</strong>：四条终止路径各自的事件序列、终态与落库终态 （第 17 周 · 0.4.5）。
 *
 * <h3>★★ 为什么这一层必须单独读一遍（HTTP 层读不到）</h3>
 *
 * <p>{@code ChatStreamHttpTest} 读的是"前端真的收到了什么" —— 而 {@code DISCONNECT} 那条路径上
 * <strong>客户端收不到任何东西</strong>：连接写不出去了，{@code error} 与 {@code done} 两帧根本到不了 wire。
 * 于是"两帧的顺序与各恰好一次"这件事，只有<strong>接管事件出口</strong>才读得到。
 *
 * <p>★ 那也是 {@link ChatTurnStore} 这个窄端口存在的理由：本类<strong>不需要数据库</strong> ——
 * 落库侧换成一份记录，而编排（三条线程、时钟、装配器、四路径判定）全都是真的那一份。
 *
 * <h3>★★ 两类"注入"分别让什么变得可证伪</h3>
 *
 * <table>
 *   <tr><th>注入</th><th>证伪的命题</th></tr>
 *   <tr><td>事件出口在写完前 N 帧后抛 {@link java.io.UncheckedIOException}</td>
 *       <td>"写失败 ⇒ 落库 {@code FAILED} 而不是 {@code GENERATED}"，且 {@code error → done} 的顺序与次数都对</td></tr>
 *   <tr><td>事件出口在收到终态那一帧时<strong>再走一次收尾</strong>（{@link TerminalGate}）</td>
 *       <td>"终态只取一次"（{@code ChatService.Run#terminalTaken}）—— ★ 去掉那道守卫，本类那条用例判红</td></tr>
 * </table>
 *
 * <p>★ 第二条为什么不是"为测试造的假场景"：终态竞争是<strong>真实存在</strong>的（心跳线程判超时与模型线程跑完可能同时到达）， 而"在收终态帧的过程中被重入"同样是真实的
 * —— 收尾的 {@code try} 里还要写帧，写帧经过调用方给出的出口，
 * 出口的失败处理里也可能有收尾动作。这道守卫防的正是"第二个收尾入口被调用"，把那一刻搬进测试是让它今天就可证伪的唯一办法。
 *
 * <h3>★ 时间被压小，契约默认值一个字没改</h3>
 *
 * <p>心跳 300ms / 闲置超时 1200ms（仍满足 {@code 心跳 × 3 ≤ 超时}），与 {@code ChatStreamHttpTest}
 * 用同一组数，便于两处读数互相印证；契约那两个数由 {@code ChatStreamTimingTest} 单独断言。
 */
class ChatStreamEventRecordingTest {

  /** 与同一条链路上 HTTP 层那份读数一致的时间压缩（契约值由 {@code ChatStreamTimingTest} 钉住）。 */
  private static final long HEARTBEAT_INTERVAL_MS = 300L;

  private static final long IDLE_TIMEOUT_MS = 1200L;

  private static final ChatStreamProperties PROPERTIES =
      new ChatStreamProperties(HEARTBEAT_INTERVAL_MS, IDLE_TIMEOUT_MS, 60L, 200);

  private static final String QUESTION = "差旅住宿标准";

  private static final String SHORT_ANSWER = "差旅住宿标准为每人每天500元。";

  // ────────────────────────── ① 断开路径（写失败）──────────────────────────

  /**
   * ★★ 写失败 ⇒ {@code DISCONNECT}：{@code error} 在前、{@code done} 在后，各恰好一条，落库终态 {@code FAILED}。
   *
   * <p>造法：事件出口在收满 1 帧之后抛一次 {@link java.io.UncheckedIOException}（= 写连接失败， {@code DISCONNECT}
   * 唯一的观测方式）。此时上游还没吐完，所以"已生成的那一段"必须保留。
   *
   * <p>★ 本条可证伪：把注入去掉（出口照收不误），路径会落在 {@code COMPLETE}、落库变成 {@code GENERATED} —— 本批实测做过这一次反证。
   */
  @Test
  void aFailedWriteEndsTheTurnWithErrorThenDoneAndPersistsFailed() {
    Fixture fixture = new Fixture();
    fixture.model.set(new ScriptedModel(0L, 2L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();
    // ★ 注入点：写失败（"客户端那条连接写不出去了"）
    sink.failAfterFrames = 1;

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.writeFailed).as("★★ 前置：注入的那一次写失败真的发生了（否则下面每一条断言都在验一条没被触发的路径）").isTrue();
    assertThat(sink.kinds())
        .as("★★ 断开路径的帧顺序：error 收原因、done 收终态（契约原文：error 不是终态，done 才是）")
        .containsSubsequence(EventKind.ERROR, EventKind.DONE);
    assertThat(sink.kinds().stream().filter(kind -> kind == EventKind.ERROR).count())
        .as("★ error 恰好一条")
        .isEqualTo(1L);
    assertThat(sink.kinds().stream().filter(kind -> kind == EventKind.DONE).count())
        .as("★ done 恰好一条")
        .isEqualTo(1L);
    assertThat(sink.done().status())
        .as("★ 断开 ⇒ done(FAILED)（与 ABORT 的 STOPPED 是两条路径、两个终态）")
        .isEqualTo(StreamStatus.FAILED);
    assertThat(sink.error().reason())
        .as("★ 写失败这一路没有上游 detail ⇒ 原因取「连接中断」那一条（不是空、也不是把异常消息直接抛给用户）")
        .isEqualTo("连接中断");
    assertThat(sink.error().hint()).as("★ 失败要让用户知道下一步（契约的 error.hint）").isEqualTo("网络或服务异常，可重试");

    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted).as("★ 收尾必须落库（否则库里那条停在 GENERATING，界面永远转圈）").isNotNull();
    assertThat(persisted.path())
        .as("★★ 路径判定：写失败 + 上游未报错 ⇒ DISCONNECT（不是 COMPLETE）")
        .isEqualTo(StreamTerminationPath.DISCONNECT);
    assertThat(persisted.failReason())
        .as("★ 库里记的原因与 error 帧同源（两处各编一句就会分叉，而那不会报错）")
        .isEqualTo("连接中断");
    assertThat(persisted.failHint()).isEqualTo("网络或服务异常，可重试");
    assertThat(persisted.text()).as("★ 断开不是清空：已经写出去的那一段仍在库里").isNotEmpty();
    assertThat(persisted.text()).isEqualTo(sink.textSeen());
  }

  /**
   * ★ 上游报错 ⇒ 也是 {@code DISCONNECT}，但原因是<strong>上游给的那一句</strong>（同一终态、两个来源、两种文案）。
   *
   * <p>★ 与上面那条写失败用例成对：它们合起来证明 {@code failReasonFor} 的两个分支都被走过了 （{@code detail == null} ⇒ "连接中断"；否则
   * ⇒ "生成失败：" + detail）。
   */
  @Test
  void anUpstreamFailureKeepsItsOwnDetailInThePersistedReason() {
    Fixture fixture = new Fixture();
    fixture.model.set(
        (modelId, prompt, timeout, callback) ->
            callback.onEnd(ChatStreamPort.End.FAILED, "上游返回 502"));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.kinds())
        .as("★ 上游失败同样先 error 再 done")
        .containsSubsequence(EventKind.ERROR, EventKind.DONE);
    assertThat(sink.kinds().stream().filter(kind -> kind == EventKind.DONE).count()).isEqualTo(1L);
    assertThat(sink.done().status()).isEqualTo(StreamStatus.FAILED);

    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted.path())
        .as("★ 上游报错与「连接断了」同族（都是 FAILED），而路径判定确实落在 DISCONNECT 上")
        .isEqualTo(StreamTerminationPath.DISCONNECT);
    assertThat(persisted.failReason())
        .as("★★ 有上游 detail 时不许把它丢掉（丢掉的话用户只看到一句泛泛的失败）")
        .isEqualTo("生成失败：上游返回 502");
  }

  // ────────────────────────── ② 终态只取一次（可证伪）──────────────────────────

  /**
   * ★★ <strong>"终态只取一次"可证伪</strong>：第二个收尾入口被调用 ⇒ 仍然只有一条 {@code done}、只落一次库。
   *
   * <p>造法：事件出口在收到 {@code done} 那一帧时<strong>重入</strong>收尾 —— 走的是 {@code
   * ChatService#streamTurnWithTerminalGate} 给出的、<strong>同一个 {@code Run}</strong> 上的收尾入口 （{@link
   * TerminalGate}）。这正是 {@code Run#terminalTaken} 那道 {@code compareAndSet} 要挡的形状。
   *
   * <p>★★ 去掉那道守卫会怎样（本批实测的那次反证）：重入那一次会一路走到装配器与落库上 —— 本用例里读到的是"出口上出现了<strong>第二条
   * done</strong>"，而生产里的后果是同一轮 <strong>把终态落两次</strong>。所以下面两条断言各守一半："收尾不许抛"（守卫缺了不许把正常收尾变成异常）
   * 与"终态恰好一条 / 只落一次库"。
   */
  @Test
  void aSecondTerminalEntryPointStillLeavesExactlyOneDone() {
    Fixture fixture = new Fixture();
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();
    TerminalGate gate = new TerminalGate();

    assertThatCode(
            () ->
                fixture
                    .chatService()
                    .streamTurnWithTerminalGate(
                        turn, QUESTION, new ReentrantTerminalSink(sink, gate), gate))
        .as("★★ 收尾里出现第二个收尾入口，不许把一次正常收尾变成异常（守着它的是 terminalTaken 那道 CAS）")
        .doesNotThrowAnyException();

    assertThat(gate.secondTerminalAttempted())
        .as("★★ 前置：第二个收尾入口真的被调用过（否则这条用例什么都没验 —— 它自己也会因此变红）")
        .isTrue();
    assertThat(sink.kinds().stream().filter(kind -> kind == EventKind.DONE).count())
        .as("★★ 第二个收尾入口被调用 ⇒ 出口上仍然只有一条 done")
        .isEqualTo(1L);
    assertThat(fixture.store.finishCount(turn.assistantMessageId()))
        .as("★★ 落库也只许一次 —— 第二次收尾必须什么都不做（先到者收，后到的返回）")
        .isEqualTo(1);
    assertThat(sink.done().status())
        .as("★ 终态停在先到的那条路径上（COMPLETE），不被第二次收尾改写")
        .isEqualTo(StreamStatus.GENERATED);
  }

  // ────────────────────────── ③ 闲置超时 ──────────────────────────

  /**
   * ★ 闲置超时 ⇒ {@code IDLE_TIMEOUT} + {@code FAILED}，且原因与 hint 是超时那一对。
   *
   * <p>造法：模型一块都不吐、睡满一整个闲置超时。★ 这条用<strong>真实时钟</strong>造（不是 mock 时钟）——
   * "心跳不刷新超时时钟"那条纪律正是在这个形状上被踩出来的（见 {@code ChatService.Run#lastBusinessFrameAtNanos}）。
   */
  @Test
  void anIdleUpstreamIsTerminatedAsIdleTimeout() {
    Fixture fixture = new Fixture();
    ScriptedModel silent = new ScriptedModel(60_000L, 0L, 0, SHORT_ANSWER);
    fixture.model.set(silent);
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.kinds()).containsSubsequence(EventKind.ERROR, EventKind.DONE);
    assertThat(sink.done().status())
        .as("★ 闲置超时的终态是 FAILED（与 COMPLETE 相对）")
        .isEqualTo(StreamStatus.FAILED);
    assertThat(sink.error().reason()).as("★ 超时的原因").isEqualTo("生成超时");
    assertThat(sink.error().hint()).isEqualTo("可稍后重试；若持续超时请联系管理员");

    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted.path()).isEqualTo(StreamTerminationPath.IDLE_TIMEOUT);
    assertThat(persisted.failReason()).isEqualTo("生成超时");
    // ★ 收尾必须打断上游：不打断的话"用户已经看到失败、模型还在烧 token"，而且不报错。
    //   ★ 等一小会儿：打断是收尾之后对另一条线程的动作，模型线程处理中断位本身要一点点时间 ——
    //     不等就断言等于把"打断晚了一微秒"误报成"没打断"（本批实测踩到过这个 flake）。
    assertThat(waitForInterrupt(silent, 2_000L))
        .as("★★ 收尾必须打断上游（StopUpstream 的唯一用处）—— 不打断就是「已经失败了但还在烧 token」")
        .isTrue();
  }

  /** 等模型线程真的处理了中断位（有上限，超时即 false —— 它不会把"没打断"藏起来）。 */
  private static boolean waitForInterrupt(ScriptedModel model, long timeoutMs) {
    long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
    while (System.nanoTime() < deadline) {
      if (model.interrupted()) {
        return true;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return model.interrupted();
      }
    }
    return model.interrupted();
  }

  // ────────────────────────── ④ 停止 ──────────────────────────

  /**
   * ★ 显式停止 ⇒ {@code ABORT} + {@code STOPPED}，且<strong>不发 error</strong>（停止是用户意图，不是故障）。
   *
   * <p>★ 与 HTTP 层那条停止用例读的是同一件事的两半：那边读"库里 {@code STOPPED} + 正文保留"， 这边读"事件序列里<strong>没有</strong>
   * error"—— 后者在 HTTP 层只读得到一条流，读不出"少了一条本不该有的帧"。
   */
  @Test
  void aStopSignalEndsAsAbortWithoutAnErrorFrame() {
    Fixture fixture = new Fixture();
    fixture.model.set(new ScriptedModel(0L, 5L, 12, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();
    // ★ 停止是独立于连接的显式信号（连接断开不算停止，见 ChatStopSignal 的类注释）
    fixture.stopSignal.request(turn.assistantMessageId());

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.kinds())
        .as("★ 停止路径不许夹一条 error（否则界面上会出现一条「出错了请重试」的横幅，而用户只是按了停止）")
        .doesNotContain(EventKind.ERROR);
    assertThat(sink.done().status()).isEqualTo(StreamStatus.STOPPED);
    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted.path())
        .as("★ pathOf()：显式停止优先于其它判定")
        .isEqualTo(StreamTerminationPath.ABORT);
    assertThat(persisted.failReason())
        .as("★ 非失败路径的 failReason 必须是 null（不是空串 —— 库里那一列是可空的）")
        .isNull();
  }

  // ────────────────────────── ⑤ 正常写完（基准）──────────────────────────

  /** ★ 正常写完 ⇒ {@code COMPLETE} + {@code GENERATED}（四条路径里的基准那一条）。 */
  @Test
  void aCompletedUpstreamEndsAsGenerated() {
    Fixture fixture = new Fixture();
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.kinds()).startsWith(EventKind.META, EventKind.REFS).endsWith(EventKind.DONE);
    assertThat(sink.kinds()).doesNotContain(EventKind.ERROR);
    assertThat(sink.done().status()).isEqualTo(StreamStatus.GENERATED);
    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted.path()).isEqualTo(StreamTerminationPath.COMPLETE);
    assertThat(persisted.text()).isEqualTo(SHORT_ANSWER);
    assertThat(persisted.failReason()).isNull();
  }

  // ────────────────────────── 一轮问答的最小真实装配 ──────────────────────────

  /**
   * 一份"真的能跑"的 {@code ChatService} 装配：三个依赖是替身，而<strong>编排、线程、时钟、装配器全是真的</strong>。
   *
   * <p>★ 检索侧刻意给"零命中"（{@code visibleKbIds} 返回空）：本类读的是<strong>终止路径</strong>， 引用那一路有自己的读数（{@code
   * ChatStreamHttpTest} 与领域侧各一条）。让检索返空还有一个作用： 它把一个变量从本类里拿掉，红了就是终止路径的问题。
   */
  private static final class Fixture {

    /** 当前这一轮用的"假模型"（本类每个用例自己装一个）。 */
    private final AtomicReference<ChatStreamPort> model = new AtomicReference<>();

    private final RecordingStore store = new RecordingStore();

    private final ChatStopSignal stopSignal = new ChatStopSignal();

    private final ChatService chatService =
        new ChatService(
            store,
            new EmptySearchPort(),
            new AnonymousScope(),
            (modelId, prompt, timeout, callback) ->
                model.get().stream(modelId, prompt, timeout, callback),
            stopSignal,
            PROPERTIES);

    private ChatService chatService() {
      return chatService;
    }

    /** 建这一轮的落库读数（替身不发号，id 由这里现造）。 */
    private ChatTurnWriter.PreparedTurn prepare() {
      return store.prepare(null, null, QUESTION, UUID.randomUUID());
    }
  }

  /**
   * "第二个收尾入口"的把手。
   *
   * <p>★ 它是 {@code ChatService#streamTurnWithTerminalGate} 的入参，而那个入口把本对象交回给调用方：
   * 于是测试可以<strong>事后</strong>在<strong>同一个 {@code Run}</strong> 上再收一次尾 —— 走的是生产里那道守卫，不是测试自己模拟的一道闸。
   */
  private static final class TerminalGate implements ChatService.TerminalGate {

    private final AtomicBoolean secondAttempted = new AtomicBoolean(false);

    private Runnable secondTerminal = () -> {};

    @Override
    public void onSecondTerminal(Runnable finishAgain) {
      this.secondTerminal = finishAgain;
    }

    /** 在同一个 {@code Run} 上再走一次收尾（第二次收尾入口）。 */
    private void finishAgain() {
      secondAttempted.set(true);
      secondTerminal.run();
    }

    private boolean secondTerminalAttempted() {
      return secondAttempted.get();
    }
  }

  /**
   * 事件出口替身：记下每一条事件（可选：<strong>断一次</strong>）。
   *
   * <p>★★ {@code failAfterFrames} 的语义是"写第 N+1 帧时<strong>断一次</strong>"，而不是"之后每一帧都失败"： 真实的 socket
   * 写失败之后，服务端仍然会继续尝试写终态帧（它并不知道对面已经走了）—— 而本类要读的正是那两帧（{@code error} → {@code
   * done}）。若这里"一直失败"，那两帧就永远读不到， 于是"断开路径上 error→done 的顺序"这条判据会被一个<strong>读不到的注入</strong>假装覆盖过去。
   */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    /** {@code > 0} = 收满这么多帧之后，下一次写抛一次"连接已断开"（{@code DISCONNECT} 的注入点）。 */
    private int failAfterFrames;

    /** ★ 那一次写失败真的发生过（"注入生效了吗"本身要可断言，否则整条用例可能什么都没验）。 */
    private boolean writeFailed;

    @Override
    public void onEvent(StreamEvent event) {
      if (failAfterFrames > 0 && !writeFailed && events.size() >= failAfterFrames) {
        writeFailed = true;
        throw ChatStreamWriter.disconnected(new IOException("测试注入：写连接失败"));
      }
      events.add(event);
    }

    private List<EventKind> kinds() {
      return events.stream().map(StreamEvent::kind).toList();
    }

    private StreamEvent.StreamDoneEvent done() {
      return (StreamEvent.StreamDoneEvent) lastOf(EventKind.DONE);
    }

    private StreamEvent.StreamErrorEvent error() {
      return (StreamEvent.StreamErrorEvent) lastOf(EventKind.ERROR);
    }

    private StreamEvent lastOf(EventKind kind) {
      return events.stream()
          .filter(event -> event.kind() == kind)
          .reduce((first, second) -> second)
          .orElseThrow(() -> new AssertionError("出口上没有收到 " + kind + " 帧"));
    }

    /** 出口上真的看到的正文（由 {@code done} 之前那些 {@code delta} 拼出来）。 */
    private String textSeen() {
      StringBuilder text = new StringBuilder();
      for (StreamEvent event : events) {
        if (event instanceof StreamEvent.Delta delta) {
          text.append(delta.text());
        }
      }
      return text.toString();
    }
  }

  /** 会在收到终态那一帧时<strong>再走一次收尾</strong>的出口 —— 即"第二个收尾入口被调用"。 */
  private static final class ReentrantTerminalSink implements ChatService.EventSink {

    private final ChatService.EventSink delegate;
    private final TerminalGate gate;
    private final AtomicBoolean reentered = new AtomicBoolean(false);

    ReentrantTerminalSink(ChatService.EventSink delegate, TerminalGate gate) {
      this.delegate = delegate;
      this.gate = gate;
    }

    @Override
    public void onEvent(StreamEvent event) {
      delegate.onEvent(event);
      boolean terminal = event.kind() == EventKind.DONE || event.kind() == EventKind.ERROR;
      if (terminal && reentered.compareAndSet(false, true)) {
        // ★ 与第一次收尾在同一条线程上重入（"两处同时收尾"里的一种确定形状）
        gate.finishAgain();
      }
    }
  }

  /** 落库侧替身：只记"收尾落库了几次、落的是什么"。 */
  private static final class RecordingStore implements ChatTurnStore {

    private final List<TerminalPersisted> terminals = new ArrayList<>();

    @Override
    public ChatTurnWriter.PreparedTurn prepare(
        UUID conversationId, UUID appId, String question, UUID userId) {
      UUID conversation = conversationId == null ? UUID.randomUUID() : conversationId;
      UUID assistantMessageId = UUID.randomUUID();
      return new ChatTurnWriter.PreparedTurn(
          conversation,
          assistantMessageId,
          UUID.randomUUID(),
          "trace_" + assistantMessageId,
          "fixture-model",
          UUID.randomUUID(),
          List.of());
    }

    @Override
    public int flush(UUID messageId, String fullTextSoFar, int persistedChars) {
      return fullTextSoFar.length();
    }

    @Override
    public void finish(
        UUID messageId,
        StreamTerminationPath path,
        String fullTextSoFar,
        int persistedChars,
        List<SourceRef> references,
        TokenUsage usage,
        long durationMs,
        String failReason,
        String failHint) {
      terminals.add(
          new TerminalPersisted(
              messageId, path, fullTextSoFar, usage, durationMs, failReason, failHint));
    }

    private TerminalPersisted finishOf(UUID messageId) {
      return terminals.stream()
          .filter(terminal -> terminal.messageId().equals(messageId))
          .findFirst()
          .orElse(null);
    }

    private int finishCount(UUID messageId) {
      return (int)
          terminals.stream().filter(terminal -> terminal.messageId().equals(messageId)).count();
    }
  }

  /** 一次收尾落库的读数。 */
  private record TerminalPersisted(
      UUID messageId,
      StreamTerminationPath path,
      String text,
      TokenUsage usage,
      long durationMs,
      String failReason,
      String failHint) {}

  /**
   * 脚本化的假模型（★ 只在本测试类里，不起任何长驻服务）。
   *
   * <p>三条义务与生产实现相同：① 回调抛异常必须让它传出去并停止吐字（那是"停止 / 超时 / 写失败"唯一的取消机制）； ② {@code onEnd} 恰好一次；③
   * 被打断时恢复中断位并抛出。
   */
  private static final class ScriptedModel implements ChatStreamPort {

    private final long firstChunkDelayMs;
    private final long chunkDelayMs;
    private final int chunkChars;
    private final String answer;

    /** 收尾有没有真的打断它（{@code IDLE_TIMEOUT} 那条用例读它）。 */
    private volatile boolean interrupted;

    ScriptedModel(long firstChunkDelayMs, long chunkDelayMs, int chunkChars, String answer) {
      this.firstChunkDelayMs = firstChunkDelayMs;
      this.chunkDelayMs = chunkDelayMs;
      this.chunkChars = chunkChars;
      this.answer = answer;
    }

    private boolean interrupted() {
      return interrupted;
    }

    @Override
    public void stream(UUID modelId, String prompt, Duration timeout, Callback callback) {
      try {
        sleep(firstChunkDelayMs);
        if (chunkChars <= 0) {
          callback.onEnd(End.COMPLETE, null);
          return;
        }
        for (int at = 0; at < answer.length(); at += chunkChars) {
          // ★ 回调抛了就让它传出去（见类注释第 1 条）
          callback.onChunk(answer.substring(at, Math.min(answer.length(), at + chunkChars)));
          sleep(chunkDelayMs);
        }
        callback.onEnd(End.COMPLETE, null);
      } catch (InterruptedException interruptedHere) {
        interrupted = true;
        Thread.currentThread().interrupt();
        throw new IllegalStateException("假模型在吐字间隙被打断", interruptedHere);
      }
    }

    private static void sleep(long millis) throws InterruptedException {
      if (millis > 0) {
        Thread.sleep(millis);
      }
    }
  }

  // ────────────────────────── 两个平台替身 ──────────────────────────

  /**
   * 检索端口替身：<strong>零命中</strong>（{@code visibleKbIds} 返空 ⇒ 后续方法一次都不会被调到）。
   *
   * <p>★ 没被用到的那些方法刻意<strong>抛</strong>而不是返回空：本类若哪天开始真的走检索， 那说明这条链的形状变了 ——
   * 那时应当失败并让人显式看一遍，而不是安静地拿到一个空结果。
   */
  private static final class EmptySearchPort implements SegmentSearchPort {

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不走向量路");
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      throw new UnsupportedOperationException("零命中时不该走到全文检索");
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不做两路同谓词");
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      throw new UnsupportedOperationException("零命中时没有展示信息要取");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类不走向量路");
    }
  }

  /**
   * 范围端口替身：<strong>取不到身份</strong>（{@link ScopeFilter#nobody()} ⇒ {@code maxClassification ==
   * null}）。
   *
   * <p>★ 刻意的 fail-closed：与 {@code ChatService#retrieve} 里那条"取不到身份 ⇒ 一条引用都不给"对应， 于是本类每条用例的 {@code
   * refs} 都是"零命中"这条确定路径。
   */
  private static final class AnonymousScope implements AccessScopePort {

    @Override
    public ScopeFilter currentScope() {
      return ScopeFilter.nobody();
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return false;
    }
  }
}
