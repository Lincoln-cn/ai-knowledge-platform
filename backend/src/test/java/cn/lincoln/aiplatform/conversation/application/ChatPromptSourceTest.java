package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter.PreparedTurn;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
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
import org.junit.jupiter.api.Test;

/**
 * `#183`：应用会话的提示词来源。
 *
 * <p>★ 这条判据只钉一件事：应用轮的角色设定必须进 prompt，平台通用初始提示词不得进； 通用轮相反。两条都必须能红 —— 只测应用那一侧会让“平台提示词串味”继续静默。
 */
class ChatPromptSourceTest {

  private static final UUID APP_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e1");
  private static final UUID MODEL_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e2");
  private static final UUID KB_ID = UUID.fromString("0a000000-0000-4000-8000-0000000000e3");
  private static final String PLATFORM_PROMPT = "PLATFORM_PROMPT_MARKER";
  private static final String ROLE_PROMPT = "APP_ROLE_PROMPT_MARKER";

  @Test
  void appTurnUsesAppRolePromptAndNeverThePlatformOne() {
    RecordingStore store = new RecordingStore();
    RecordingStream stream = new RecordingStream();
    ChatService service = service(store, stream);
    service.setPolicyResolver(policyWithPlatformPrompt());

    service.streamTurn(store.turn(APP_ID, ROLE_PROMPT), "PING", new NoopSink());

    assertThat(stream.prompts).hasSize(1);
    assertThat(stream.prompts.get(0))
        .as("★★ 应用 rolePrompt 必须进 prompt")
        .contains(ROLE_PROMPT)
        .as("★★ 平台通用提示词不得污染应用会话")
        .doesNotContain(PLATFORM_PROMPT);
  }

  @Test
  void genericTurnUsesPlatformPrompt() {
    RecordingStore store = new RecordingStore();
    RecordingStream stream = new RecordingStream();
    ChatService service = service(store, stream);
    service.setPolicyResolver(policyWithPlatformPrompt());

    service.streamTurn(store.turn(null, null), "PING", new NoopSink());

    assertThat(stream.prompts).hasSize(1);
    assertThat(stream.prompts.get(0))
        .as("通用问答仍应读平台初始提示词")
        .contains(PLATFORM_PROMPT)
        .doesNotContain(ROLE_PROMPT);
  }

  @Test
  void appTurnPutsBoundPromptTextsBeforeTheRolePrompt() {
    RecordingStore store = new RecordingStore();
    RecordingStream stream = new RecordingStream();
    ChatService service = service(store, stream);
    service.setPolicyResolver(policyWithPlatformPrompt());

    service.streamTurn(
        store.turn(APP_ID, ROLE_PROMPT, List.of("PROMPT_A_MARKER", "PROMPT_B_MARKER")),
        "PING",
        new NoopSink());

    String prompt = stream.prompts.get(0);
    // ★ `#215`：绑定的提示词正文必须真的进来 —— 这一条在修复前恒红（那时只拼 rolePrompt）
    assertThat(prompt).contains("PROMPT_A_MARKER").contains("PROMPT_B_MARKER");
    // ★ 顺序是口径的一部分：提示词在前、rolePrompt 在最后一句（冲突时它赢）
    assertThat(prompt.indexOf("PROMPT_A_MARKER"))
        .as("提示词之间的相对顺序归 AppBindingAdapter 管，这里只钉它们都在 rolePrompt 之前")
        .isLessThan(prompt.indexOf(ROLE_PROMPT));
    assertThat(prompt.indexOf("PROMPT_B_MARKER")).isLessThan(prompt.indexOf(ROLE_PROMPT));
    assertThat(prompt).as("平台提示词仍不得污染应用会话").doesNotContain(PLATFORM_PROMPT);
  }

  @Test
  void rolePromptCopiedIntoAPromptTextAppearsOnlyOnce() {
    RecordingStore store = new RecordingStore();
    RecordingStream stream = new RecordingStream();
    ChatService service = service(store, stream);

    // 作者把同一段话既写进 rolePrompt、又提交成一条提示词 ⇒ 拼出来只应有一份。
    // ★ 同时带一条**只存在于提示词里**的标记：否则"提示词整体被忽略"也会让这条绿（只出现一份），
    //   那就成了自证 —— 去重与"真的拼进来了"必须一起断。
    service.streamTurn(
        store.turn(APP_ID, ROLE_PROMPT, List.of("PROMPT_ONLY_MARKER", ROLE_PROMPT)),
        "PING",
        new NoopSink());

    String prompt = stream.prompts.get(0);
    assertThat(prompt).contains("PROMPT_ONLY_MARKER");
    assertThat(prompt.split(ROLE_PROMPT, -1)).as("同一段文本只拼一次").hasSize(2);
  }

  @Test
  void appWithOnlyPromptsAndNoRolePromptStillGetsAPrefix() {
    RecordingStore store = new RecordingStore();
    RecordingStream stream = new RecordingStream();
    ChatService service = service(store, stream);

    service.streamTurn(
        store.turn(APP_ID, "", List.of("PROMPT_ONLY_MARKER")), "PING", new NoopSink());

    assertThat(stream.prompts.get(0))
        .as("rolePrompt 为空不等于什么都不加：绑了提示词就应当有前缀")
        .contains("PROMPT_ONLY_MARKER")
        .doesNotContain(PLATFORM_PROMPT);
  }

  private static ChatPolicyResolver policyWithPlatformPrompt() {
    ChatPolicyResolver policy = mock(ChatPolicyResolver.class);
    when(policy.systemPrompt()).thenReturn(PLATFORM_PROMPT);
    return policy;
  }

  private static ChatService service(ChatTurnStore store, ChatStreamPort stream) {
    return new ChatService(
        store,
        new EmptySearchPort(),
        new KnownScope(),
        stream,
        new ChatStopSignal(),
        new ChatStreamProperties(300L, 1200L, 60L, 200),
        (ModelEgressPort) modelId -> ClassificationLevel.CONFIDENTIAL);
  }

  private static final class RecordingStream implements ChatStreamPort {
    private final List<String> prompts = new ArrayList<>();

    @Override
    public void stream(UUID modelId, String prompt, java.time.Duration timeout, Callback callback) {
      prompts.add(prompt);
      callback.onEnd(End.COMPLETE, null);
    }
  }

  private static final class NoopSink implements ChatService.EventSink {
    @Override
    public void onEvent(StreamEvent event) {}
  }

  private static final class EmptySearchPort implements SegmentSearchPort {
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
      throw new UnsupportedOperationException("零命中");
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      throw new UnsupportedOperationException("不走向量路");
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      throw new UnsupportedOperationException("不走向量路");
    }
  }

  private static final class KnownScope implements AccessScopePort {
    @Override
    public ScopeFilter currentScope() {
      return new ScopeFilter(
          UUID.fromString("0a000000-0000-4000-8000-0000000000e4"),
          DataScope.OWN,
          Set.of("ADMIN"),
          ClassificationLevel.INTERNAL,
          Set.of());
    }

    @Override
    public boolean canSee(OwnedResource resource) {
      return true;
    }
  }

  private static final class RecordingStore implements ChatTurnStore {
    private PreparedTurn turn;

    private PreparedTurn turn(UUID appId, String rolePrompt) {
      return turn(appId, rolePrompt, List.of());
    }

    private PreparedTurn turn(UUID appId, String rolePrompt, List<String> promptTexts) {
      UUID assistantMessageId = UUID.randomUUID();
      turn =
          new ChatTurnWriter.PreparedTurn(
              UUID.randomUUID(),
              assistantMessageId,
              UUID.randomUUID(),
              "trace_" + assistantMessageId,
              MODEL_ID.toString(),
              MODEL_ID,
              List.of(),
              false,
              false,
              appId,
              rolePrompt,
              promptTexts,
              null);
      return turn;
    }

    @Override
    public PreparedTurn prepare(UUID conversationId, UUID appId, String question, UUID userId) {
      return turn;
    }

    @Override
    public List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
        UUID conversationId, UUID userMessageId, int maxMessages) {
      throw new UnsupportedOperationException("本用例单轮，不读历史");
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

    @Override
    public PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
      throw new UnsupportedOperationException("本用例不走重生成");
    }

    @Override
    public String questionOf(UUID userMessageId) {
      throw new UnsupportedOperationException("本用例不走重生成");
    }
  }
}
