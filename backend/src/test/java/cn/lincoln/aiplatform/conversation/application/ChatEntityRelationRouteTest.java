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
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.EntityRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.RelationRecallPort;
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
 * ★★ 实体/关系两路扩召回真的进了<b>对话链路</b>，不是只有命中测试页在用。
 *
 * <h3>它钉的是什么</h3>
 *
 * <p>{@code #378} 记的就是这件事的反面：命中测试页走四路（向量/全文/实体/关系），对话只走向量 + 全文两路，而所有闸门全绿 —— 于是 B1/B3
 * 那组读数测的根本不是真实问答链路。
 *
 * <p>收口之后两条链路共用同一段策略，但"共用"这件事本身需要一个<b>能红</b>的判据：本类给两路各造一个"只从这一路返回某一段"的端口，看那一段会不会出现在对话的 {@code refs}
 * 里。
 *
 * <p>★ 判定方式与 {@code ChatQaRouteTest} 同款：全文路只命中 {@link #SEGMENT_TEXT}，扩召回那一路返回的那个分段<b>没有别的来路</b> ⇒
 * 它出现在 {@code refs} 里只可能来自扩召回；把端口换成空实现 ⇒ 它不出现。
 *
 * <h3>为什么不起容器</h3>
 *
 * <p>判据读的是"候选集合里有没有那一条"，与数据库、向量、HTTP 都无关。
 */
class ChatEntityRelationRouteTest {

  /** 会话域的 ownerId：RetrievalScope 的构造器要求 conversationId 非空时它也必须非空。 */
  private static final UUID SCOPE_OWNER = UUID.fromString("0d000000-0000-4000-8000-0000000000f1");

  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000f2");

  /** 全文路命中的分段。 */
  private static final UUID SEGMENT_TEXT = UUID.fromString("0f000000-0000-4000-8000-0000000000b1");

  /** ★ 只被关系扩召回命中的分段。 */
  private static final UUID SEGMENT_RELATION =
      UUID.fromString("0f000000-0000-4000-8000-0000000000b2");

  /** ★ 只被实体扩召回命中的分段。 */
  private static final UUID SEGMENT_ENTITY =
      UUID.fromString("0f000000-0000-4000-8000-0000000000b3");

  private static final ChatStreamProperties PROPERTIES =
      new ChatStreamProperties(300L, 5000L, 60L, 200);

  private static final String QUESTION = "财务资产部负责哪些预算事项";

  /** 出站闸门替身：显式声明"都允许"（缺省放行会让"没接"与"放行"读数一样）。 */
  private static final ModelEgressPort UNRESTRICTED_EGRESS =
      modelId -> ClassificationLevel.CONFIDENTIAL;

  @Test
  void relationOnlySegmentReachesTheReferences() {
    RecordingStore store = new RecordingStore();
    ChatService chatService =
        newChatService(store, emptyEntityPort(), relationPort(SEGMENT_RELATION));
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    List<String> segments = sink.referenceSegments();
    assertThat(segments)
        .as("★★ 关系扩召回必须把它的分段带进 refs —— 这一段只可能来自那一路")
        .contains(SEGMENT_RELATION.toString());
    assertThat(segments).as("★ 前置：全文那一路的命中也要在（否则上面的断言可能只是碰巧）").contains(SEGMENT_TEXT.toString());
  }

  @Test
  void entityOnlySegmentReachesTheReferences() {
    RecordingStore store = new RecordingStore();
    ChatService chatService =
        newChatService(store, entityPort(SEGMENT_ENTITY), emptyRelationPort());
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    List<String> segments = sink.referenceSegments();
    assertThat(segments)
        .as("★★ 实体扩召回必须把它的分段带进 refs —— 这一段只可能来自那一路")
        .contains(SEGMENT_ENTITY.toString());
    assertThat(segments).as("★ 前置：全文那一路照常在").contains(SEGMENT_TEXT.toString());
  }

  @Test
  void withoutTheRecallRoutesThoseSegmentsAreNotThere() {
    RecordingStore store = new RecordingStore();
    ChatService chatService = newChatService(store, emptyEntityPort(), emptyRelationPort());
    RecordingSink sink = new RecordingSink();

    chatService.streamTurn(store.prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);

    List<String> segments = sink.referenceSegments();
    assertThat(segments)
        .as("★★ 反证：两路都空 ⇒ 那两个分段不许出现（它们没有别的来路）")
        .doesNotContain(SEGMENT_RELATION.toString(), SEGMENT_ENTITY.toString());
    assertThat(segments).as("★ 但全文那一路照常（撤掉扩召回不该把整轮打空）").contains(SEGMENT_TEXT.toString());
  }

  // ────────────────────────── 装配 ──────────────────────────

  private static ChatService newChatService(
      RecordingStore store, EntityRecallPort entity, RelationRecallPort relation) {
    ChatService chatService =
        new ChatService(
            store,
            new FixedSearchPort(),
            new KnownScope(),
            // 模型侧几毫秒写完：本类只读 refs，不读时序
            (modelId, prompt, timeout, callback) -> {
              callback.onChunk("预算事项由财务资产部统一受理。");
              callback.onUsage(120, 40);
              callback.onEnd(ChatStreamPort.End.COMPLETE, null);
            },
            new ChatStopSignal(),
            PROPERTIES,
            UNRESTRICTED_EGRESS,
            null);
    return ChatServiceRetrieval.wireWithRecall(chatService, entity, relation);
  }

  // ────────────────────────── 替身 ──────────────────────────

  /**
   * 关系端口替身：{@code recall} 返回一段。
   *
   * <p>★ 必须重写 {@code recall} 而不是只写 {@code
   * segmentsWithRelations}：检索服务调的是前者（明细与召回同一次产出），只覆盖抽象方法会让这一路悄悄走默认实现、返回空 —— 那正是本类要防的那种静默。
   */
  private static RelationRecallPort relationPort(UUID segmentId) {
    return new RelationRecallPort() {
      @Override
      public List<UUID> segmentsWithRelations(UUID kbId, String question) {
        return List.of(segmentId);
      }

      @Override
      public Recall recall(UUID kbId, String question) {
        assertThat(kbId).as("★ 这一路是按库逐个问的，传进来的必须是共用库").isEqualTo(KB_ID);
        return new Recall(
            List.of(segmentId), List.of(new Relation("BELONGS_TO", "预算科", "财务资产部", segmentId)));
      }
    };
  }

  private static EntityRecallPort entityPort(UUID segmentId) {
    return new EntityRecallPort() {
      @Override
      public List<UUID> segmentsMentionedIn(UUID kbId, String question) {
        return List.of(segmentId);
      }

      @Override
      public Recall recall(UUID kbId, String question) {
        return new Recall(List.of(segmentId), List.of(new Entity("财务资产部", "ORG", 6)));
      }
    };
  }

  private static EntityRecallPort emptyEntityPort() {
    return new EntityRecallPort() {
      @Override
      public List<UUID> segmentsMentionedIn(UUID kbId, String question) {
        return List.of();
      }

      @Override
      public Recall recall(UUID kbId, String question) {
        return new Recall(List.of(), List.of());
      }
    };
  }

  private static RelationRecallPort emptyRelationPort() {
    return new RelationRecallPort() {
      @Override
      public List<UUID> segmentsWithRelations(UUID kbId, String question) {
        return List.of();
      }

      @Override
      public Recall recall(UUID kbId, String question) {
        return new Recall(List.of(), List.of());
      }
    };
  }

  /**
   * 检索端口替身：全文路只命中 {@link #SEGMENT_TEXT}，向量路空。
   *
   * <p>★ {@code embeddingModelByKb} 返回空 Map 而不是抛：混合模式下它会真被问到，抛会把这一轮打成零引用。
   */
  private static final class FixedSearchPort implements SegmentSearchPort {

    @Override
    public RetrievalScope retrievalScope(
        Collection<UUID> requested, UUID conversationId, boolean includePersonal) {
      // ★★ 必须尊重 conversationId（0.9.9 修）：它一非空就说明"这一轮是在会话里跑的"。
      //   这里曾经恒返回"只有共用库域"的 scope ⇒ 生产代码里 `!scope.hasConversationScope()`
      //   在测试里恒真，三路扩召回（实体 / 关系 / 问答对）在**真实问答里一次都没跑过**，
      //   而下面几条判据一直绿。漏洞是 0.9.9 用真栈的 scripts/qa_direct_check.py 抓出来的。
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

    @Override
    public Set<UUID> visibleAmong(Collection<UUID> candidateIds, RetrievalScope scope) {
      return Set.copyOf(candidateIds);
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      return segmentIds.stream().map(FixedSearchPort::card).toList();
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
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
          UUID.fromString("0d000000-0000-4000-8000-0000000000c1"),
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
  }

  /** 落库侧替身：本类不需要数据库，只要求"别在检索阶段炸"。 */
  private static final class RecordingStore implements ChatTurnStore {

    @Override
    public List<ChatMessage> historyBefore(
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
        String failHint) {}

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
