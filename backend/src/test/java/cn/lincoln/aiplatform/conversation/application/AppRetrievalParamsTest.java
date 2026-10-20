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
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
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
  private static final UUID MODEL_ID_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000a5");
  private static final UUID KB_ID_2 = UUID.fromString("0a000000-0000-4000-8000-0000000000a4");
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

  // ──────────────── #185：检索模式 VECTOR / FULLTEXT / HYBRID ────────────────

  @Test
  void vectorModeEmbedsQuestionAndUsesVectorSearch() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            vector,
            new FixedSearchPort(List.of(), List.of(hit(SEGMENT_1, 0.90))),
            List.of(0.1f, 0.2f));

    assertThat(run.embeddingCalls.get())
        .as("★ VECTOR：问题必须真的被向量化一次 —— 不调 embed 它就变成 0")
        .isEqualTo(1);
    assertThat(run.search.fullTextCalls.get()).as("★ VECTOR：不得走全文那一路").isZero();
    assertThat(run.refs())
        .as("向量一路返回 SEGMENT_1 ⇒ refs 必须含它")
        .extracting(SourceRef::segmentId)
        .contains(SEGMENT_1.toString());
  }

  @Test
  void fullTextModeDoesNotEmbed() {
    RetrievalParams fullText =
        new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(fullText, new FixedSearchPort(List.of(hit(SEGMENT_1, 0.90)), List.of()), List.of());

    assertThat(run.embeddingCalls.get())
        .as("★ FULLTEXT：不得向量化 —— 一旦它变成 1，说明 FULLTEXT 被误改走向量")
        .isZero();
    assertThat(run.search.vectorSearchCalls.get()).as("★ FULLTEXT：不得调用向量检索").isZero();
    assertThat(run.refs()).extracting(SourceRef::segmentId).contains(SEGMENT_1.toString());
  }

  @Test
  void hybridModeUsesBothRoutesAndFuses() {
    RetrievalParams hybrid =
        new RetrievalParams(RetrievalMode.HYBRID, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            hybrid,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.20)), List.of(hit(SEGMENT_2, 0.90))),
            List.of(0.1f, 0.2f));

    assertThat(run.search.fullTextCalls.get()).as("★ HYBRID：全文那一路必须跑").isEqualTo(1);
    assertThat(run.embeddingCalls.get()).as("★ HYBRID：向量那一路也必须跑").isEqualTo(1);
    assertThat(run.search.vectorSearchCalls.get()).isEqualTo(1);
    assertThat(run.refs())
        .as("★ HYBRID：两路都参与 ⇒ refs 必须同时含两路各自的分段（融合后去重但两路都留）")
        .extracting(SourceRef::segmentId)
        .contains(SEGMENT_1.toString(), SEGMENT_2.toString());
  }

  @Test
  void hybridModeFallsBackToFullTextWhenVectorFails() {
    RetrievalParams hybrid =
        new RetrievalParams(RetrievalMode.HYBRID, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            hybrid,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.80)), List.of(hit(SEGMENT_2, 0.90))),
            List.of(0.1f, 0.2f),
            true,
            MODEL_ID);

    assertThat(run.search.fullTextCalls.get()).as("★ 全文那一路仍然要跑").isEqualTo(1);
    assertThat(run.embeddingCalls.get()).as("向量那一路确实尝试过").isEqualTo(1);
    assertThat(run.search.vectorSearchCalls.get()).as("向量化失败就不再查向量").isZero();
    assertThat(run.refs())
        .as("★★ 向量失败时只保留全文那一路；不许把整轮打成零引用")
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString());
  }

  @Test
  void vectorModeKeepsHealthyModelGroupsWhenOneGroupFails() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    FixedSearchPort search =
        new FixedSearchPort(
            List.of(),
            List.of(),
            false,
            List.of(KB_ID, KB_ID_2),
            Map.of(KB_ID, MODEL_ID, KB_ID_2, MODEL_ID_2),
            Map.of(KB_ID, List.of(hit(SEGMENT_1, 0.90)), KB_ID_2, List.of(hit(SEGMENT_2, 0.90))));
    Run run = run(vector, search, List.of(0.1f, 0.2f), true, MODEL_ID_2);

    assertThat(run.search.vectorSearchCalls.get()).as("★ 坏掉一个向量模型，另一个模型组仍要检索").isEqualTo(1);
    assertThat(run.refs())
        .as("★★ 分组隔离：坏组的失败不许让好组也零引用")
        .extracting(SourceRef::segmentId)
        .containsExactly(SEGMENT_1.toString());
  }

  @Test
  void vectorModeDoesNotQueryConversationScopeWithTheKnowledgeBaseVector() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    FixedSearchPort search = new FixedSearchPort(List.of(), List.of(hit(SEGMENT_1, 0.90)), true);

    Run run = run(vector, search, List.of(0.1f, 0.2f));

    assertThat(run.refs()).as("知识库那一路仍然要返回引用").isNotEmpty();
    assertThat(search.lastVectorScope).as("向量查询必须真的发生").isNotNull();
    assertThat(search.lastVectorScope.hasConversationScope())
        .as("★★ 向量查询不得带会话域 —— 会话分段没有库行，会拿错向量模型/维度表")
        .isFalse();
    assertThat(search.lastVectorScope.sharedKbIds()).as("摘会话域时不许把知识库也一起摘掉").containsExactly(KB_ID);
  }

  @Test
  void vectorModeWithoutEmbeddingPortReturnsNoRefsAndDoesNotFallBackToFullText() {
    RetrievalParams vector =
        new RetrievalParams(RetrievalMode.VECTOR, 0.0, 5, NoRefStrategy.FREE_ANSWER);
    Run run =
        run(
            vector,
            new FixedSearchPort(List.of(hit(SEGMENT_1, 0.90)), List.of(hit(SEGMENT_1, 0.90))),
            List.of(),
            false);

    assertThat(run.refs()).as("★ 未装配 EmbeddingPort ⇒ 零引用，绝不静默退回全文").isEmpty();
    assertThat(run.search.fullTextCalls.get())
        .as("★★ 向量失败不得退回全文那一路 —— fullText 一旦非 0，就说明发生了'静默降级'")
        .isZero();
    assertThat(run.embeddingCalls.get()).isZero();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Run(
      List<SourceRef> refs,
      RecordingSink sink,
      AtomicInteger modelCalls,
      AtomicInteger embeddingCalls,
      FixedSearchPort search) {}

  private static Run run(RetrievalParams params, List<SegmentSearchPort.Scored> hits) {
    return run(params, new FixedSearchPort(hits, List.of()), List.of(), true);
  }

  private static Run run(RetrievalParams params, FixedSearchPort search, List<Float> vector) {
    return run(params, search, vector, true, null);
  }

  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort) {
    return run(params, search, vector, wireEmbeddingPort, null);
  }

  private static Run run(
      RetrievalParams params,
      FixedSearchPort search,
      List<Float> vector,
      boolean wireEmbeddingPort,
      UUID failingEmbeddingModel) {
    List<SourceRef> captured = new ArrayList<>();
    RecordingStore store = new RecordingStore(params);
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
    AtomicInteger embeddingCalls = new AtomicInteger();
    if (wireEmbeddingPort) {
      service.setEmbeddingPort(
          new EmbeddingPort() {
            @Override
            public List<float[]> embed(UUID modelId, List<String> texts) {
              embeddingCalls.incrementAndGet();
              if (failingEmbeddingModel != null && failingEmbeddingModel.equals(modelId)) {
                throw new IllegalStateException("测试注入：向量模型未加载");
              }
              float[] v = new float[vector.size()];
              for (int i = 0; i < vector.size(); i++) {
                v[i] = vector.get(i);
              }
              return List.of(v);
            }
          });
    }
    RecordingSink sink = new RecordingSink();
    service.streamTurn(store.prepare(null, APP_ID, QUESTION, UUID.randomUUID()), QUESTION, sink);
    captured.addAll(sink.refs());
    return new Run(captured, sink, modelCalls, embeddingCalls, search);
  }

  private static SegmentSearchPort.Scored hit(UUID id, double score) {
    return new SegmentSearchPort.Scored(id, score);
  }

  /**
   * 检索端口：固定返回给定命中；`load` 给每条命中一个可组装的 Card。
   *
   * <p>★ 同时支持两路：fullText 返回 fullTextHits、vectorSearch 返回 vectorHits，各带一个调用计数器， 供 VECTOR / FULLTEXT
   * / HYBRID 三种模式断言"哪一路真的被跑了"。
   */
  private static final class FixedSearchPort implements SegmentSearchPort {

    private final List<Scored> fullTextHits;
    private final List<Scored> vectorHits;
    private final boolean conversationScope;
    private final List<UUID> kbIds;
    private final Map<UUID, UUID> embeddingModels;
    private final Map<UUID, List<Scored>> vectorHitsByKb;
    private RetrievalScope lastVectorScope;
    final AtomicInteger fullTextCalls = new AtomicInteger();
    final AtomicInteger vectorSearchCalls = new AtomicInteger();

    private FixedSearchPort(List<Scored> fullTextHits, List<Scored> vectorHits) {
      this(fullTextHits, vectorHits, false);
    }

    private FixedSearchPort(
        List<Scored> fullTextHits, List<Scored> vectorHits, boolean conversationScope) {
      this(
          fullTextHits,
          vectorHits,
          conversationScope,
          List.of(KB_ID),
          Map.of(KB_ID, MODEL_ID),
          Map.of(KB_ID, vectorHits));
    }

    private FixedSearchPort(
        List<Scored> fullTextHits,
        List<Scored> vectorHits,
        boolean conversationScope,
        List<UUID> kbIds,
        Map<UUID, UUID> embeddingModels,
        Map<UUID, List<Scored>> vectorHitsByKb) {
      this.fullTextHits = fullTextHits;
      this.vectorHits = vectorHits;
      this.conversationScope = conversationScope;
      this.kbIds = kbIds;
      this.embeddingModels = embeddingModels;
      this.vectorHitsByKb = vectorHitsByKb;
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      if (conversationScope) {
        return new RetrievalScope(
            kbIds,
            null,
            UUID.fromString("0a000000-0000-4000-8000-0000000000d1"),
            UUID.fromString("0a000000-0000-4000-8000-0000000000d2"));
      }
      return RetrievalScope.shared(kbIds);
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return kbIds;
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      fullTextCalls.incrementAndGet();
      return fullTextHits;
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
      return embeddingModels;
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      lastVectorScope = query.scope();
      vectorSearchCalls.incrementAndGet();
      UUID kbId = query.scope().sharedKbIds().getFirst();
      return vectorHitsByKb.getOrDefault(kbId, vectorHits);
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
