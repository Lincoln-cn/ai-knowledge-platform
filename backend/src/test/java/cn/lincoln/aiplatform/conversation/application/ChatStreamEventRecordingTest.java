package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.EventKind;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamStatus;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.retrieval.SourceLabels;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
  void aStopSignalEndsAsAbortWithoutAnErrorFrame() throws Exception {
    Fixture fixture = new Fixture();
    // ★★ 模型要**慢到跑得完一轮**：停止请求必须在 `Run` 构造**之后**才到（见下面的说明）。
    fixture.model.set(new ScriptedModel(0L, 40L, 12, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    // ★★ 时序必须与生产一致：**先开流、再置停止信号**。
    //   为什么（本批实测踩到）：`ChatService.Run` 的构造里会调 `ChatStopSignal#begin`（#143 后半：
    //   一轮开始清掉上一条同 id 的残留）—— 所以"先置信号、再开流"的写法会被那一次清理**当场抹掉**，
    //   于是这一轮正常跑完（逐字读数：`agree ⇒ requested=true` / `begin ⇒ decided=true` /
    //   `consume ⇒ decided=false` ⇒ `expected STOPPED but was GENERATED`）。
    //   ★ 而生产链路正是"流已经开始、用户在界面上点了停止"⇒ 信号在 Run 之后到达。
    Thread streaming = new Thread(() -> fixture.chatService().streamTurn(turn, QUESTION, sink));
    streaming.start();

    // 等这一轮真的开始（`begin` 已经跑过）再置信号 —— 用 JDK 自己的等待，不靠 sleep 猜。
    sink.awaitFirstChunk();
    fixture.stopSignal.request(turn.assistantMessageId());
    assertThat(fixture.stopSignal.agree(turn.assistantMessageId()))
        .as("前提：这一轮真的答应了停（否则下面的断言在验一条从没发生过的停止）")
        .isTrue();

    streaming.join(Duration.ofSeconds(10).toMillis());
    assertThat(streaming.isAlive()).as("前提：这一轮必须在 10 秒内收尾（否则读数是在半路上取的）").isFalse();

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

  // ────────────────────────── ⑥ 身份取不到 ⇒ 整批 fail-closed（#123 判据③）──────────────────────────

  /**
   * ★★ 判据③（{@code #123}）：<strong>身份 / 范围取不到 ⇒ 整批 fail-closed（一条引用都不给）</strong>。
   *
   * <p>★ 这条用例存在的理由不是"多验一条边界"，而是它是"改一处不能碰另一处"的<strong>反证</strong>： {@code ChatService#retrieve}
   * 这一批把"单条坏数据"改成了<strong>逐条跳过</strong>，而"身份取不到"必须 <strong>仍然</strong>是整批的 —— 两者一旦被合成一条（例如把 {@code
   * ceiling == null} 也当成"这一条装不成"）， "可见性无法判定"就会降级成"照常返回几条引用"。那不是省事，那是把 fail-closed 换成了 fail-open：
   * 给出去的东西<strong>没人判过可见性</strong>，而且不报错。
   *
   * <p>造法：检索端口<strong>有</strong>命中（可见库、命中集合、展示信息都齐），而范围端口是 {@link ScopeFilter#nobody()} （{@code
   * maxClassification == null}）—— 这正是"能查到数据、但身份没跟过来"那一刻的形状 （第 17 周那个 P0 的成因）。⇒
   * 期望：引用给成<strong>空</strong>，而答案照常生成、照常收尾。
   */
  @Test
  void anUnavailableIdentityKeepsTheWholeBatchFailClosed() {
    Fixture fixture = new Fixture(new HitSearchPort(), new AnonymousScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    assertThat(sink.refs())
        .as("★★ 取不到身份 ⇒ 一条引用都不给（不是『跳过几条之后照常返回』，更不是『给全部』）" + "—— 可见性无法判定时，唯一安全的方向是什么都不给")
        .isEmpty();
    assertThat(sink.kinds())
        .as("★ 零引用不是失败：仍然是一条正常收尾的流（fail-closed 的引用与『检索没跑』在装配器里是两件事）")
        .startsWith(EventKind.META, EventKind.REFS)
        .endsWith(EventKind.DONE);
    assertThat(sink.kinds()).doesNotContain(EventKind.ERROR);
    TerminalPersisted persisted = fixture.store.finishOf(turn.assistantMessageId());
    assertThat(persisted.path())
        .as("★ 收尾路径不受影响（引用为空不改这一轮的结局）")
        .isEqualTo(StreamTerminationPath.COMPLETE);
  }

  // ────────────────────────── ⑦ 引用载荷的两个结构性分量（第 17 周）──────────────────────────

  /**
   * ★★ 引用载荷里那两个结构性分量<strong>取自真实值</strong>：知识库引用必带 {@code kbId} / {@code documentId}，
   * 而<strong>会话域引用的 {@code kbId} 必为 {@code null}</strong> —— <strong>同一条用例里两个方向都钉住</strong>。
   *
   * <h3>为什么必须两个方向在同一条用例里</h3>
   *
   * <p>只钉"库引用有 {@code kbId}"这一条的话，"给所有引用都补一个 kbId"这种改法<strong>照样绿</strong> —— 而它恰好会毁掉前端的判据（{@code
   * kbId} 缺失 = 会话域 ⇒ 会话附件被读成「知识库：」）。 反过来只钉"会话域为空"也不行：装配点恒传 {@code
   * null}（本批修掉的那一处）会让它<strong>恒绿</strong>。 两个方向一起才把"真实值透传"这件事钉成一个可证伪的命题。
   *
   * <h3>★ 金丝雀（本批实测做过一次）</h3>
   *
   * <p>把装配点的 {@code card.kbId()} 改回 {@code null} ⇒ 本用例第一条断言立刻红（见交付报告里逐字读数）。 ★ 同理，把 {@code
   * card.documentId()} 改回 {@code null} ⇒ 第二条红（{@code #91} 的根因读数）。
   *
   * <p>★ 本用例的会话域那一条**必须**在会话 A 里跑（{@code prepare(CONVERSATION_A)}）：会话域那一支的绑定值 就是这一轮的会话 id，而 {@link
   * HitSearchPort#fullText} 按它过滤 —— 用随机会话 id 的话两条命中会变成一条， 本用例的前置断言会先红（那正是判据 1 的读数，见 {@link
   * #theAttachmentOfThisConversationIsReferencedAndAnotherConversationsIsNot()}）。
   */
  @Test
  void referencesCarryTheRealDocumentAndKbIdWhileTheConversationDomainStaysNull() {
    Fixture fixture = new Fixture(new HitSearchPort(), new KnownScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare(HitSearchPort.CONVERSATION_A);
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(turn, QUESTION, sink);

    List<SourceRef> refs = sink.refs();
    assertThat(refs).as("★ 前置：两条命中（知识库域 + 会话域）都必须装成引用 —— 否则下面两条断言都在空跑").hasSize(2);
    SourceRef kbRef = refOf(refs, HitSearchPort.SEGMENT_ID);
    SourceRef conversationRef = refOf(refs, HitSearchPort.CONVERSATION_SEGMENT_ID);

    assertThat(kbRef.kbId())
        .as(
            "★★ 判据 1：真栈上的知识库引用**必须带 kbId** —— 它就是前端"
                + "「知识库：<kbName>」那条结构性判据的判据本身（缺失 = 会话域）。此前装配点恒传 null ⇒ 每条库引用都被读成「来源：」")
        .isEqualTo(HitSearchPort.KB_ID.toString());
    assertThat(kbRef.documentId())
        .as(
            "★★ 判据 3（#91 的根因）：documentId 必须非空 —— 前端 Index.vue 用它拼深链的 query，"
                + "恒为 null 时点击是一次**静默 no-op**（不报错、也不跳转）")
        .isEqualTo(HitSearchPort.DOCUMENT_ID.toString());

    assertThat(conversationRef.kbId())
        .as(
            "★★ 判据 2：会话域引用的 kbId **必为 null**（那一行没有库行）——"
                + "若这里被补上了值，前端就会把会话附件读成「知识库：」，而那是本批要修的那个误判本身")
        .isNull();
    assertThat(conversationRef.documentId())
        .as(
            "★ 会话域引用同样带 documentId（文档行两个域都有）⇒ 深链在会话附件上也拼得出 query"
                + "（来源名的口径见 #theConversationAttachmentSourceNameIsTheAgreedConstant）")
        .isEqualTo(HitSearchPort.CONVERSATION_DOCUMENT_ID.toString());
  }

  // ────────────────────────── ⑦b 会话域的接线（本批 · 判据 1 / 判据 2）──────────────────────────

  /**
   * ★★ <strong>本会话的附件引用得到，另一个会话引用不到</strong> —— 两个方向在<strong>同一条用例</strong>里。
   *
   * <h3>它钉的是什么（这是"会话附件真的接进检索链路了吗"的唯一读数）</h3>
   *
   * <p>接线之前，{@code ChatService#retrieve} 用的是"只带共用库域"的重载 （{@code new TextQuery(问题, kbIds, limit)} ⇒
   * {@code RetrievalScope.shared}）⇒ <strong>会话域那一支恒假</strong>：
   * 会话里传的附件<strong>永远检索不到</strong>（能建、谓词也通，就是没接上），而且不报错、不留痕。
   *
   * <p>★ 两个方向<strong>必须一起钉</strong>：
   *
   * <ul>
   *   <li>只钉"会话 A 能引用到" ⇒ "把范围放大成不分会话"（甚至"把 {@code conversationId} 去掉、回到 shared"）
   *       那些改法里，有的<strong>照样绿</strong>；
   *   <li>只钉"会话 B 引用不到" ⇒ 变量是"检索端口压根不支持域"（替身不实现 {@code retrievalScope}， 异常被整批 fail-closed 接住 ⇒ 恒空 ⇒
   *       恒绿）。两个方向一起，才逼出"这一轮的会话 id 真的进了域"。
   * </ul>
   *
   * <p>★ 同时也是判据 1 的**内容**读数（不是"有引用"而是"引用到的是那条附件的正文"）：断言里是这个附件的 正文关键词，而不是"refs 非空" ——
   * 后者在"引用到的是知识库那条"时也成立。
   *
   * <p>★★ 金丝雀（本批实测）：把装配点改回 {@code new TextQuery(question, turn.kbIds(), …)}（即 {@code
   * RetrievalScope.shared}）⇒ 本用例<strong>会话 A 那一半立刻红</strong>（逐字读数见交付报告）。
   */
  @Test
  void theAttachmentOfThisConversationIsReferencedAndAnotherConversationsIsNot() {
    Fixture fixture = new Fixture(new HitSearchPort(), new KnownScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));

    // ── 方向一：会话 A（附件属于它）⇒ 引用得到 ──
    RecordingSink inConversationA = new RecordingSink();
    fixture
        .chatService()
        .streamTurn(fixture.prepare(HitSearchPort.CONVERSATION_A), QUESTION, inConversationA);

    assertThat(inConversationA.refs())
        .as("★★ 判据 1：会话里传的附件必须能被这一轮的问答引用到 —— 接线前它恒为空（会话域那一支恒假），且不报错")
        .anySatisfy(
            ref ->
                assertThat(ref.full())
                    .as("★ 引用到的是**那条附件的正文**（不只是『refs 非空』：引用到知识库那条也会非空）")
                    .isEqualTo(HitSearchPort.CONVERSATION_CONTENT));
    assertThat(inConversationA.refs())
        .as("★ 前置：这一轮同时引用到知识库那一条（两条命中都在，说明下面会话 B 的『引用不到』不是『检索整体坏了』）")
        .anySatisfy(
            ref -> assertThat(ref.segmentId()).isEqualTo(HitSearchPort.SEGMENT_ID.toString()));

    // ── 方向二：同一个人的**另一个**会话 ⇒ 引用不到 ──
    RecordingSink inConversationB = new RecordingSink();
    fixture
        .chatService()
        .streamTurn(fixture.prepare(HitSearchPort.CONVERSATION_B), QUESTION, inConversationB);

    assertThat(inConversationB.refs())
        .as("★★ 判据 2：同一条附件在**另一个会话**里必须引用不到（会话域那一支带 scope_id = :conversationId）")
        .noneSatisfy(ref -> assertThat(ref.full()).isEqualTo(HitSearchPort.CONVERSATION_CONTENT));
    assertThat(inConversationB.refs())
        .as("★ 前置：会话 B 里知识库那一条照常在（判据 3：库引用不受影响）—— 否则『引用不到』可能只是检索没跑")
        .anySatisfy(
            ref -> assertThat(ref.segmentId()).isEqualTo(HitSearchPort.SEGMENT_ID.toString()));
  }

  /**
   * ★★ 会话域引用的 {@code kbName} <strong>逐字等于约定的常量</strong>（{@link
   * SourceLabels#CONVERSATION_ATTACHMENT}）。
   *
   * <h3>为什么这条必须有</h3>
   *
   * <p>会话临时的分段没有库行 ⇒ 左连接下 {@code kb_name} 是 {@code NULL}，而契约 {@code SourceRef.kbName}
   * <strong>必填</strong> ⇒ 不给这条约定，这些引用会被"逐条跳过"<strong>整批丢掉</strong>： 日志里一条 WARN
   * 带计数、界面上<strong>一条都没有</strong>（与"这个会话没有相关附件"不可分）。
   *
   * <p>★ 两个方向一起钉：会话域那一条等于常量，而<strong>知识库引用仍然是它自己的库名</strong> ——
   * 只钉前者的话，"给所有引用都补上这个值"这种改法照样绿（而它会让库引用的来源全变成"会话附件"）。
   *
   * <p>★ 替身那一侧刻意给的是 {@code null}（真实左连接的形状）：所以这条断言读的正是 <strong>装配点补的那一格</strong>，不是替身自己塞的值。
   */
  @Test
  void theConversationAttachmentSourceNameIsTheAgreedConstant() {
    Fixture fixture = new Fixture(new HitSearchPort(), new KnownScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    RecordingSink sink = new RecordingSink();

    fixture.chatService().streamTurn(fixture.prepare(HitSearchPort.CONVERSATION_A), QUESTION, sink);

    List<SourceRef> refs = sink.refs();
    assertThat(refs).as("★ 前置：两条命中都装成了引用（否则下面两条断言在空跑）").hasSize(2);

    assertThat(refOf(refs, HitSearchPort.CONVERSATION_SEGMENT_ID).kbName())
        .as(
            "★★ 判据 4：会话域的引用没有库行 ⇒ 后端按约定发这个常量（前端把它渲染成 `来源：<kbName>`）。"
                + "★ 不给它，这一条会被逐条跳过整批丢掉：界面一条都没有")
        .isEqualTo(SourceLabels.CONVERSATION_ATTACHMENT);
    assertThat(refOf(refs, HitSearchPort.SEGMENT_ID).kbName())
        .as("★ 判据 3 的另一半：知识库引用仍然是**它自己的库名** —— 若被一起换成常量，库引用的来源就全错了")
        .isEqualTo("SSE 夹具库");
  }

  // ────────────────────────── ⑧ "范围为空"那条早退的留痕（第 17 周补）──────────────────────────

  /**
   * ★★ 判据 5 的一半：<strong>"范围为空"（合法状态）必须留痕，且与"身份为空"可分</strong>。
   *
   * <p>它此前<strong>一条日志都没有</strong> ⇒ 与"库里确实没有相关内容"在读数上完全一样。而它有两个成因： ① 用户确实没有任何可见库（本用例：身份正常，只是没有库）；②
   * {@code currentScope()} 是 {@code nobody()} （下一条用例）。两者<strong>处置相同</strong>（整批
   * fail-closed）、<strong>含义完全不同</strong> ⇒ 必须能分开读。
   *
   * <p>★ 同时钉住<strong>返回值语义一个字没改</strong>：引用仍是空、终态仍是 {@code GENERATED}、收尾路径仍是 {@code
   * COMPLETE}（零引用不是失败，也不是"检索没跑"）。
   */
  @Test
  void anEmptyVisibleRangeIsLoggedAsALegitimateState() {
    Fixture fixture = new Fixture(new EmptySearchPort(), new KnownScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    List<String> messages = streamTurnCapturingLog(fixture, turn, sink);

    assertThat(messages)
        .as("★★ 范围为空必须留痕（它此前是**无日志早退**：与『库里确实没有』不可分）")
        .anyMatch(message -> message.contains("没有任何可见库"));
    assertThat(messages)
        .as("★ 两种形状必须可分：身份正常时**不许**出现 nobody() 那条（否则这条日志读起来与身份缺失一样）")
        .noneMatch(message -> message.contains("nobody()"));

    assertThat(sink.refs()).as("★ 返回值语义未变：范围为空 ⇒ 一条引用都不给（整批 fail-closed）").isEmpty();
    assertThat(sink.done().status())
        .as("★ 零引用不是失败：终态仍是 GENERATED")
        .isEqualTo(StreamStatus.GENERATED);
    assertThat(sink.kinds()).doesNotContain(EventKind.ERROR);
    assertThat(fixture.store.finishOf(turn.assistantMessageId()).path())
        .as("★ 收尾路径未被这一批影响")
        .isEqualTo(StreamTerminationPath.COMPLETE);
  }

  /**
   * ★★ 判据 5 的另一半：<strong>"身份为空"（异常形状）也必须留痕，且文案与"范围为空"不同</strong>。
   *
   * <p>造法：范围端口是 {@link ScopeFilter#nobody()}（{@code me == null}）而检索端口"没有可见库" —— 这正是 第 17 周那个 P0
   * 的形状（模型线程上读不到范围的 {@code ThreadLocal}）：可见性<strong>无法判定</strong>， 于是 {@code visibleKbIds} 为空。★
   * 它与"用户没有库"在返回值上不可分，所以只能靠<strong>日志</strong>分。
   */
  @Test
  void anUnavailableIdentityIsLoggedDistinctlyFromAnEmptyRange() {
    Fixture fixture = new Fixture(new EmptySearchPort(), new AnonymousScope());
    fixture.model.set(new ScriptedModel(0L, 0L, 6, SHORT_ANSWER));
    ChatTurnWriter.PreparedTurn turn = fixture.prepare();
    RecordingSink sink = new RecordingSink();

    List<String> messages = streamTurnCapturingLog(fixture, turn, sink);

    assertThat(messages)
        .as("★★ 身份为空（currentScope() 是 nobody()）必须留痕，而且要与『用户没有可见库』区分开")
        .anyMatch(message -> message.contains("nobody()"));
    assertThat(messages)
        .as("★ 两种形状必须可分：身份缺失时**不许**把它读成『用户确实没有可见库』这条合法状态")
        .noneMatch(message -> message.contains("没有任何可见库"));

    assertThat(sink.refs()).as("★ 返回值语义未变：整批 fail-closed，一条引用都不给").isEmpty();
    assertThat(sink.done().status()).as("★ 零引用不改这一轮的结局").isEqualTo(StreamStatus.GENERATED);
    assertThat(fixture.store.finishOf(turn.assistantMessageId()).path())
        .as("★ 收尾路径未被这一批影响")
        .isEqualTo(StreamTerminationPath.COMPLETE);
  }

  /** 跑一轮并把它写给 {@code ChatService} 那个 logger 的日志读回来（读完即摘，不留给下一条用例）。 */
  private static List<String> streamTurnCapturingLog(
      Fixture fixture, ChatTurnWriter.PreparedTurn turn, RecordingSink sink) {
    ListAppender<ILoggingEvent> appender = captureChatServiceLog();
    try {
      fixture.chatService().streamTurn(turn, QUESTION, sink);
    } finally {
      releaseLogCapture(appender);
    }
    return logMessagesOf(appender);
  }

  /** 按分段 id 取那一条引用（★ 不按下标取：下标依赖假端口的返回顺序，那是另一个变量的读数）。 */
  private static SourceRef refOf(List<SourceRef> refs, UUID segmentId) {
    return refs.stream()
        .filter(ref -> segmentId.toString().equals(ref.segmentId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("引用里没有分段 " + segmentId));
  }

  /**
   * 给 {@code ChatService} 那个 logger 挂一个内存追加器（读完必须 {@link #releaseLogCapture}）。
   *
   * <p>★ 为什么需要它：判据要读的"这条早退走的是哪个形状"只有<strong>日志</strong>这一个载体 —— 它不落库、不进事件， 而"降级不许静默"这条判据不验就等于没做。
   */
  private static ListAppender<ILoggingEvent> captureChatServiceLog() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ChatService.class)).addAppender(appender);
    return appender;
  }

  /** 摘掉上面那个追加器（★ 不摘会把本用例的日志漏给下一条用例，而那种漏读起来像"别处也在这么做"）。 */
  private static void releaseLogCapture(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(ChatService.class)).detachAppender(appender);
    appender.stop();
  }

  /** 那个追加器收到的日志正文（已经过占位符格式化）。 */
  private static List<String> logMessagesOf(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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

    private final ChatService chatService;

    /** 默认装配：检索<strong>零命中</strong> + 身份<strong>取不到</strong>（见两个替身的注释）。 */
    private Fixture() {
      this(new EmptySearchPort(), new AnonymousScope());
    }

    /**
     * 换掉检索与身份这两个端口。
     *
     * <p>★ 需要它的是判据③那条用例：它要的正是 {@link HitSearchPort}"有命中" + {@link AnonymousScope} "身份取不到"这个组合 ——
     * 两者在"引用为空"这个返回值上不可分，只有分开装配才能把 "是身份没跟过来"与"库里确实没有"分开。
     */
    private Fixture(SegmentSearchPort search, AccessScopePort scope) {
      this.chatService =
          new ChatService(
              store,
              search,
              scope,
              (modelId, prompt, timeout, callback) ->
                  model.get().stream(modelId, prompt, timeout, callback),
              stopSignal,
              PROPERTIES);
    }

    private ChatService chatService() {
      return chatService;
    }

    /** 建这一轮的落库读数（替身不发号，id 由这里现造）。 */
    private ChatTurnWriter.PreparedTurn prepare() {
      return store.prepare(null, null, QUESTION, UUID.randomUUID());
    }

    /**
     * 建这一轮，并指定<strong>它属于哪个会话</strong>。
     *
     * <p>★ 需要它的理由：会话域那一支的绑定值就在这一轮的上下文里（{@code PreparedTurn.conversationId}）——
     * 而"检索范围带没带会话域"这件事，只有让这一轮的会话是<strong>一个已知的 id</strong> 才读得出来 （随机会话 id 下，判据 1 与判据 2
     * 都会退化成"谁也命中不了"）。
     */
    private ChatTurnWriter.PreparedTurn prepare(UUID conversationId) {
      return store.prepare(conversationId, null, QUESTION, UUID.randomUUID());
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

    /** ★ 第一个 {@code delta} 到达的信号 —— 用例靠它"等这一轮真的开始"（见 {@link #awaitFirstChunk()}）。 */
    private final CountDownLatch firstDelta = new CountDownLatch(1);

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
      if (event.kind() == EventKind.DELTA) {
        // ★ 让用例能"等这一轮真的开始"再置停止信号（停止信号必须在 Run 构造之后才到，见用例注释）
        firstDelta.countDown();
      }
    }

    /** 等第一个 {@code delta} 到达（= 这一轮真的开始吐字了，Run 早就构造完）。 */
    private void awaitFirstChunk() throws InterruptedException {
      if (!firstDelta.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("10 秒内没有等到第一个 delta —— 这一轮没有开起来，下面的断言会验在半路上");
      }
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

    /**
     * {@code refs} 帧里<strong>到底是哪几条引用</strong>。
     *
     * <p>★ 与"那一帧在不在"是两件事：只读 kinds 的话，"引用整体为空"与"引用内容对不上"读数完全一样 —— 第 17 周那个 P0（引用恒为空）正是从这个缝里过去的。
     */
    private List<SourceRef> refs() {
      return ((StreamEvent.References) lastOf(EventKind.REFS)).refs();
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

    /**
     * ★ 与生产侧同一份语义（{@code JdbcSegmentSearchQuery#retrievalScope} 的**唯一**解析点）：共用库那一支为空 ⇒ 三支全关 ⇒
     * {@code ChatService} 走"范围为空"那条早退。
     *
     * <p>★★ 为什么<strong>必须显式实现</strong>而不是靠端口的默认：默认实现是"明确拒绝" （{@code
     * UnsupportedOperationException}），而它会被 {@code ChatService#retrieve} 那个"整批 fail-closed"的 {@code
     * catch (RuntimeException)} 接住 —— 于是本类会<strong>绿着</strong>，但绿的是一条"检索端口还不支持域解析"
     * 的路径，而不是本类声称在读的那条。★ 这类"替身没跟上端口、测试照样绿"的洞正是本仓最贵的那一类。
     */
    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      return RetrievalScope.shared(List.of());
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

  /**
   * 检索端口替身：★ <strong>有命中</strong>（可见库 / 命中集合 / 展示信息全齐），只差"身份"。
   *
   * <p>★ 它与 {@link EmptySearchPort} 的分工：那个读"零命中"这条正常路径，这个读"身份取不到"那条 fail-closed 路径。
   * 两者在"引用为空"这个返回值上<strong>不可分</strong> —— 而判据③要验的恰恰是"这一批走的是哪一条分支"，
   * 所以这里必须让命中<strong>真的存在</strong>：只有这样才能证明引用为空的原因不是"没搜到"。
   *
   * <p>★★ 它给的是<strong>两条</strong>命中，一条知识库域、一条会话域（{@code kbId} 为 {@code null}）——
   * 引用载荷里那两个结构性分量（{@code kbId} / {@code documentId}）的两个方向就落在这个形状上 （见 {@link
   * #referencesCarryTheRealDocumentAndKbIdWhileTheConversationDomainStaysNull}）。
   *
   * <p>★ 没被用到的那些方法照旧<strong>抛</strong>（与 {@link EmptySearchPort} 同一条纪律）：
   * 本类若哪天开始走向量路，那说明这条链的形状变了，应当红而不是安静地拿到空结果。
   */
  private static final class HitSearchPort implements SegmentSearchPort {

    private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-000000000001");

    /** 知识库域那一条：★ 它属于 {@link #KB_ID} 那个库。 */
    private static final UUID SEGMENT_ID = UUID.fromString("0b000000-0000-4000-8000-000000000001");

    /** 会话域那一条：★ <strong>没有库行</strong> ⇒ 期望它的 {@code kbId} 是 {@code null}。 */
    private static final UUID CONVERSATION_SEGMENT_ID =
        UUID.fromString("0b000000-0000-4000-8000-000000000002");

    private static final UUID DOCUMENT_ID = UUID.fromString("0c000000-0000-4000-8000-000000000001");

    private static final UUID CONVERSATION_DOCUMENT_ID =
        UUID.fromString("0c000000-0000-4000-8000-000000000002");

    /** ★ 会话附件正文里的关键词：判据 1 就是"另一轮的 refs 里能不能读到它"。 */
    static final String CONVERSATION_CONTENT = "会话附件正文：合同违约金上限见附件三";

    /** ★ 这一条属于哪个会话（会话域那一支按它过滤 —— 换一个会话就检索不到，判据 2）。 */
    static final UUID CONVERSATION_A = UUID.fromString("0e000000-0000-4000-8000-0000000000aa");

    /** ★ 同一个人的**另一个**会话：同一个问题在它里面不许引用到 A 的附件。 */
    static final UUID CONVERSATION_B = UUID.fromString("0e000000-0000-4000-8000-0000000000bb");

    /**
     * ★ 与生产侧同一份语义：共用库那一支过一遍可见性，会话域那一支**照搬调用方给的会话 id** （生产侧再从当前范围取 {@code ownerId}；本替身没有身份那一维 ⇒
     * 只在有会话时补一个非空 owner， 因为 {@code RetrievalScope} 的构造不变量要求 {@code conversationId != null ⇒ ownerId
     * != null}）。
     */
    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      return new RetrievalScope(
          List.of(KB_ID), null, conversationId, conversationId == null ? null : UUID.randomUUID());
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB_ID);
    }

    /**
     * ★★ 域感知的全文检索：会话域**只在这个会话是 {@link #CONVERSATION_A} 时**贡献那一条附件。
     *
     * <p>它让判据 1 / 判据 2 在<strong>同一条用例</strong>里两个方向都可读：同一个人换个会话，同一条附件就该消失。 ★ 这也正是生产侧那道谓词（{@code
     * scope_kind = 'CONVERSATION' and scope_id = :conversationId}）的可读替身。
     */
    @Override
    public List<Scored> fullText(TextQuery query) {
      if (CONVERSATION_A.equals(query.scope().conversationId())) {
        return List.of(new Scored(SEGMENT_ID, 0.9), new Scored(CONVERSATION_SEGMENT_ID, 0.8));
      }
      return List.of(new Scored(SEGMENT_ID, 0.9));
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      List<Card> cards = new ArrayList<>();
      for (UUID id : segmentIds) {
        if (CONVERSATION_SEGMENT_ID.equals(id)) {
          // ★★ 这一格**必须**是 null：真实的那一行来自左连接，而会话临时没有 kb_knowledge_base 行
          //   ⇒ `k.name` 是 NULL。早先这里给的是"会话附件"这个字面量，于是"装配点补没补这一格"
          //   在本类里**读不出来**（替身替它补了）。现在由装配点补（SourceLabels），断言见
          //   #theConversationAttachmentSourceNameIsTheAgreedConstant 与
          //   #referencesCarryTheRealDocumentAndKbIdWhileTheConversationDomainStaysNull。
          cards.add(
              new Card(
                  id,
                  "会话附件里的段落",
                  CONVERSATION_CONTENT,
                  "附件-合同.docx",
                  null,
                  CONVERSATION_DOCUMENT_ID,
                  null,
                  ClassificationLevel.INTERNAL));
        } else {
          cards.add(
              new Card(
                  id,
                  "差旅住宿标准章节",
                  "差旅住宿标准为每人每天500元",
                  "SSE 差旅制度文档",
                  "SSE 夹具库",
                  DOCUMENT_ID,
                  KB_ID,
                  ClassificationLevel.SECRET));
        }
      }
      return cards;
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不走向量路");
    }

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不做两路同谓词");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类不走向量路");
    }
  }

  /**
   * 范围端口替身：<strong>身份正常</strong>（{@code me} 与密级上限都在）。
   *
   * <p>★ 它与 {@link AnonymousScope} 的分工正是"范围为空"那两个成因：用本替身时 {@code visibleKbIds} 为空
   * 只能是"用户确实没有任何可见库"（合法状态）；用那个替身时 {@code currentScope()} 是 {@code nobody()} （异常形状）。两者在读数上必须能分开 —— 见
   * {@link #anEmptyVisibleRangeIsLoggedAsALegitimateState()} 与 {@link
   * #anUnavailableIdentityIsLoggedDistinctlyFromAnEmptyRange()}。
   */
  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0d000000-0000-4000-8000-000000000001");

    @Override
    public ScopeFilter currentScope() {
      return new ScopeFilter(
          ME, DataScope.OWN, Set.of("ADMIN"), ClassificationLevel.INTERNAL, Set.of());
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return true;
    }
  }
}
