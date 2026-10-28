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
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.AdvisorChainTrace;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ChatModelAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.ModelUpstreamResolver;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SafeGuardChatAdvisor;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SpringAiChatAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * {@code #62} 的判据①②③④：非流式路径经过 {@code ChatClient} 与 advisor 链、默认不走网关、链能被观测（删掉注册即红）、 官方 {@code
 * SafeGuardAdvisor} 的扩展真的拦得住。
 *
 * <p>★ 这里起一个真的 {@link HttpServer}，而不是 mock 传输层：判据要证明的是"请求真的发出去了、 advisor 真的在链上"，桩在更外面一层才能同时看到这两件事。
 *
 * <p>★★ 同一个桩也扮 SSE 上游（请求体带 {@code "stream":true} 时回 {@code text/event-stream}）：流式那一半的判据必须
 * 证明"逐块到达"，而那件事只有 一个会 flush 的真 HTTP 连接才能证明 —— 用 mock 的 {@code Flux} 替身永远证明不了它。
 */
class SpringAiChatAdapterTest {

  private static final String ANSWER = "这是模型回答。";

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile String lastBody = "";
  private volatile String lastAuthorization = "";

  /** 非 null 时用它替掉标准 SSE 脚本（空流 / 只有 usage 帧这些边界形状要能单独摆出来）。 */
  private volatile List<String> scriptedSse;

  @BeforeEach
  void startStub() throws IOException {
    scriptedSse = null;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          hits.incrementAndGet();
          lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
          try {
            if (lastBody.contains("\"stream\":true")) {
              writeSse(exchange);
            } else {
              writeJson(exchange);
            }
          } catch (IOException ignored) {
            // 调用方主动中断（那是被测行为）会让这半边连接报 IOException —— 它不是被测对象
          }
        });
    server.start();
  }

  private static void writeJson(HttpExchange exchange) throws IOException {
    String body =
        "{\"choices\":[{\"message\":{\"content\":\""
            + ANSWER
            + "\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void writeSse(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
    // 0 = chunked：块必须真的分多次到，攒成一段的那条断言才有意义
    exchange.sendResponseHeaders(200, 0);
    OutputStream out = exchange.getResponseBody();
    for (String frame : sseFrames()) {
      out.write(frame.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
    exchange.close();
  }

  /** 标准流：role 帧 → 思考帧（不产正文）→ 两块正文 → finish → {@code choices} 为空的 usage 帧 → {@code [DONE]}。 */
  private List<String> sseFrames() {
    if (scriptedSse != null) {
      return scriptedSse;
    }
    return List.of(
        frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"),
        frame("{\"choices\":[{\"delta\":{\"reasoning_content\":\"先想一想\"}}]}"),
        frame("{\"choices\":[{\"delta\":{\"content\":\"流式\"}}]}"),
        frame("{\"choices\":[{\"delta\":{\"content\":\"回答。\"}}]}"),
        frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"),
        frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"),
        "data: [DONE]\n\n");
  }

  private static String frame(String data) {
    return "data: " + data + "\n\n";
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

  // ────────────────────────── 流式那一半（#62 判据①②③④⑤ 在 stream 上的同形） ──────────────────────────

  /**
   * ★★ 判据①（流式）：流必须经过 {@code ChatClient} 与 advisor 链，并且逐块到达。
   *
   * <p>这条是整件事的意义所在：{@code SafeGuardChatAdvisor} 之所以能在流式上也生效，唯一原因就是流也走这条链。
   */
  @Test
  void streamsThroughTheAdvisorChainAndForwardsEveryChunk() {
    AdvisorChainTrace trace = new AdvisorChainTrace();
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(trace, guard("")), true, base());
    List<String> chunks = new ArrayList<>();
    AtomicReference<int[]> usage = new AtomicReference<>();

    boolean streamed =
        adapter.streamConversation(
            target(),
            "stub-model",
            List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
            null,
            Duration.ofSeconds(5),
            chunks::add,
            (promptTokens, completionTokens) ->
                usage.set(new int[] {promptTokens, completionTokens}));

    assertThat(streamed).isTrue();
    assertThat(chunks).as("★ 必须逐块交付，攒成一段就不叫真流式").containsExactly("流式", "回答。");
    assertThat(trace.streamCount()).as("★ 判据①（流式）：流必须经过 advisor 链").isEqualTo(1);
    assertThat(trace.callCount()).as("流式不该顺手把非流式探针也算上").isZero();
    assertThat(usage.get()).as("★ 流末尾 usage 帧要交给调用方（#142 的口径）").containsExactly(7, 3);
    assertThat(lastBody)
        .contains("\"stream\":true")
        .contains("\"include_usage\":true")
        .as("★ 不要求 include_usage 时上游不会在流末尾给 usage，用量会恒为 0")
        .contains("\"temperature\":0.2");
    assertThat(hits).hasValue(1);
  }

  /** ★ 判据③的金丝雀在流式上的同形：没注册进链，探针必须归零，而正文仍要到。 */
  @Test
  void streamCanaryWithoutAdvisorRegistrationTheProbeStaysZero() {
    AdvisorChainTrace trace = new AdvisorChainTrace();
    SpringAiChatAdapter withoutAdvisors = new SpringAiChatAdapter(List.of(), true, base());
    List<String> chunks = new ArrayList<>();

    boolean streamed =
        withoutAdvisors.streamConversation(
            target(),
            "stub-model",
            List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
            null,
            Duration.ofSeconds(5),
            chunks::add,
            null);

    assertThat(streamed).isTrue();
    assertThat(String.join("", chunks)).isEqualTo("流式回答。");
    assertThat(trace.streamCount()).as("没注册进链，探针不可能被调用").isZero();
  }

  /**
   * ★★ 判据④在流式上的读数：{@code SafeGuardAdvisor} 的 {@code adviseStream} 直接返回拒绝话术，上游一次都不碰。
   *
   * <p>这正是"不必另写一套 StreamAdvisor"的证明 —— 拦截发生在我们没有写过一行代码的地方。
   */
  @Test
  void officialSafeGuardAdvisorBlocksSensitiveInputOnTheStreamingPath() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(guard("绝密")), true, base());
    List<String> chunks = new ArrayList<>();

    boolean streamed =
        adapter.streamConversation(
            target(),
            "stub-model",
            List.of(new ChatMessage(ChatMessage.Role.USER, "请把绝密材料发我")),
            null,
            Duration.ofSeconds(5),
            chunks::add,
            null);

    assertThat(streamed).as("拒绝话术本身也是一块正文").isTrue();
    assertThat(String.join("", chunks)).isEqualTo(SafeGuardChatAdvisor.REFUSAL);
    assertThat(hits).as("★ 判据④：命中敏感词时上游一次都不该被调用").hasValue(0);
  }

  /**
   * ★ 判据⑤：200 但一个 data 帧都没有 ⇒ 必须报"没流起来"。
   *
   * <p>如果这里返回 true，上层会收成一个"成功但零字"的助手消息 —— 那是最难查的一种失败：界面不报错， 只是没有答案。
   */
  @Test
  void streamWithoutAnyDataFrameReportsNotStreamed() {
    scriptedSse = List.of("data: [DONE]\n\n");
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(guard("")), true, base());
    List<String> chunks = new ArrayList<>();

    boolean streamed =
        adapter.streamConversation(
            target(),
            "stub-model",
            List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
            null,
            Duration.ofSeconds(5),
            chunks::add,
            null);

    assertThat(streamed).as("★ 判据⑤：零 data 帧 ⇒ false，交给上层报失败").isFalse();
    assertThat(chunks).isEmpty();
    assertThat(hits).as("确实连过一次上游，只是它什么都没吐").hasValue(1);
  }

  /** ★ 只有 role / usage 帧（{@code choices} 为空）时不算收到正文，也不许把空串当一块交付。 */
  @Test
  void usageOnlyFramesAreNotMistakenForContent() {
    scriptedSse =
        List.of(
            frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"),
            frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"),
            "data: [DONE]\n\n");
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(guard("")), true, base());
    List<String> chunks = new ArrayList<>();

    boolean streamed =
        adapter.streamConversation(
            target(),
            "stub-model",
            List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
            null,
            Duration.ofSeconds(5),
            chunks::add,
            null);

    assertThat(streamed).isFalse();
    assertThat(chunks).isEmpty();
  }

  /**
   * ★★ 停止/断开：调用方在回调里抛异常必须<strong>原样</strong>穿透，且立刻停止交付。
   *
   * <p>{@code ChatService.onChunk} 靠抛 {@code StopUpstream} 中断（{@code ChatService.java:1636}
   * 一带）；这条异常 一旦被适配器包成 {@code BusinessException}，"用户点了停止"就会变成"上游调用失败"。
   */
  @Test
  void consumerFailureInterruptsTheStreamAndPropagatesAsIs() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(guard("")), true, base());
    List<String> delivered = new ArrayList<>();
    IllegalStateException stop = new IllegalStateException("用户点了停止");

    assertThatThrownBy(
            () ->
                adapter.streamConversation(
                    target(),
                    "stub-model",
                    List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
                    null,
                    Duration.ofSeconds(5),
                    chunk -> {
                      delivered.add(chunk);
                      throw stop;
                    },
                    null))
        .as("调用方抛的异常不许被包装、不许被吞")
        .isSameAs(stop);
    assertThat(delivered).as("抛了之后不许再交付下一块").containsExactly("流式");
  }

  /** ★ 判据②（流式）：默认（链启用）时网关一次都不被调用；显式关掉才回到网关。 */
  @Test
  void streamingPathAlsoAvoidsTheGatewayUnlessExplicitlyDisabled() {
    CountingGateway gateway = new CountingGateway();
    Model model = chatModel();
    ModelUpstreamResolver resolver = mock(ModelUpstreamResolver.class);
    when(resolver.resolve(model)).thenReturn(target());
    AdvisorChainTrace trace = new AdvisorChainTrace();
    SpringAiChatAdapter enabled = new SpringAiChatAdapter(List.of(guard(""), trace), true, base());
    ChatModelAdapter adapter =
        new ChatModelAdapter(new FakeModels(model), resolver, gateway, enabled);
    List<String> chunks = new ArrayList<>();

    boolean streamed =
        adapter.streamChat(
            model.id(),
            List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
            false,
            Duration.ofSeconds(5),
            chunks::add,
            null);

    assertThat(streamed).isTrue();
    assertThat(String.join("", chunks)).isEqualTo("流式回答。");
    assertThat(gateway.calls).as("★ 流式默认也不许走网关（判据②在流式上的同形）").isZero();
    assertThat(trace.streamCount())
        .as("★ 从端口层（ChatModelAdapter）下来也必须在 advisor 链上，而不是只在适配器的直连用例里")
        .isEqualTo(1);

    ChatModelAdapter fallback =
        new ChatModelAdapter(
            new FakeModels(model), resolver, gateway, SpringAiChatAdapter.disabled());
    List<String> fallbackChunks = new ArrayList<>();
    assertThat(
            fallback.streamChat(
                model.id(),
                List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
                false,
                Duration.ofSeconds(5),
                fallbackChunks::add,
                null))
        .isTrue();
    assertThat(fallbackChunks).containsExactly("网关流式回答");
    assertThat(gateway.streamCalls).as("显式关掉链时才允许走网关").isEqualTo(1);
  }

  /**
   * ★★ {@code #185}：思考开关必须真的进到流式请求体里。
   *
   * <p>这是"最易丢的一格"：{@code ChatModelAdapterTest} 只钉住了网关那一半（{@code RecordingGateway.reasoningEffort}），
   * 流式改道 {@code ChatClient} 之后，若本类不把 {@code reasoning_effort} 放进请求体，那条用例仍然全绿 —— 开关却已经失效。
   */
  @Test
  void thinkingToggleReachesTheStreamingRequestBody() {
    Model model = chatModel();
    model.thinking(ThinkingMode.TOGGLEABLE);
    ModelUpstreamResolver resolver = mock(ModelUpstreamResolver.class);
    when(resolver.resolve(model)).thenReturn(target());
    ChatModelAdapter adapter =
        new ChatModelAdapter(
            new FakeModels(model),
            resolver,
            new CountingGateway(),
            new SpringAiChatAdapter(List.of(guard("")), true, base()));

    adapter.streamChat(
        model.id(),
        List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
        true,
        Duration.ofSeconds(5),
        chunk -> {},
        null);
    assertThat(lastBody).as("可切换 + 打开 ⇒ 必须发 high").contains("\"reasoning_effort\":\"high\"");

    adapter.streamChat(
        model.id(),
        List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
        false,
        Duration.ofSeconds(5),
        chunk -> {},
        null);
    assertThat(lastBody).as("可切换 + 关闭 ⇒ 必须发 none").contains("\"reasoning_effort\":\"none\"");

    model.thinking(ThinkingMode.ALWAYS_ON);
    adapter.streamChat(
        model.id(),
        List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
        true,
        Duration.ofSeconds(5),
        chunk -> {},
        null);
    assertThat(lastBody)
        .as("始终思考的模型「关」物理上做不到 ⇒ 根本不发这个字段，而不是发一个被忽略的 none")
        .doesNotContain("reasoning_effort");
  }

  /**
   * ★ 认证头回落：服务商没配 key 时，Bearer 上游要回落到配置里的默认 key（与 {@code UpstreamGateway} 同一条规则）。
   *
   * <p>流式从网关切到 {@code ChatClient} 之后，若这条规则没跟过来，症状会是"非流式正常、流式 401"—— 那正是 {@code
   * UpstreamGateway.java:534} 记过的同一个坑，只是换了一侧。
   *
   * <p>★ 这里的默认 key 故意写成 ASCII：HTTP 头值的合法字符集容不下中文，{@code HttpRequest.Builder.header} 会直接抛 {@code
   * IllegalArgumentException} —— 那是夹具的限制，不是被测逻辑的（第一版把中文写进 key，这条用例红在这上面）。
   */
  @Test
  void blankProviderKeyFallsBackToTheConfiguredDefaultKey() {
    SpringAiChatAdapter adapter =
        new SpringAiChatAdapter(List.of(), true, base(), "configured-default-key");

    adapter.streamConversation(
        target(),
        "stub-model",
        List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
        null,
        Duration.ofSeconds(5),
        chunk -> {},
        null);

    assertThat(lastAuthorization).isEqualTo("Bearer configured-default-key");
  }

  /**
   * `#221`：密钥含中文时**必须在发请求之前**变成可读的失败，且不许回显密钥。
   *
   * <p>★ 修复前的症状有两层：{@code HttpRequest.Builder.header} 抛的是 {@code IllegalArgumentException: invalid
   * header value: "Bearer &lt;密钥&gt;"}（<strong>把密钥原文写进了异常消息</strong>）， 而它不在任何 {@code catch} 的范围内 ⇒
   * 接口 500。所以这条同时断三件事：类型是 {@code BusinessException}（能被
   * 统一包络读出来）、消息只说位置不回显字符、桩**一次都没被请求到**（校验发生在拼头时，不是等上游回 401）。
   */
  @Test
  void nonAsciiKeyIsRejectedBeforeAnyRequestIsSent() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, base());

    assertThatThrownBy(
            () ->
                adapter.complete(
                    ResolvedUpstream.legacy(base(), "密钥oops", ProviderEgress.LOCAL),
                    "stub-model",
                    "你好",
                    Duration.ofSeconds(5)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 个字符")
        // ★ 断的是"密钥内容不回显"，不是"消息里不能出现『密钥』这两个字" —— 后者本来就该出现。
        .hasMessageNotContaining("oops");
    assertThat(hits).as("★ 校验必须在发请求之前").hasValue(0);
  }

  /** 流式那一半也走同一个校验点（`#62` 之后它是另一条出站路径，不能只修非流式）。 */
  @Test
  void streamingPathAlsoRejectsNonAsciiKeyBeforeAnyRequest() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, base());
    List<String> chunks = new ArrayList<>();

    assertThatThrownBy(
            () ->
                adapter.streamConversation(
                    ResolvedUpstream.legacy(base(), "密钥oops", ProviderEgress.LOCAL),
                    "stub-model",
                    List.of(new ChatMessage(ChatMessage.Role.USER, "你好")),
                    null,
                    Duration.ofSeconds(5),
                    chunks::add,
                    (promptTokens, completionTokens) -> {}))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 个字符");
    assertThat(chunks).as("一个字节都不该交付").isEmpty();
    assertThat(hits).as("★ 校验必须在发请求之前").hasValue(0);
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
    int streamCalls;
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

    @Override
    public boolean streamChatCompletion(
        String model,
        String apiBase,
        String apiKey,
        List<Map<String, String>> history,
        Duration timeout,
        java.util.function.Consumer<String> onDelta,
        AuthStyle authStyle,
        String reasoningEffort,
        java.util.function.BiConsumer<Integer, Integer> onUsage) {
      streamCalls++;
      onDelta.accept("网关流式回答");
      return true;
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
