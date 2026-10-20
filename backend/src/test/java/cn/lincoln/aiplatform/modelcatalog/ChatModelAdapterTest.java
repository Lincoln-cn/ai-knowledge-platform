package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ChatModelAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 适配器的<strong>结构化出口</strong>（{@code ChatPort.completeAs}）：格式说明由官方转换器追加、响应由它解析 （{@code 11
 * §2.7}：实现结构化输出时先看官方，不要又写一层）。
 *
 * <h3>为什么这个类必须存在</h3>
 *
 * <p>{@code StructuredOutputConverterTest} 钉的是<strong>转换器自己</strong>的行为，而"生产代码真的用了它" 是另一件事 ——
 * 把适配器里"追加格式说明"那一句删掉，转换器那个类照样全绿（它压根不经过适配器）。 所以这里用假网关把<strong>真实的请求体</strong>留下来看：
 *
 * <ul>
 *   <li>★ 请求体里必须出现<strong>官方生成的 schema 字段名</strong>：提示词里已经不写 JSON 契约了， 它是模型知道形状的唯一来源（删掉追加那句，模型只能靠猜
 *       —— 而且没有任何地方报错）
 *   <li>围栏响应能被读出来（这条容错我们删掉了自研版本，依据在 {@code StructuredOutputConverterTest}）
 *   <li>坏响应 ⇒ {@link BusinessException}（不是 null、不是默认值 —— 调用方据此降级）
 * </ul>
 */
class ChatModelAdapterTest {

  private static final String PROMPT = "判断这些线索够不够回答问题";

  /** 与多跳判定同形的两个字段（这里只关心适配器怎么把它喂给模型、怎么读回来）。 */
  record Sample(Boolean sufficient, String nextQuery) {}

  private final RecordingGateway gateway = new RecordingGateway();
  private final ModelUpstreamResolver upstream = mock(ModelUpstreamResolver.class);

  @Test
  void appendsOfficialFormatToThePrompt() {
    Model model = chatModel(UUID.randomUUID());
    gateway.response = "{\"sufficient\": true, \"nextQuery\": null}";

    adapter(model).completeAs(model.id(), PROMPT, Sample.class, Duration.ofSeconds(5));

    assertThat(gateway.prompt).startsWith(PROMPT);
    assertThat(gateway.prompt)
        .as("★ 官方 schema 必须进请求体，否则模型不知道要输出哪两个字段")
        .contains("sufficient")
        .contains("nextQuery");
  }

  @Test
  void readsValuesFromTheModelOutput() {
    Model model = chatModel(UUID.randomUUID());
    gateway.response = "```json\n{\"sufficient\": false, \"nextQuery\": \"再查一次\"}\n```";

    Sample sample =
        adapter(model).completeAs(model.id(), PROMPT, Sample.class, Duration.ofSeconds(5));

    assertThat(sample.sufficient()).isFalse();
    assertThat(sample.nextQuery()).isEqualTo("再查一次");
  }

  /** 输出不符合 schema ⇒ 一次可读的调用失败（多跳据此降级，绝不静默给默认值）。 */
  @Test
  void turnsUnparsableResponseIntoBusinessFailure() {
    Model model = chatModel(UUID.randomUUID());
    gateway.response = "我不太确定该不该再查一次";

    ChatModelAdapter adapter = adapter(model);

    assertThatThrownBy(
            () -> adapter.completeAs(model.id(), PROMPT, Sample.class, Duration.ofSeconds(5)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Sample");
  }

  /** 类型不对要明确拒绝：拿向量模型走对话出口，回的不是正文，解析只会给出一个看不懂的失败。 */
  @Test
  void rejectsNonChatModel() {
    Model model = embeddingModel(UUID.randomUUID());

    ChatModelAdapter adapter = adapter(model);

    assertThatThrownBy(
            () -> adapter.completeAs(model.id(), PROMPT, Sample.class, Duration.ofSeconds(5)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不是对话模型");
  }

  @Test
  void doesNotPickChatModelWhoseProviderIsDisabled() {
    Model model = chatModel(UUID.randomUUID());
    when(upstream.egressOf(model)).thenReturn(java.util.Optional.empty());

    ChatModelAdapter adapter = new ChatModelAdapter(new FakeModels(model), upstream, gateway);

    assertThat(adapter.pickChatModel()).as("★ 服务商停用后，这个模型不能被“自动挑一个对话模型”选中").isEmpty();
  }

  /** `#185`：可开关模型 + 应用打开思考 ⇒ 请求体带 high；关掉 ⇒ none。 */
  @Test
  void mapsThinkingToggleToReasoningEffort() {
    Model model = chatModel(UUID.randomUUID());
    model.thinking(cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode.TOGGLEABLE);
    ChatModelAdapter adapter = adapter(model);

    adapter.streamChat(
        model.id(),
        List.of(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.USER, "hi")),
        true,
        Duration.ofSeconds(5),
        chunk -> {});
    assertThat(gateway.reasoningEffort).as("★ 打开思考必须真的进上游请求体").isEqualTo("high");

    adapter.streamChat(
        model.id(),
        List.of(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.USER, "hi")),
        false,
        Duration.ofSeconds(5),
        chunk -> {});
    assertThat(gateway.reasoningEffort).as("★ 关掉思考必须发 none，不能什么都不发").isEqualTo("none");
  }

  /** `#185`：始终思考 / 不支持思考的模型不发送 reasoning_effort（"关不掉"不能伪装成"已关闭"）。 */
  @Test
  void neverPretendsToDisableAlwaysOnThinking() {
    Model alwaysOn = chatModel(UUID.randomUUID());
    alwaysOn.thinking(cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode.ALWAYS_ON);
    ChatModelAdapter adapter = adapter(alwaysOn);
    adapter.streamChat(
        alwaysOn.id(),
        List.of(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.USER, "hi")),
        false,
        Duration.ofSeconds(5),
        chunk -> {});
    assertThat(gateway.reasoningEffort).as("★ ALWAYS_ON 不接受关闭，不能发 none").isNull();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 建适配器。
   *
   * <p>第 2 个参数（{@code ApiKeyCipher}）传 {@code null}：夹具模型没有存 key， {@code decryptKey} 在 {@code
   * apiKeyCipher == null} 时直接返回 null，压根不会用到它。
   */
  private ChatModelAdapter adapter(Model model) {
    when(upstream.resolve(model))
        .thenReturn(ResolvedUpstream.legacy(model.apiBase(), null, ProviderEgress.LOCAL));
    when(upstream.egressOf(model)).thenReturn(java.util.Optional.of(ProviderEgress.LOCAL));
    return new ChatModelAdapter(new FakeModels(model), upstream, gateway);
  }

  private static Model chatModel(UUID id) {
    return loaded(id, ModelKind.CHAT, null, 8_192);
  }

  private static Model embeddingModel(UUID id) {
    return loaded(id, ModelKind.EMBEDDING, 1_024, null);
  }

  private static Model loaded(UUID id, ModelKind kind, Integer dimension, Integer contextWindow) {
    Model model =
        new Model(
            id,
            "夹具模型",
            kind,
            Supplier.LOCAL_INFERENCE,
            "qwen2.5:1.5b",
            "http://localhost:11434/v1",
            null,
            null,
            contextWindow,
            dimension,
            null,
            null,
            false,
            null,
            null);
    model.startLoad();
    model.markLoaded();
    return model;
  }

  /** 假网关：把请求体留下来，回一个预置响应（★ 不真的发 HTTP）。 */
  private static final class RecordingGateway extends UpstreamGateway {

    String prompt;
    String reasoningEffort;
    String response = "{\"sufficient\": true, \"nextQuery\": null}";

    RecordingGateway() {
      super("http://localhost:9/v1", "test-key");
    }

    @Override
    public String chatCompletion(
        String model, String apiBase, String apiKey, String prompt, Duration timeout) {
      return chatCompletion(model, apiBase, apiKey, prompt, timeout, AuthStyle.BEARER);
    }

    @Override
    public String chatCompletion(
        String model,
        String apiBase,
        String apiKey,
        String prompt,
        Duration timeout,
        AuthStyle authStyle) {
      this.prompt = prompt;
      return response;
    }

    @Override
    public boolean streamChatCompletion(
        String model,
        String apiBase,
        String apiKey,
        List<java.util.Map<String, String>> history,
        Duration timeout,
        java.util.function.Consumer<String> onDelta,
        AuthStyle authStyle,
        String reasoningEffort) {
      this.reasoningEffort = reasoningEffort;
      return true;
    }
  }

  /** 只回答"这个 id 的模型"的假仓储。 */
  private static final class FakeModels implements ModelRepository {

    private final Model model;

    FakeModels(Model model) {
      this.model = model;
    }

    @Override
    public Optional<Model> findById(UUID id) {
      return model.id().equals(id) ? Optional.of(model) : Optional.empty();
    }

    @Override
    public Model save(Model model) {
      throw new UnsupportedOperationException("本测试不落库");
    }

    @Override
    public void delete(Model model) {
      throw new UnsupportedOperationException("本测试不落库");
    }

    @Override
    public List<Model> search(
        String keyword, ModelKind kind, ModelStatus status, int offset, int limit) {
      return model.kind() == kind && model.status() == status ? List.of(model) : List.of();
    }

    @Override
    public long countSearch(String keyword, ModelKind kind, ModelStatus status) {
      return 0;
    }

    @Override
    public long countByProviderId(UUID providerId) {
      return 0;
    }
  }
}
