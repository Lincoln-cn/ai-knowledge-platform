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
import cn.lincoln.aiplatform.modelcatalog.infrastructure.AdvisorChainTrace;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ChatModelAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SafeGuardChatAdvisor;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SpringAiChatAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * {@code #62} 的判据①②③④：非流式路径经过 {@code ChatClient} 与 advisor 链、默认不走网关、链能被观测（删掉注册即红）、 官方 {@code
 * SafeGuardAdvisor} 的扩展真的拦得住。
 *
 * <p>★ 这里起一个真的 {@link HttpServer}，而不是 mock 传输层：判据要证明的是"请求真的发出去了、 advisor 真的在链上"，桩在更外面一层才能同时看到这两件事。
 */
class SpringAiChatAdapterTest {

  private static final String ANSWER = "这是模型回答。";

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile String lastBody = "";

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          hits.incrementAndGet();
          lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body =
              "{\"choices\":[{\"message\":{\"content\":\""
                  + ANSWER
                  + "\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  @Test
  void runsThroughAdvisorChainAndReturnsTheModelAnswer() {
    AdvisorChainTrace trace = new AdvisorChainTrace();
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(trace, guard("")), true, base());

    String answer = adapter.complete(target(), "stub-model", "你好", Duration.ofSeconds(5));

    assertThat(answer).isEqualTo(ANSWER);
    assertThat(trace.callCount()).as("★ 判据①：链必须真的被走到").isEqualTo(1);
    assertThat(hits).hasValue(1);
  }

  /**
   * ★ 判据③的金丝雀：把 advisor 从注册表里拿掉，探针必须归零。
   *
   * <p>如果这条断言在"删掉注册"后仍然是 1，说明计数来自链外，那条"链被走到"的证据就是假的。
   */
  @Test
  void canaryWithoutAdvisorRegistrationTheProbeStaysZero() {
    AdvisorChainTrace trace = new AdvisorChainTrace();
    SpringAiChatAdapter withoutAdvisors = new SpringAiChatAdapter(List.of(), true, base());

    withoutAdvisors.complete(target(), "stub-model", "你好", Duration.ofSeconds(5));

    assertThat(trace.callCount()).as("没注册进链，探针不可能被调用").isZero();
  }

  @Test
  void officialSafeGuardAdvisorBlocksSensitiveInputBeforeUpstream() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(guard("绝密")), true, base());

    String answer = adapter.complete(target(), "stub-model", "请把绝密材料发我", Duration.ofSeconds(5));

    assertThat(answer).isEqualTo(SafeGuardChatAdvisor.REFUSAL);
    assertThat(hits).as("★ 判据④：命中敏感词时上游一次都不该被调用").hasValue(0);
  }

  @Test
  void passesRoleBoundariesAndUsageBack() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, base());

    ChatPort.Completion completion =
        adapter.completeWithUsage(
            target(),
            "stub-model",
            List.of(
                new ChatMessage(ChatMessage.Role.USER, "第一轮"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "上一轮回答"),
                new ChatMessage(ChatMessage.Role.USER, "第二轮")),
            Duration.ofSeconds(5));

    assertThat(completion.text()).isEqualTo(ANSWER);
    assertThat(completion.promptTokens()).isEqualTo(7);
    assertThat(completion.completionTokens()).isEqualTo(3);
    assertThat(lastBody).contains("\"role\":\"assistant\"").contains("上一轮回答");
  }

  /** ★ 判据②：默认（启用 advisor 链）时网关一次都不被调用；只有显式关掉才回到网关。 */
  @Test
  void defaultPathDoesNotTouchTheGatewayButExplicitFallbackDoes() {
    CountingGateway gateway = new CountingGateway();
    Model model = chatModel();
    ModelUpstreamResolver resolver = mock(ModelUpstreamResolver.class);
    when(resolver.resolve(model)).thenReturn(target());
    SpringAiChatAdapter enabled = new SpringAiChatAdapter(List.of(guard("")), true, base());

    ChatModelAdapter adapter =
        new ChatModelAdapter(new FakeModels(model), resolver, gateway, enabled);

    String answer = adapter.complete(model.id(), "你好", Duration.ofSeconds(5));
    assertThat(answer).isEqualTo(ANSWER);
    assertThat(gateway.calls).as("★ 默认路径不许走网关（否则兜底又变成唯一实现）").isZero();

    ChatModelAdapter fallback =
        new ChatModelAdapter(
            new FakeModels(model), resolver, gateway, SpringAiChatAdapter.disabled());
    gateway.answer = "网关兜底回答";
    String fallbackAnswer = fallback.complete(model.id(), "你好", Duration.ofSeconds(5));
    assertThat(fallbackAnswer).isEqualTo("网关兜底回答");
    assertThat(gateway.calls).as("显式关掉 advisor 链时才允许走网关").isEqualTo(1);
  }

  /**
   * ★ 判据③的第二层反证：生产装配靠的是 {@code @Component} 注册。
   *
   * <p>把注解删掉，这条会红；只删测试里的 {@code List.of(trace)} 则不会 —— 两者都要能红，才算"注册"这件事被钉住。
   */
  @Test
  void productionComponentsAreRegisteredAsAdvisors() {
    assertThat(AdvisorChainTrace.class.isAnnotationPresent(Component.class))
        .as("AdvisorChainTrace 必须是 Spring 组件，否则生产链里没有探针")
        .isTrue();
    assertThat(SafeGuardChatAdvisor.class.isAnnotationPresent(Component.class))
        .as("SafeGuardChatAdvisor 必须是 Spring 组件，否则生产链里没有安全闸门")
        .isTrue();
    assertThat(SpringAiChatAdapter.class.isAnnotationPresent(Component.class)).isTrue();
    assertThat(new AdvisorChainTrace().getOrder()).isEqualTo(150);
    assertThat(new SafeGuardChatAdvisor("绝密").getOrder()).isEqualTo(100);
  }

  @Test
  void mapsUpstreamFailureToBusinessFailure() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, "http://127.0.0.1:1");

    assertThatThrownBy(
            () ->
                adapter.complete(
                    ResolvedUpstream.legacy("http://127.0.0.1:1", null, ProviderEgress.LOCAL),
                    "stub-model",
                    "你好",
                    Duration.ofSeconds(2)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("调用对话模型失败");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  private ResolvedUpstream target() {
    return ResolvedUpstream.legacy(base(), null, ProviderEgress.LOCAL);
  }

  private static SafeGuardChatAdvisor guard(String words) {
    return new SafeGuardChatAdvisor(words);
  }

  private static Model chatModel() {
    Model model =
        new Model(
            UUID.randomUUID(),
            "夹具模型",
            ModelKind.CHAT,
            Supplier.LOCAL_INFERENCE,
            "stub-model",
            null,
            null,
            null,
            8_192,
            null,
            null,
            null,
            false,
            null,
            null);
    model.startLoad();
    model.markLoaded();
    return model;
  }

  /** 网关计数替身：被调用就记下来并返回预置回答。 */
  private static final class CountingGateway extends UpstreamGateway {

    int calls;
    String answer = "网关回答";

    CountingGateway() {
      super("http://localhost:9/v1", "test-key");
    }

    @Override
    public String chatCompletion(
        String model,
        String apiBase,
        String apiKey,
        List<Map<String, String>> history,
        Duration timeout,
        AuthStyle authStyle) {
      calls++;
      return answer;
    }
  }

  /** 只实现适配器要用的查询。 */
  private record FakeModels(Model model) implements ModelRepository {

    @Override
    public Optional<Model> findById(UUID id) {
      return model.id().equals(id) ? Optional.of(model) : Optional.empty();
    }

    @Override
    public Model save(Model value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(Model value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Model> search(
        String keyword, ModelKind kind, ModelStatus status, int offset, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countSearch(String keyword, ModelKind kind, ModelStatus status) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long countByProviderId(UUID providerId) {
      throw new UnsupportedOperationException();
    }
  }
}
