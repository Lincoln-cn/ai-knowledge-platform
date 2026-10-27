package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ChatStopSignal;
import cn.lincoln.aiplatform.conversation.application.ChatTurnStore;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
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
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #72} 判据 1：<strong>断言送出的 payload</strong>，而不是"结果看起来没泄漏"。
 *
 * <h3>它补的是什么</h3>
 *
 * <p>平台里此前<strong>没有任何闸门管"数据被发到哪里"</strong>：{@code ai_model.supplier} 有 {@code EXTERNAL_API}
 * 这个取值，而密级只用来判"我能不能看"。于是"一份 {@code SECRET} 分段被发去外部模型 API" 这件事<strong>既没有拦截、也没有留痕、也不报错</strong> ——
 * 本项目头号病 {@code silent-failure} 的形状。
 *
 * <p>★ 根因是把<strong>可见性</strong>与<strong>数据流向</strong>当成了同一件事：
 * 一个高密级分段可以合法地被本地模型读，但绝不能被发去公网模型。两者<strong>正交</strong>。
 *
 * <h3>★★ 判据的形状（照 issue 判据 1 的原文写）</h3>
 *
 * <p>"库内一份 {@code SECRET} 分段 + 一个 allowed ceiling = {@code INTERNAL} 的外部模型 ⇒
 * 提问时该分段<strong>不得出现在送往模型的上下文里</strong>（断言送出的 payload，而不是结果看起来没泄漏）"
 *
 * <p>★ 所以本类<strong>拦在模型端口上看 prompt</strong>：那个替身收到的 {@code prompt} 就是"送出的 payload"。
 * 断言它<strong>不含</strong> SECRET 正文、且<strong>含</strong> PUBLIC 正文 —— ★
 * 后半句是关键：只断言"不含"的话，一个"一条引用都没给"的坏实现也能绿。
 *
 * <h3>三条判据各对应一条断言（都能红）</h3>
 *
 * <ol>
 *   <li>外部模型 + {@code INTERNAL} 上限 ⇒ SECRET 分段不进 payload（本类主判据）；
 *   <li>★ <b>本地模型不受影响</b>（{@code #72} 判据 3）：同一条 SECRET 分段换成 {@code LOCAL_INFERENCE} ⇒
 *       它<strong>应当</strong>进 payload —— 防"一刀切禁用"式的过度实现；
 *   <li>★ <b>fail-closed</b>：出口上限取不到（{@code null}）⇒ 一条引用都不给 （不许把"认不出来"读成"没有限制"）。
 * </ol>
 *
 * <h3>★ 本类不覆盖什么（如实写）</h3>
 *
 * <p>它<b>不</b>验证 `app.egress.external-api-ceiling` 这个配置真的被读到（那由 {@code ModelEgressPropertiesTest}
 * 覆盖），也<b>不</b>覆盖审计留痕（{@code #72} 判据 4 尚未落地）。
 */
class ChatEgressGateTest {

  private static final String PUBLIC_TEXT = "差旅住宿标准为每人每天500元。";
  private static final String SECRET_TEXT = "内部并购标的：某某公司，作价九亿。";
  private static final String QUESTION = "差旅住宿标准";
  private static final UUID MODEL_ID = UUID.fromString("0c000000-0000-4000-8000-0000000000e1");

  @Test
  void secretSegmentNeverReachesAnExternalModelWithLowerCeiling() {
    // ── 场景：用户的密级上限是 CONFIDENTIAL（**他读得到** SECRET 那一条），
    //    而目标模型是外部 API、出口上限只有 INTERNAL ⇒ SECRET 那一条必须被挡下
    Harness harness = new Harness(ClassificationLevel.INTERNAL);

    harness.run();

    String prompt = harness.promptSentToModel();
    assertThat(prompt)
        .as("★★ #72 判据 1：SECRET 分段不得出现在送往模型的上下文里 —— " + "断言的是**送出的 payload**，不是『结果看起来没泄漏』")
        .doesNotContain(SECRET_TEXT);
    assertThat(prompt)
        .as("★ 这一半同样是判据：PUBLIC 那条**必须仍在** —— " + "否则一个『一条引用都不给』的坏实现也能让上面那条绿")
        .contains(PUBLIC_TEXT);
  }

  @Test
  void localInferenceStillSeesTheSecretSegment() {
    // ── #72 判据 3：本地推理不出网 ⇒ 不受出口上限约束。
    //    ★ 没有这一条，最省事的"实现"就是把所有外部模型一律禁用 ——
    //      那会让"本地模型也读不到本该读得到的东西"，而它在读数上与"闸门正常工作"一模一样。
    Harness harness = new Harness(ClassificationLevel.CONFIDENTIAL);

    harness.run();

    assertThat(harness.promptSentToModel())
        .as("★ 本地模型 + 足够的上限 ⇒ SECRET 那一条**应当**进 payload（防一刀切过度实现）")
        .contains(SECRET_TEXT);
  }

  @Test
  void unknownEgressCeilingFailsClosed() {
    // ── ★ 出口上限取不到（null）⇒ 一条引用都不给。
    //    ★ 不许把它读成"没有限制"：那正是 silent-failure 的形状。
    Harness harness = new Harness(null);

    harness.run();

    String prompt = harness.promptSentToModel();
    assertThat(prompt)
        .as("★ fail-closed：认不出目标模型的出口上限时，连 PUBLIC 那条也不得送出")
        .doesNotContain(PUBLIC_TEXT)
        .doesNotContain(SECRET_TEXT);
    assertThat(harness.referencesSentToClient()).as("★ 而且客户端也应当看到零引用（不是『过滤了但界面仍显示』）").isEmpty();
  }

  // ────────────────────────── 场景装配 ──────────────────────────

  /**
   * 一套最小装配：检索命中两条（PUBLIC + SECRET）、身份上限 CONFIDENTIAL、出口上限由参数给。
   *
   * <p>★ 用替身而不是数据库：本类要断的是"送进模型端口的那段字符串"，而 {@code ChatStreamPort} 那个替身收到的 {@code prompt}
   * 就是它。多一层数据库只会让这条判据变脆。
   */
  private static final class Harness {

    private final ClassificationLevel egressCeiling;
    private final AtomicReference<String> prompt = new AtomicReference<>();
    private final RecordingSink sink = new RecordingSink();
    private final TwoHitSearchPort search = new TwoHitSearchPort();

    private Harness(ClassificationLevel egressCeiling) {
      this.egressCeiling = egressCeiling;
    }

    private void run() {
      ChatStreamPort model =
          (modelId, promptText, timeout, callback) -> {
            prompt.set(promptText);
            callback.onChunk("按每天 500 元报销。");
            callback.onUsage(30, 10);
            callback.onEnd(ChatStreamPort.End.COMPLETE, null);
          };
      ModelEgressPort egress = modelId -> egressCeiling;
      ChatService service =
          new ChatService(
              new RecordingStore(), search, new OpenScope(), model, new ChatStopSignal(), egress);
      service.streamTurn(
          new RecordingStore().prepare(null, null, QUESTION, UUID.randomUUID()), QUESTION, sink);
    }

    private String promptSentToModel() {
      String sent = prompt.get();
      assertThat(sent).as("★ 前置：模型端口这一轮确实被调用了（否则下面那些 doesNotContain 是空断言）").isNotNull();
      return sent;
    }

    /** 客户端收到的引用（REFS 帧里的那批）—— 用来断言"界面也不该显示被挡下的条目"。 */
    private List<SourceRef> referencesSentToClient() {
      return sink.events.stream()
          .filter(event -> event.kind() == EventKind.REFS)
          .map(event -> ((StreamEvent.References) event).refs())
          .reduce((first, second) -> second)
          .orElse(List.of());
    }
  }

  /** 检索替身：两条命中，密级一条 PUBLIC 一条 SECRET（★ 都**在**用户可见范围内 —— 挡住它们的只能是出口闸门）。 */
  private static final class TwoHitSearchPort implements SegmentSearchPort {

    private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e2");
    private static final UUID PUBLIC_SEGMENT =
        UUID.fromString("0a000000-0000-4000-8000-0000000000f1");
    private static final UUID SECRET_SEGMENT =
        UUID.fromString("0a000000-0000-4000-8000-0000000000f2");

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
      return List.of(new Scored(PUBLIC_SEGMENT, 0.9), new Scored(SECRET_SEGMENT, 0.8));
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      List<Card> cards = new ArrayList<>();
      for (UUID id : segmentIds) {
        boolean isPublic = PUBLIC_SEGMENT.equals(id);
        cards.add(
            new Card(
                id,
                "差旅报销",
                isPublic ? PUBLIC_TEXT : SECRET_TEXT,
                "报销制度.pdf",
                "制度库",
                UUID.fromString("0a000000-0000-4000-8000-0000000000d1"),
                KB_ID,
                isPublic ? ClassificationLevel.PUBLIC : ClassificationLevel.SECRET));
      }
      return cards;
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

  /** 身份替身：上限 = CONFIDENTIAL（★ 于是 SECRET 那一条**能过身份那一关**，只有出口闸门能挡它）。 */
  private static final class OpenScope implements AccessScopePort {

    private static final UUID ME = UUID.fromString("0d000000-0000-4000-8000-0000000000e2");

    @Override
    public ScopeFilter currentScope() {
      return new ScopeFilter(
          ME, DataScope.OWN, Set.of("ADMIN"), ClassificationLevel.CONFIDENTIAL, Set.of());
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return true;
    }
  }

  /** 事件出口替身：只记事件。 */
  private static final class RecordingSink implements ChatService.EventSink {

    private final List<StreamEvent> events = new ArrayList<>();

    @Override
    public void onEvent(StreamEvent event) {
      events.add(event);
    }
  }

  /** 落库侧替身：本类不需要数据库，只需要"收尾能调通"。 */
  private static final class RecordingStore implements ChatTurnStore {

    @Override
    public java.util.List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      // ★ `#168`：本类不消费历史（多轮那一路由 ChatService 的多轮判据覆盖）。
      //   ★ 抛而不是返回空/null —— 返回空会让"这里没测"伪装成"测过了且是对的"。
      throw new UnsupportedOperationException("本替身不提供历史（#168）");
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
          MODEL_ID,
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
        String failReason,
        String failHint) {
      // 本类不关心落库内容
    }

    /**
     * ★ 本替身不走向重新生成那一路 ⇒ **抛**（与同族替身"没被用到的方法照旧抛"同一条纪律）。
     *
     * <p>★ 不写成 `return null` / `return ""`：那会让"这个替身其实不支持重生成" 在读数上消失（一个静默的空实现比一个明确的异常难查得多）。
     */
    @Override
    public cn.lincoln.aiplatform.conversation.application.ChatTurnWriter.PreparedTurn
        prepareRegenerate(java.util.UUID targetMessageId, java.util.UUID userId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }

    /** 同上：不走向重生成 ⇒ 抛。 */
    @Override
    public String questionOf(java.util.UUID userMessageId) {
      throw new UnsupportedOperationException("本替身不走向重新生成那一侧");
    }
  }
}
