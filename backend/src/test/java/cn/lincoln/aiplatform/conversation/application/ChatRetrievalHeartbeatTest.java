package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #142} ③：<strong>检索阶段必须有心跳帧</strong> —— 事件层读数（不起 Spring、不起容器）。
 *
 * <h3>★★ 它钉的是什么（以及为什么必须是一条新用例）</h3>
 *
 * <p>修之前，{@code ChatService#streamTurnWithTerminalGate} 是"先 {@code retrieve(...)} 再 {@code
 * startHeartbeatThread(run)}"⇒ <strong>检索期间没有任何心跳线程</strong>。而那条链路上的检索 <strong>不走</strong> {@code
 * RetrievalService} 的 5000ms 预算：长文档 / 多跳下它可以慢到几十秒， 那段时间里既没有业务帧、也没有注释帧 ⇒ 前端的闲置计时一路涨到阈值，
 * 而服务端以为自己一切正常（它确实一切正常 —— 只是没人告诉用户）。
 *
 * <p>★ 既有那条真栈心跳用例（{@code ChatStreamHttpTest#heartbeatIsAnSseCommentFrameNotABusinessEvent}）
 * 造的是<strong>生成阶段</strong>的间隙（假模型"第一块之前等 700ms"）， 所以它在"心跳根本不覆盖检索"这个缺陷面前<strong>照样绿</strong> ——
 * 这正是本仓最贵的那类洞： 判据看着覆盖了心跳，其实只覆盖了心跳的一半。
 *
 * <h3>★★ 判据的形状：不是"有心跳"，而是"心跳排在 refs 之前"</h3>
 *
 * <p>{@code refs} 是<strong>检索返回之后</strong>才被交进装配器的（{@code ChatService} 在 {@code retrieve} 之后调
 * {@code assembler.references(refs)}）。所以"存在一条心跳帧、且它排在 {@code refs}
 * 之前"这件事<strong>只可能</strong>发生在检索阶段 —— 不需要读时钟、也不依赖调度顺序。
 *
 * <h3>★★ 金丝雀（本批实测做过一次）</h3>
 *
 * <p>把 {@code startHeartbeatThread(run)} 挪回 {@code retrieve} 与 {@code references} 之后 （= 修之前的样子）⇒
 * 本用例第一条断言当场红（{@code kinds} 里一条 {@code HEARTBEAT} 都没有， 因为生成只要几毫秒、心跳线程还没来得及跑第一圈）。逐字读数见交付报告。
 */
class ChatRetrievalHeartbeatTest {

  /** 与两条真栈用例同一组压缩时间（契约那两个数由 {@code ChatStreamTimingTest} 单独钉住）。 */
  private static final long HEARTBEAT_INTERVAL_MS = 300L;

  private static final long IDLE_TIMEOUT_MS = 1200L;

  /**
   * 检索耗时（毫秒）。
   *
   * <p>★ 取 900：<strong>≥ 3 个心跳间隔</strong>（所以断言"至少 2 条"不靠调度运气）， 且<strong>&lt; 闲置超时</strong> 1200ms
   * —— 否则这一轮会先被超时收掉，读到的就不是"检索期间的心跳"了。
   *
   * <p>★★ 本批实测过一次 {@code RETRIEVAL_MS = 1500}（&gt; 闲置超时 1200ms）：那一轮<strong>一个 delta 都没有</strong>
   * （收尾落在 {@code IDLE_TIMEOUT}，断言红在"refs 之前没有 delta"那一行）。⇒ 读数证实："检索耗时本来就在闲置超时这个预算里" （时钟 {@code
   * lastBusinessFrameAtNanos} 从 {@code Run} 构造起算），本类取 900 正是为了避开那条路径、 让判据只读"检索期间的心跳"。
   */
  private static final long RETRIEVAL_MS = 900L;

  private static final ChatStreamProperties PROPERTIES =
      new ChatStreamProperties(HEARTBEAT_INTERVAL_MS, IDLE_TIMEOUT_MS, 60L, 200);

  private static final String QUESTION = "差旅住宿标准";

  @Test
  void heartbeatFramesArriveWhileRetrievalIsStillRunning() {
    SlowSearchPort search = new SlowSearchPort(RETRIEVAL_MS);
    RecordingStore store = new RecordingStore();
    ChatService chatService =
        new ChatService(
            store,
            search,
            new KnownScope(),
            // ★ 模型侧只要"几毫秒写完"：于是 refs 之前的心跳只可能来自检索阶段（见类注释）
            (modelId, prompt, timeout, callback) -> {
              callback.onChunk("差旅住宿标准为每人每天500元。");
              callback.onUsage(120, 40);
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            },
            new ChatStopSignal(),
            PROPERTIES);
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    assertThat(search.retrievalStarted).as("★★ 前置：慢的是**检索**那一侧（否则这条用例证明不了任何与检索阶段有关的事）").isTrue();

    List<EventKind> kinds = sink.kinds();
    assertThat(kinds)
        .as(
            "★★ 判据③（#142）：检索期间必须有心跳帧 —— "
                + "把 startHeartbeatThread 挪回 retrieve 之后（修之前的样子）这一条立刻红："
                + "生成只要几毫秒，心跳线程根本来不及跑第一圈")
        .contains(EventKind.HEARTBEAT);

    int refs = kinds.indexOf(EventKind.REFS);
    assertThat(refs).as("★ 前置：这一轮确实产出了 refs 帧（没有它下面的比较没有意义）").isGreaterThanOrEqualTo(0);
    assertThat(kinds.indexOf(EventKind.HEARTBEAT))
        .as("★★ 心跳必须排在 refs **之前**：refs 是检索返回之后才交进装配器的 ⇒ 这条心跳只可能落在检索阶段")
        .isLessThan(refs);
    assertThat(kinds.subList(0, refs).stream().filter(EventKind.HEARTBEAT::equals).count())
        .as("★ 900ms 的检索 ÷ 300ms 的心跳间隔 ⇒ refs 之前至少 2 条（只断言 1 条的话，一次调度抖动就能让它假绿/假红）")
        .isGreaterThanOrEqualTo(2L);

    // ★ 心跳不许把这一轮搞坏：顺序保证与终态一个字都不改
    assertThat(kinds)
        .as("★ refs 仍然先于第一个 delta，且这一轮照常收在 done 上（心跳只是注释帧）")
        .startsWith(EventKind.META)
        .endsWith(EventKind.DONE);
    assertThat(kinds.indexOf(EventKind.REFS)).isLessThan(kinds.indexOf(EventKind.DELTA));
    assertThat(sink.done().status()).isEqualTo(StreamStatus.GENERATED);
    assertThat(store.finishCount()).as("★ 收尾照常落库一次").isEqualTo(1);
  }

  // ────────────────────────── 替身 ──────────────────────────

  /**
   * 检索端口替身：★ <strong>{@code retrievalScope} 里睡 {@link #RETRIEVAL_MS} 毫秒</strong>，其余照旧"零命中"。
   *
   * <p>★ 为什么睡在 {@code retrievalScope} 而不是 {@code fullText}：它是 {@code ChatService#retrieve} 的
   * <strong>第一个</strong>调用，睡在这里最贴近"检索整体很慢"的形状，也最容易读懂。
   *
   * <p>★ 没被用到的那些方法照旧<strong>抛</strong>（与 {@code ChatStreamEventRecordingTest} 的替身同一条纪律）：
   * 本类若哪天开始走别的检索路，应当红而不是安静地拿到空结果。
   */
  private static final class SlowSearchPort implements SegmentSearchPort {

    private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e1");

    private final long retrievalMs;

    private volatile boolean retrievalStarted;

    private SlowSearchPort(long retrievalMs) {
      this.retrievalMs = retrievalMs;
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      retrievalStarted = true;
      sleep(retrievalMs);
      return RetrievalScope.shared(List.of(KB_ID));
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB_ID);
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      // ★ 零命中：本类读的是"检索**期间**有没有心跳"，命中与否是另一个变量
      return List.of();
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      throw new UnsupportedOperationException("零命中时没有展示信息要取");
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类不走向量路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类不走向量路");
    }
  }

  /** 范围端口替身：身份正常（{@code me} 与密级上限都在），于是 {@code retrieve} 会真的走到全文检索那一步。 */
  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0d000000-0000-4000-8000-0000000000e1");

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

  /** 事件出口替身：只记顺序，什么都不做（本类不注入写失败）。 */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    @Override
    public void onEvent(StreamEvent event) {
      events.add(event);
    }

    private List<EventKind> kinds() {
      return events.stream().map(StreamEvent::kind).toList();
    }

    private StreamEvent.StreamDoneEvent done() {
      return (StreamEvent.StreamDoneEvent)
          events.stream()
              .filter(event -> event.kind() == EventKind.DONE)
              .reduce((first, second) -> second)
              .orElseThrow(() -> new AssertionError("出口上没有收到 done 帧"));
    }
  }

  /** 落库侧替身：只记"收尾落库了几次"（本类不需要数据库）。 */
  private static final class RecordingStore implements ChatTurnStore {

    private int finishCount;

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
      finishCount++;
    }

    private int finishCount() {
      return finishCount;
    }
  }

  /** 不吞中断：被打断时恢复中断位并抛出（替身也要遵守"取消要能生效"）。 */
  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("检索替身被打断", interrupted);
    }
  }
}
