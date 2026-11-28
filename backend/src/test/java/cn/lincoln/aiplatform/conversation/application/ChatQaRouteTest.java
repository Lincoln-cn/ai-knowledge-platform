package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.EventKind;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.QaRecallPort;
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
 * ★★ 问答对（第五路）真的接进了<b>对话链路</b>，不是只有命中测试页在用。
 *
 * <h3>★★ 它钉的是什么</h3>
 *
 * <p>`#378` 的教训是：命中测试页看得见的东西，真实对话里可能根本没有 —— 四路 vs 两路， 而所有闸门全绿。所以"问答对接进检索"这件事，判据必须落在 `ChatService`
 * 上，不能落在 `/retrieval/test` 上。
 *
 * <p>★ 判定方式：一个分段<b>只</b>被问答对那一路命中（全文路返回的是另一个分段）， 于是它出现在 `refs` 里这件事，<b>只可能</b>来自 `withQaRoute`。撤掉端口
 * ⇒ 它不出现。 这是本类唯一的判据，也是它能红的原因。
 *
 * <h3>★ 为什么不起容器</h3>
 *
 * <p>本类读的是"候选集合里有没有那一条"，与数据库、向量、HTTP 都无关（同 {@code ChatRetrievalHeartbeatTest} 的口径：一个判据要的是一个真跑的
 * {@code ChatService}，不是一堆 mock）。
 */
class ChatQaRouteTest {

  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000f1");

  /** 全文路命中的分段。 */
  private static final UUID SEGMENT_TEXT = UUID.fromString("0f000000-0000-4000-8000-0000000000a1");

  /** ★ 只被问答对那一路命中的分段 —— 本类的判据就看它。 */
  private static final UUID SEGMENT_QA = UUID.fromString("0f000000-0000-4000-8000-0000000000a2");

  private static final ChatStreamProperties PROPERTIES =
      new ChatStreamProperties(300L, 5000L, 60L, 200);

  private static final String QUESTION = "财务资产部负责哪些预算事项";

  /** 出站闸门替身：显式声明"都允许"（理由同同族用例：缺省放行会让"没接"与"放行"读数一样）。 */
  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  @Test
  void qaOnlySegmentReachesTheReferencesWhenThePortIsWired() {
    RecordingStore store = new RecordingStore();
    ChatService chatService = newChatService(store, new FixedQaPort(SEGMENT_QA));
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    List<String> segments = sink.referenceSegments();
    assertThat(segments)
        .as("★★ 第五路（问答对）必须把它的出处分段带进 refs —— 这一条只可能来自 withQaRoute")
        .contains(SEGMENT_QA.toString());
    assertThat(segments).as("★ 前置：全文那一路的命中也要在（否则下面的对照没有意义）").contains(SEGMENT_TEXT.toString());
  }

  @Test
  void withoutThePortTheQaSegmentIsNotThere() {
    RecordingStore store = new RecordingStore();
    ChatService chatService = newChatService(store, null);
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    List<String> segments = sink.referenceSegments();
    assertThat(segments)
        .as("★★ 反证：不注入端口 ⇒ 这一路空转，那个分段不许出现（它没有别的来路）")
        .doesNotContain(SEGMENT_QA.toString());
    assertThat(segments).as("★ 但全文那一路照常（撤掉一路不该把整轮打空）").contains(SEGMENT_TEXT.toString());
  }

  private static ChatService newChatService(RecordingStore store, QaRecallPort qaRecallPort) {
    ChatService chatService =
        new ChatService(
            store,
            new FixedSearchPort(),
            new KnownScope(),
            // ★ 模型侧几毫秒写完：本类只读 refs，不读时序
            (modelId, prompt, timeout, callback) -> {
              callback.onChunk("预算事项由财务资产部统一受理。");
              callback.onUsage(120, 40);
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            },
            new ChatStopSignal(),
            PROPERTIES,
            UNRESTRICTED_EGRESS,
            null);
    // ★ 走 setter：与生产装配同一条路（这也是"可选注入"的形状本身）
    chatService.setQaRecall(qaRecallPort);
    return chatService;
  }

  // ────────────────────────── 替身 ──────────────────────────

  /**
   * 问答对端口替身：固定返回一条<b>有出处</b>的召回型命中。
   *
   * <p>★ 断言入参里带的是共用库那一路：会话域不该进这一路（问答对挂在知识库上）。
   */
  private static final class FixedQaPort implements QaRecallPort {

    private final UUID segmentId;

    private FixedQaPort(UUID segmentId) {
      this.segmentId = segmentId;
    }

    @Override
    public List<Match> recall(Query query) {
      assertThat(query.kbIds()).as("★ 这一路拿到的必须是共用库 id 集合（会话域不该进这里）").containsExactly(KB_ID);
      return List.of(
          new Match(
              UUID.randomUUID(),
              QUESTION,
              "预算事项由财务资产部统一受理。",
              segmentId,
              false,
              ClassificationLevel.INTERNAL,
              0.95));
    }
  }

  /**
   * 检索端口替身：全文路只命中 {@link #SEGMENT_TEXT}，<b>永远不</b>命中 {@link #SEGMENT_QA}。
   *
   * <p>★ 这正是判据成立的前提：那个分段唯一的来路是问答对那一路。
   */
  private static final class FixedSearchPort implements SegmentSearchPort {

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
      return List.of(new Scored(SEGMENT_TEXT, 0.9));
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return segmentIds.stream().map(FixedSearchPort::card).toList();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      // ★ 本类只走全文那一路：向量路给空集（不是抛）—— 混合模式下它会真被问到，抛会把这一轮打死
      return Map.of();
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      return List.of();
    }

    private static Card card(UUID segmentId) {
      return new Card(
          segmentId,
          "合同管理制度",
          "预算事项由财务资产部统一受理。",
          "合同管理制度.pdf",
          "合同管理制度",
          UUID.fromString("0d000000-0000-4000-8000-0000000000a1"),
          KB_ID,
          ClassificationLevel.INTERNAL);
    }
  }

  /** 范围端口替身：身份正常（`me` 与密级上限都在）。 */
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

  /** 事件出口替身：记顺序，并暴露 refs 帧里的分段 id。 */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    @Override
    public void onEvent(StreamEvent event) {
      events.add(event);
    }

    private List<String> referenceSegments() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.REFS)
          .map(event -> (StreamEvent.References) event)
          .flatMap(references -> references.refs().stream())
          .map(SourceRef::segmentId)
          .toList();
    }

    @SuppressWarnings("unused")
    private StreamEvent.StreamDoneEvent done() {
      return (StreamEvent.StreamDoneEvent)
          events.stream()
              .filter(event -> event.kind() == EventKind.DONE)
              .reduce((first, second) -> second)
              .orElseThrow(() -> new AssertionError("出口上没有收到 done 帧"));
    }
  }

  /** 落库侧替身：本类不需要数据库，只要求"别在检索阶段炸"。 */
  private static final class RecordingStore implements ChatTurnStore {

    @Override
    public List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      return List.of();
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
          false,
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
        Integer firstTokenMs,
        String failReason,
        String failHint) {
      // 本类只读 refs，落库内容不参与判定
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }

    @Override
    public String questionOf(UUID userMessageId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }
  }
}
