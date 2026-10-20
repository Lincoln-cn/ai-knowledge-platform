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
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@code #185}：应用级检索参数必须真正进入问答链路。
 *
 * <h3>★★ 每条断言都能红在哪</h3>
 *
 * <ul>
 *   <li>阈值 / TopN：把 {@code ChatService.retrieve} 改回读平台设置（{@code policyResolver} 为 null ⇒ 阈值 0 /
 *       TopN 5）⇒ 两条读数都变成 3，断言立刻红；
 *   <li>无引用拒答：把 {@code refuses(...)} 那个分支删掉 ⇒ 模型会被调用、正文变成假模型那句答案，两条断言都红。
 * </ul>
 *
 * <p>★ 本类不碰数据库、不碰 Spring：它读的是"应用参数有没有被消费"这件事，与落库无关。
 */
class AppRetrievalParamsTest {

  private static final UUID APP_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a1");
  private static final UUID MODEL_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a2");
  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000a3");
  private static final UUID SEGMENT_1 = UUID.fromString("0a000000-0000-4000-8000-0000000000b1");
  private static final UUID SEGMENT_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000b2");
  private static final UUID SEGMENT_3 = UUID.fromString("0a000000-0000-4000-8000-0000000000b3");
  private static final String QUESTION = "差旅住宿标准";

  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  @Test
  void thresholdFromTheAppChangesTheNumberOfRefs() {
    RetrievalParams strict =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.FREE_ANSWER);
    RetrievalParams loose =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 5, NoRefStrategy.FREE_ANSWER);

    Run strictRun =
        run(strict, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));
    Run looseRun =
        run(loose, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));

    assertThat(strictRun.refs()).as("阈值 0.99 ⇒ 三条命中（0.90 / 0.50 / 0.20）全部低于阈值 ⇒ 零引用").isEmpty();
    assertThat(looseRun.refs())
        .as("阈值 0.01 ⇒ 三条都进上下文（★ 若 ChatService 仍读平台设置，这里会变成 0 / 3 与 0 / 3 一样）")
        .hasSize(3);
  }

  @Test
  void topNFromTheAppCapsTheNumberOfRefs() {
    RetrievalParams topTwo =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 2, NoRefStrategy.FREE_ANSWER);

    Run run =
        run(topTwo, List.of(hit(SEGMENT_1, 0.90), hit(SEGMENT_2, 0.50), hit(SEGMENT_3, 0.20)));

    assertThat(run.refs())
        .as("应用 TopN=2 ⇒ 只留 2 条；改回平台 TopN（5）这里会变成 3")
        .hasSize(2)
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString(), SEGMENT_2.toString());
  }

  @Test
  void refuseStrategyDoesNotCallTheModelWhenThereAreNoRefs() {
    RetrievalParams refuse =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.REFUSE);

    Run run = run(refuse, List.of(hit(SEGMENT_1, 0.90)));

    assertThat(run.refs()).as("前置：阈值把唯一命中过滤掉 ⇒ 零引用").isEmpty();
    assertThat(run.modelCalls.get()).as("★★ REFUSE：零引用时**不得**调用模型 —— 删掉那个分支它就会变成 1").isZero();
    assertThat(run.sink.deltas())
        .as("零引用拒答：正文必须是固定拒答文案，而不是假模型那句自由回答")
        .containsExactly(ChatService.NO_REF_REFUSAL);
    assertThat(run.sink.done().status())
        .as("拒答仍是一次正常收尾（GENERATED），不是失败")
        .isEqualTo(StreamStatus.GENERATED);
  }

  @Test
  void freeAnswerStrategyStillCallsTheModelWhenThereAreNoRefs() {
    RetrievalParams free =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.FREE_ANSWER);

    Run run = run(free, List.of(hit(SEGMENT_1, 0.90)));

    assertThat(run.refs()).isEmpty();
    assertThat(run.modelCalls.get())
        .as("★ 同一读数下的对照：FREE_ANSWER 必须调用模型 —— 否则上面的'拒答没调模型'证明不了是策略生效")
        .isEqualTo(1);
    assertThat(run.sink.deltas()).containsExactly("假模型答案");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Run(List<SourceRef> refs, RecordingSink sink, AtomicInteger modelCalls) {}

  private static Run run(RetrievalParams params, List<SegmentSearchPort.Scored> hits) {
    List<SourceRef> captured = new ArrayList<>();
    RecordingStore store = new RecordingStore(params);
    SegmentSearchPort search = new FixedSearchPort(hits);
    AtomicInteger modelCalls = new AtomicInteger();
    ChatStreamPort model =
        (modelId, prompt, timeout, callback) -> {
          modelCalls.incrementAndGet();
          callback.onChunk("假模型答案");
          callback.onUsage(10, 5);
          callback.onEnd(ChatStreamPort.End.COMPLETE, null);
        };
    ChatService service =
        new ChatService(
            store,
            search,
            new KnownScope(),
            model,
            new ChatStopSignal(),
            ChatStreamProperties.defaults(),
            UNRESTRICTED_EGRESS);
    RecordingSink sink = new RecordingSink();
    service.streamTurn(store.prepare(null, APP_ID, QUESTION, UUID.randomUUID()), QUESTION, sink);
    captured.addAll(sink.refs());
    return new Run(captured, sink, modelCalls);
  }

  private static SegmentSearchPort.Scored hit(UUID id, double score) {
    return new SegmentSearchPort.Scored(id, score);
  }

  /** 检索端口：固定返回给定命中；`load` 给每条命中一个可组装的 Card。 */
  private static final class FixedSearchPort implements SegmentSearchPort {

    private final List<Scored> hits;

    private FixedSearchPort(List<Scored> hits) {
      this.hits = hits;
    }

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
      return hits;
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return segmentIds.stream()
          .map(
              id ->
                  new Card(
                      id,
                      "分段 " + id,
                      "差旅住宿标准为每人每天500元",
                      "差旅制度",
                      "制度库",
                      UUID.randomUUID(),
                      KB_ID,
                      ClassificationLevel.INTERNAL))
          .toList();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("本类只读全文那一路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类只读全文那一路");
    }
  }

  /** 范围端口：身份正常（me + 密级上限都在），于是 retrieve 会真的走到全文检索。 */
  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0a000000-0000-4000-8000-0000000000c1");

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

  /** 落库侧替身：只提供 prepare（本类不测落库）。 */
  private static final class RecordingStore implements ChatTurnStore {

    private final RetrievalParams params;

    private RecordingStore(RetrievalParams params) {
      this.params = params;
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepare(
        UUID conversationId, UUID appId, String question, UUID userId) {
      UUID assistantMessageId = UUID.randomUUID();
      return new ChatTurnWriter.PreparedTurn(
          UUID.randomUUID(),
          assistantMessageId,
          UUID.randomUUID(),
          "trace_" + assistantMessageId,
          MODEL_ID.toString(),
          MODEL_ID,
          List.of(KB_ID),
          false,
          false,
          APP_ID,
          "你是法务助手",
          params);
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
      throw new UnsupportedOperationException("本类不测重新生成");
    }

    @Override
    public String questionOf(UUID userMessageId) {
      throw new UnsupportedOperationException("本类不测重新生成");
    }

    @Override
    public List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      throw new UnsupportedOperationException("本类不测多轮");
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
        String failHint) {}
  }

  /** 事件出口：记 refs / delta / done。 */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    @Override
    public void onEvent(StreamEvent event) {
      events.add(event);
    }

    private List<SourceRef> refs() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.REFS)
          .map(event -> (StreamEvent.References) event)
          .findFirst()
          .map(StreamEvent.References::refs)
          .orElseThrow(() -> new AssertionError("出口上没有收到 refs 帧"));
    }

    private List<String> deltas() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.DELTA)
          .map(event -> ((StreamEvent.Delta) event).text())
          .toList();
    }

    private StreamEvent.StreamDoneEvent done() {
      return (StreamEvent.StreamDoneEvent)
          events.stream()
              .filter(event -> event.kind() == EventKind.DONE)
              .reduce((first, second) -> second)
              .orElseThrow(() -> new AssertionError("出口上没有收到 done 帧"));
    }
  }
}
