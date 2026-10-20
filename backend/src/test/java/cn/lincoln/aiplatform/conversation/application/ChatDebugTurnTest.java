package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@code #184}：调试那一轮<strong>复用正式问答的配置，但不落库</strong>。
 *
 * <h3>★★ 这三条断言各自的"能红点"</h3>
 *
 * <ul>
 *   <li>提示词里必须出现应用的角色设定与编号引用 ⇒ 调试不走另一条拼装路径；
 *   <li>{@code flush / finish} 的调用次数必须为 0 ⇒ 谁把落库顺手接进来，这里立刻红；
 *   <li>{@code tokens} 必须是 {@code null}（假模型没报用量）⇒ 不能把"没上报"写成 0（{@code #142}）。
 * </ul>
 *
 * <p>★ 真库上的"前后行数不变"由 {@code AssistantDebugHttpTest} 读；本类读的是"链路上没有落库调用"。
 */
class ChatDebugTurnTest {

  private static final UUID APP_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000d1");
  private static final UUID MODEL_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000d2");
  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000d3");
  private static final UUID SEGMENT_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000d4");
  private static final String QUESTION = "差旅住宿标准";

  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  @Test
  void debugUsesRolePromptAndRefsButNeverPersistsAnything() {
    RecordingStore store =
        new RecordingStore(
            new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 5, NoRefStrategy.FREE_ANSWER));
    AtomicReference<String> promptSeen = new AtomicReference<>();
    AtomicInteger modelCalls = new AtomicInteger();

    ChatService service =
        service(
            store,
            List.of(new SegmentSearchPort.Scored(SEGMENT_ID, 0.90)),
            (modelId, prompt, timeout, callback) -> {
              modelCalls.incrementAndGet();
              promptSeen.set(prompt);
              callback.onChunk("调试答案");
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            });

    ChatService.DebugAnswer answer = service.debugTurn(APP_ID, QUESTION);

    assertThat(answer.answer()).isEqualTo("调试答案");
    assertThat(answer.refs()).hasSize(1);
    assertThat(promptSeen.get())
        .as("★ 调试必须用应用角色设定 + 引用编号拼提示词（与正式轮同一个 promptOf）")
        .contains("你是法务助手")
        .contains("[1]");
    assertThat(answer.tokens()).as("★★ 假模型没有 onUsage ⇒ tokens 必须是 null（未知），不是 0").isNull();
    assertThat(modelCalls.get()).isEqualTo(1);
    assertThat(store.flushCalls).as("★ 调试不得 flush 落库").isZero();
    assertThat(store.finishCalls).as("★ 调试不得写终态").isZero();
  }

  @Test
  void debugHonoursRefuseWithoutCallingTheModel() {
    RecordingStore store =
        new RecordingStore(
            new RetrievalParams(RetrievalMode.FULLTEXT, 0.99, 5, NoRefStrategy.REFUSE));
    AtomicInteger modelCalls = new AtomicInteger();

    ChatService service =
        service(
            store,
            List.of(new SegmentSearchPort.Scored(SEGMENT_ID, 0.90)),
            (modelId, prompt, timeout, callback) -> {
              modelCalls.incrementAndGet();
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            });

    ChatService.DebugAnswer answer = service.debugTurn(APP_ID, QUESTION);

    assertThat(answer.refs()).as("前置：阈值 0.99 把唯一命中过滤掉").isEmpty();
    assertThat(answer.answer()).isEqualTo(ChatService.NO_REF_REFUSAL);
    assertThat(modelCalls.get()).as("★ REFUSE 时调试也不调用模型（与正式轮同一判定）").isZero();
  }

  /** `#185`：应用打开思考时，ChatService 必须把 true 交给端口（删掉这一参，本测试立刻红）。 */
  @Test
  void debugPassesApplicationThinkingToTheStreamPort() {
    RecordingStore store =
        new RecordingStore(
            new RetrievalParams(RetrievalMode.FULLTEXT, 0.01, 5, NoRefStrategy.FREE_ANSWER), true);
    RecordingThinkingPort port = new RecordingThinkingPort();

    service(store, List.of(new SegmentSearchPort.Scored(SEGMENT_ID, 0.90)), port)
        .debugTurn(APP_ID, QUESTION);

    assertThat(port.thinking).as("★ 应用 thinkingEnabled=true 必须一路传到模型端口").isTrue();
  }

  private static ChatService service(
      RecordingStore store, List<SegmentSearchPort.Scored> hits, ChatStreamPort model) {
    return new ChatService(
        store,
        new FixedSearchPort(hits),
        new KnownScope(),
        model,
        new ChatStopSignal(),
        ChatStreamProperties.defaults(),
        UNRESTRICTED_EGRESS);
  }

  private static final class RecordingThinkingPort implements ChatStreamPort {

    private boolean thinking;

    @Override
    public void stream(UUID modelId, String prompt, java.time.Duration timeout, Callback callback) {
      throw new AssertionError("应走带 thinking 的重载");
    }

    @Override
    public void stream(
        UUID modelId,
        String prompt,
        boolean thinking,
        java.time.Duration timeout,
        Callback callback) {
      this.thinking = thinking;
      callback.onChunk("调试答案");
      callback.onEnd(End.COMPLETE, null);
    }
  }

  private static final class FixedSearchPort implements SegmentSearchPort {

    private final List<Scored> hits;

    private FixedSearchPort(List<Scored> hits) {
      this.hits = hits;
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      // ★ 调试没有会话：conversationId 必须是 null（本判据顺带钉住"调试不属于任何会话"）
      if (conversationId != null) {
        throw new AssertionError("调试的检索域里不应出现会话 id，实际收到 " + conversationId);
      }
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
                      "差旅住宿标准章节",
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
      throw new UnsupportedOperationException("本类只读全文");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("本类只读全文");
    }
  }

  private static final class KnownScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0a000000-0000-4000-8000-0000000000e1");

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

  private static final class RecordingStore implements ChatTurnStore {

    private final RetrievalParams params;
    private final boolean thinkingEnabled;

    private int flushCalls;
    private int finishCalls;

    private RecordingStore(RetrievalParams params) {
      this(params, false);
    }

    private RecordingStore(RetrievalParams params, boolean thinkingEnabled) {
      this.params = params;
      this.thinkingEnabled = thinkingEnabled;
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepare(
        UUID conversationId, UUID appId, String question, UUID userId) {
      throw new UnsupportedOperationException("本类只测调试；正式轮不得被调用");
    }

    @Override
    public ChatTurnWriter.PreparedTurn prepareDebug(UUID appId, String question) {
      UUID assistantMessageId = UUID.randomUUID();
      return new ChatTurnWriter.PreparedTurn(
          // ★ 调试没有会话
          null,
          assistantMessageId,
          UUID.randomUUID(),
          "debug_" + assistantMessageId,
          MODEL_ID.toString(),
          MODEL_ID,
          List.of(KB_ID),
          false,
          thinkingEnabled,
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
    public List<ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      throw new UnsupportedOperationException("本类不测多轮");
    }

    @Override
    public int flush(UUID messageId, String fullTextSoFar, int persistedChars) {
      flushCalls++;
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
      finishCalls++;
    }
  }
}
