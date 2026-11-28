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

  /** 会话域的 ownerId：RetrievalScope 的构造器要求 conversationId 非空时它也必须非空。 */
  private static final UUID SCOPE_OWNER = UUID.fromString("0d000000-0000-4000-8000-0000000000f1");

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

  /**
   * ★★ 个人库里的问答对也要扩得到（2026-11-28 第二次修）。
   *
   * <p>用户自己建的库是 {@code PRIVATE} + {@code owner_id = 自己} ⇒ 走 {@code personalKbId} 那一支。 三路扩召回曾经只遍历
   * {@code sharedKbIds()}，于是「给自己库建的问答对」在对话里一条都扩不到， 而命中测试（传显式 kbIds，被归成共用库）查得到 —— 同一句问话两个入口两个结果。
   */
  @Test
  void personalKnowledgeBaseIsRecalledToo() {
    RecordingStore store = new RecordingStore();
    ChatService chatService =
        newChatService(
            store,
            new FixedQaPort(SEGMENT_QA),
            (modelId, prompt, timeout, callback) -> {
              callback.onChunk("预算事项由财务资产部统一受理。");
              callback.onUsage(120, 40);
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            },
            true);
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    assertThat(sink.referenceSegments())
        .as("★★ 个人库里的出处分段也必须进 refs —— 这一条红了就是扩召回又只查了共用库")
        .contains(SEGMENT_QA.toString());
  }

  @Test
  void directAnswerIsReturnedAsIsWithoutCallingTheModel() {
    RecordingStore store = new RecordingStore();
    ChatService chatService =
        newChatService(
            store,
            new FixedQaPort(null, true),
            (modelId, prompt, timeout, callback) -> {
              throw new AssertionError("★★ 直答型问答对命中时不许调用模型 —— 这条红了就是直答分支没生效");
            });
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    assertThat(sink.text()).as("★★ 直答：正文必须是问答对的答案原文（不是模型的、也不是拒答文案）").contains("预算事项由财务资产部统一受理。");
    assertThat(sink.done().status())
        .as("★ 直答也走完整收尾（GENERATED），不是一条特殊终态")
        .isEqualTo(StreamStatus.GENERATED);
    assertThat(store.finishCount()).as("★ 收尾照常落库一次").isEqualTo(1);
  }

  private static ChatService newChatService(RecordingStore store, QaRecallPort qaRecallPort) {
    return newChatService(
        store,
        qaRecallPort,
        // ★ 模型侧几毫秒写完：本类只读 refs，不读时序
        (modelId, prompt, timeout, callback) -> {
          callback.onChunk("预算事项由财务资产部统一受理。");
          callback.onUsage(120, 40);
          callback.onEnd(ChatStreamPort.End.COMPLETE, null);
        });
  }

  private static ChatService newChatService(
      RecordingStore store, QaRecallPort qaRecallPort, ChatStreamPort model) {
    return newChatService(store, qaRecallPort, model, false);
  }

  private static ChatService newChatService(
      RecordingStore store, QaRecallPort qaRecallPort, ChatStreamPort model, boolean personalOnly) {
    ChatService chatService =
        new ChatService(
            store,
            new FixedSearchPort(personalOnly),
            new KnownScope(),
            model,
            new ChatStopSignal(),
            PROPERTIES,
            UNRESTRICTED_EGRESS,
            null);
    ChatServiceRetrieval.wire(chatService, qaRecallPort);
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
    private final boolean directAnswer;

    private FixedQaPort(UUID segmentId) {
      this(segmentId, false);
    }

    private FixedQaPort(UUID segmentId, boolean directAnswer) {
      this.segmentId = segmentId;
      this.directAnswer = directAnswer;
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
              directAnswer,
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

    private final boolean personalOnly;

    private FixedSearchPort() {
      this(false);
    }

    private FixedSearchPort(boolean personalOnly) {
      this.personalOnly = personalOnly;
    }

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      // ★★ 必须尊重 conversationId（0.9.9 修）：它一非空就说明"这一轮是在会话里跑的"。
      //   这里曾经恒返回"只有共用库域"的 scope ⇒ 生产代码里 `!scope.hasConversationScope()`
      //   在测试里恒真，三路扩召回（实体 / 关系 / 问答对）在**真实问答里一次都没跑过**，
      //   而下面几条判据一直绿。漏洞是 0.9.9 用真栈的 scripts/qa_direct_check.py 抓出来的。
      if (personalOnly) {
        // 个人库形态：共用户一支是空的，库落在 personalKbId 上。
        return new RetrievalScope(List.of(), KB_ID, conversationId, SCOPE_OWNER);
      }
      return conversationId == null
          ? RetrievalScope.shared(List.of(KB_ID))
          : new RetrievalScope(List.of(KB_ID), null, conversationId, SCOPE_OWNER);
    }

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      return List.of(KB_ID);
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      return List.of(new Scored(SEGMENT_TEXT, 0.9));
    }

    /**
     * ★ 检索现在走 {@code RetrievalService}，它在出结果前会做一次「域感知的可见性复核」。
     *
     * <p>这个假端口的可见性由 {@code retrievalScope} / {@code visibleKbIds} 决定，复核这一步对它恒真。
     * 不补这个方法的话默认实现会<strong>明确抛</strong>（那是为了防生产实现静默丢候选）， 而抛出来在对话侧表现为「零引用」，看起来像"检索坏了"。
     */
    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
      return Set.copyOf(candidateIds);
    }

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

    /** 正文 = 所有 delta 事件的文本拼接（直答时应当只有一段）。 */
    private String text() {
      StringBuilder text = new StringBuilder();
      for (StreamEvent event : events) {
        if (event instanceof StreamEvent.Delta delta) {
          text.append(delta.text());
        }
      }
      return text.toString();
    }

    private List<String> referenceSegments() {
      return events.stream()
          .filter(event -> event.kind() == EventKind.REFS)
          .map(event -> (StreamEvent.References) event)
          .flatMap(references -> references.refs().stream())
          .map(SourceRef::segmentId)
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
      finishCount++;
    }

    private int finishCount;

    private int finishCount() {
      return finishCount;
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
