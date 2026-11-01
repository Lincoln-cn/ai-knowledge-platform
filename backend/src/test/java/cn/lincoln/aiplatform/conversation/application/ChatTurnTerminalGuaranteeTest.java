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
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★★ <strong>一轮问答必须有终态</strong>：中途抛异常也一样（2026-10-29 那次「问答卡住、没有任何输出」的回归用例）。
 *
 * <h3>它钉的是什么</h3>
 *
 * <p>当时的现象：库里两条助手消息停在 {@code GENERATING}、{@code updated_at == created_at}（一次都没被写过）、 界面无任何输出也无任何错误，而
 * ollama 的日志证明 <strong>上游 200 且答完了</strong>。线程转储里 {@code chat-model-*} / {@code chat-heartbeat-*}
 * 一个都没有 —— 线程早退了，状态没落。
 *
 * <p>代码上的洞：{@code ChatService#streamTurnWithTerminalGate} 从 {@code assembler.begin()} 到 {@code
 * awaitTerminal} 这一段<strong>没有 try</strong>，终态的唯一出口是 {@code awaitTerminal}。任何异常越过那一行，就等于
 * "这一轮没有终态"：异常会一路逃到 {@code ChatStreamAssembler.streamOf} 的 {@code catch (Throwable)}，
 * 而那里因为"响应已提交"只记一条日志就不再抛 —— 于是<strong>库里不收尾、界面不报错</strong>。
 *
 * <h3>两条判据（以及它们的反证）</h3>
 *
 * <ol>
 *   <li>本轮起跑线上抛 {@link IllegalStateException}（读历史时数据库那侧挂了）⇒ {@code streamTurn}
 *       <strong>不抛</strong>，且 {@code ChatTurnStore#finish} 被调<strong>恰好一次</strong>、路径是 {@code
 *       DISCONNECT}、原因里写着 「服务端内部错误」。★ 反证：把 {@code streamTurnWithTerminalGate} 里那个外层 {@code try} 去掉
 *       ⇒ 本用例红在"抛出了 IllegalStateException"且 {@code finishCount == 0}。
 *   <li>写事件时就断线（{@link UncheckedIOException}）⇒ 同样收尾一次，但原因是「连接中断」而<strong>不是</strong> 「服务端内部错误」。★
 *       反证：把 {@code catch (UncheckedIOException)} 换成并入 {@code catch (Throwable)} ⇒
 *       本用例红在"客户端断开被记成了服务端内部错误"（{@code #224} 要求这两档文案不许混）。
 * </ol>
 *
 * <h3>★ 为什么不拿"检索抛异常"当注入点（实测得知，别再改回去）</h3>
 *
 * <p>第一版正是那么写的，跑出来 {@code COMPLETE}：检索失败**早就**被 {@code ChatService#retrieve} 自己兜住了 —— 它 {@code
 * catch (RuntimeException)} 后打一条 {@code 检索失败，本轮以'零引用'继续：…} 的 WARN，然后照常回答。
 * 也就是说"检索炸了"在这条链路上<strong>本来就有终态</strong>，拿它当注入点等于什么也没钉住。 现在选的是 {@code startModelThread} 里读历史那一行：它在
 * {@code try} 内、又不在任何就地兜底里， 正是"异常逃出去就再也不收尾"的那个形状。
 */
class ChatTurnTerminalGuaranteeTest {
  /** 与 {@code ChatRetrievalHeartbeatTest} 同一组压缩时间：本类不读时序，只要它们能通过构造校验。 */
  private static final ChatStreamProperties PROPERTIES =
      new ChatStreamProperties(300L, 1200L, 60L, 200);

  /** 出站闸门替身：显式声明"都允许"（与同族用例同一条纪律：不写缺省放行）。 */
  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  private static final String QUESTION = "差旅住宿标准";

  @Test
  @DisplayName("读历史时抛异常（数据库那侧挂了）：仍然落一次终态，且不往外抛")
  void aThrowingHistoryReadStillEndsTheTurnWithExactlyOneTerminalState() {
    RecordingStore store = new RecordingStore(true);
    RecordingSink sink = new RecordingSink();
    ChatService chatService = chatService(store, new IdleSearchPort());

    assertThatCode(
            () ->
                chatService.streamTurn(
                    store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink))
        .as("★ 异常不许穿出 streamTurn：穿出去就没有人再落终态了")
        .doesNotThrowAnyException();

    assertThat(store.finishCount()).as("★★ 终态必须落一次（改之前这里是 0 —— 消息永久停在 GENERATING）").isEqualTo(1);
    assertThat(store.lastPath()).isEqualTo(StreamTerminationPath.DISCONNECT);
    assertThat(store.lastReason())
        .as("★ 原因要写清是服务端内部错误：别让用户照着「检查模型配置」去查一个代码缺陷")
        .contains("服务端内部错误")
        .contains("IllegalStateException")
        .contains("历史读取炸了");

    assertThat(sink.done().status())
        .as("★ 用户那一侧收到的终态是 FAILED，不是「生成完了」")
        .isEqualTo(StreamStatus.FAILED);
    assertThat(sink.kinds()).as("done 仍然是最后一条（契约：恰好一次终态）").endsWith(EventKind.DONE);
    assertThat(sink.errorReason()).as("error 帧与库里那份原因同源（#224）").contains("服务端内部错误");
  }

  @Test
  @DisplayName("写事件时断线：同样落一次终态，但原因是「连接中断」而不是服务端内部错误")
  void aBrokenStreamStillEndsTheTurnAndKeepsTheDisconnectWording() {
    RecordingStore store = new RecordingStore(false);
    ChatService chatService = chatService(store, new IdleSearchPort());
    ChatService.EventSink gone = new FailingSink();

    assertThatCode(
            () ->
                chatService.streamTurn(
                    store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, gone))
        .as("断线是预期事件：不许把 UncheckedIOException 抛到容器那层")
        .doesNotThrowAnyException();

    assertThat(store.finishCount()).as("★★ 断线也必须落一次终态").isEqualTo(1);
    assertThat(store.lastPath()).isEqualTo(StreamTerminationPath.DISCONNECT);
    assertThat(store.lastReason())
        .as("★ 客户端断开不许被写成「服务端内部错误」（#224：两档文案不许混）")
        .doesNotContain("服务端内部错误")
        .doesNotContain("生成失败");
  }

  private static ChatService chatService(ChatTurnStore store, SegmentSearchPort search) {
    return new ChatService(
        store,
        search,
        new KnownScope(),
        (modelId, prompt, timeout, callback) -> {
          callback.onChunk("差旅住宿标准为每人每天500元。");
          callback.onUsage(120, 40);
          callback.onEnd(ChatStreamPort.End.COMPLETE, null);
        },
        new ChatStopSignal(),
        PROPERTIES,
        UNRESTRICTED_EGRESS);
  }

  // ────────────────────────── 替身 ──────────────────────────

  /** 检索端口替身：零命中、立即返回（本类两条用例都不关心检索结果）。 */
  private static final class IdleSearchPort implements SegmentSearchPort {

    private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e2");

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      return RetrievalScope.shared(List.of(KB_ID));
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB_ID);
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
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

  /** 范围端口替身：身份正常（于是检索会真的被调用）。 */
  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0d000000-0000-4000-8000-0000000000e2");

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

  /** 事件出口替身：全部记下来（第一条用例要读 error / done）。 */
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

    private String errorReason() {
      StreamEvent.StreamErrorEvent error =
          (StreamEvent.StreamErrorEvent)
              events.stream()
                  .filter(event -> event.kind() == EventKind.ERROR)
                  .reduce((first, second) -> second)
                  .orElseThrow(() -> new AssertionError("出口上没有收到 error 帧"));
      return error.reason();
    }
  }

  /** 事件出口替身：一写就断（模拟客户端已经走了）。 */
  private static final class FailingSink implements ChatService.EventSink {

    @Override
    public void onEvent(StreamEvent event) {
      throw new UncheckedIOException(new IOException("Broken pipe"));
    }
  }

  /**
   * 落库侧替身：只记"收尾落了几次、路径与原因是什么"（本类不需要数据库）。
   *
   * <p>★ {@code multiTurn} 决定会不会走到"读历史"那一行 —— 第一条用例靠它把异常注入在 {@code startModelThread}
   * 里、又不在任何就地兜底之内的位置上。
   */
  private static final class RecordingStore implements ChatTurnStore {

    private final boolean multiTurn;

    private int finishCount;
    private StreamTerminationPath lastPath;
    private String lastReason;

    private RecordingStore(boolean multiTurn) {
      this.multiTurn = multiTurn;
    }

    @Override
    public List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      // ★ 只有 multiTurn 的那条用例会到这里；它就是"起跑线上炸一次"的注入点
      throw new IllegalStateException("历史读取炸了（数据库那侧不可用）");
    }

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
          List.of(),
          multiTurn,
          false,
          null,
          null,
          List.of(),
          null);
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
      lastPath = path;
      lastReason = failReason;
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }

    @Override
    public String questionOf(UUID userMessageId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }

    private int finishCount() {
      return finishCount;
    }

    private StreamTerminationPath lastPath() {
      return lastPath;
    }

    private String lastReason() {
      return lastReason;
    }
  }
}
