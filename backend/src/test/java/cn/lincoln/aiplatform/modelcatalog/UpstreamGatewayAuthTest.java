package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 上游认证头必须是「服务商声明的那种」，不能退回 Bearer。
 *
 * <p>★ 这条判据的意义：JAiRouter 认 {@code X-API-Key}，OpenAI 认 {@code Authorization: Bearer}。 如果把 X-API-Key
 * 的 key 发成 Bearer，症状是 401 —— 看起来像用户填错密钥，实际是我方发错头。
 */
class UpstreamGatewayAuthTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> xApiKey = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();

  /** 探活路径被真正请求过几次 —— `#221` 的"校验发生在发请求之前"要靠它证明。 */
  private final AtomicInteger modelHits = new AtomicInteger();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
          modelHits.incrementAndGet();
          xApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "{\"data\":[{\"id\":\"m1\"}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          xApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          String requestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          this.requestBody.set(requestBody);
          if (requestBody.contains("\"stream\":true")) {
            byte[] body =
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } else {
            byte[] body =
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  @Test
  void xApiKeyStyleSendsOnlyXApiKey() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.listModels(baseUrl, "gateway-secret", AuthStyle.X_API_KEY);

    assertThat(xApiKey.get()).isEqualTo("gateway-secret");
    assertThat(authorization.get()).isNull();
  }

  @Test
  void bearerStyleSendsAuthorizationHeader() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.listModels(baseUrl, "openai-secret", AuthStyle.BEARER);

    assertThat(authorization.get()).isEqualTo("Bearer openai-secret");
    assertThat(xApiKey.get()).isNull();
  }

  @Test
  void noneStyleSendsNoCredentialAtAll() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.listModels(baseUrl, "should-not-be-sent", AuthStyle.NONE);

    assertThat(xApiKey.get()).isNull();
    assertThat(authorization.get()).isNull();
  }

  @Test
  void streamBearerStyleFallsBackToConfiguredDefaultKey() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.BEARER);

    // ★ 修复前这条是 null：stream 内联拼头，绕过了 effectiveKey；非流式却会带默认 key。
    assertThat(authorization.get()).isEqualTo("Bearer default-key");
    assertThat(xApiKey.get()).isNull();
  }

  @Test
  void streamXApiKeyStyleDoesNotFallBackToBearer() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        "gateway-secret",
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.X_API_KEY);

    assertThat(xApiKey.get()).isEqualTo("gateway-secret");
    assertThat(authorization.get()).isNull();
  }

  /**
   * `#221`：密钥含中文时必须在**发请求之前**失败，且消息里不回显密钥。
   *
   * <p>★ 修复前的症状：{@code HttpRequest.Builder.header} 抛 {@code IllegalArgumentException: invalid
   * header value: "Bearer &lt;密钥&gt;"} —— 密钥原文进了异常消息，而它不在 {@code catch} 覆盖范围内 ⇒ 500。
   */
  @Test
  void nonAsciiKeyIsRejectedBeforeTheRequestIsSent() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    assertThatThrownBy(() -> gateway.listModels(baseUrl, "密钥oops", AuthStyle.BEARER))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 个字符")
        .hasMessageContaining("非 ASCII")
        // ★ 断的是"密钥内容不回显"，不是"消息里不能出现『密钥』这两个字" —— 后者本来就该出现。
        .hasMessageNotContaining("oops");
    assertThat(modelHits).as("★ 校验在拼头时发生，不该先打一次上游再失败").hasValue(0);
  }

  /** 换行是最危险的一类（头注入）：报错要能指到"第几个字符是什么类别"，否则人只能一个字一个字看。 */
  @Test
  void newlineInKeyIsReportedByPosition() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    assertThatThrownBy(() -> gateway.listModels(baseUrl, "abc\ndef", AuthStyle.BEARER))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 4 个字符")
        .hasMessageContaining("换行");
    assertThat(modelHits).hasValue(0);
  }

  /** 回落到配置里的默认 key 时也要校验 —— 那条值同样会进请求头，且它从来不由用户当场输入。 */
  @Test
  void defaultKeyIsValidatedToo() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "默认-key");

    assertThatThrownBy(() -> gateway.listModels(baseUrl, null, AuthStyle.BEARER))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 个字符");
    assertThat(modelHits).hasValue(0);
  }

  /** `#185`：思考开关必须真的进请求体；不带时字段必须不出现（不能只写进数据库）。 */
  @Test
  void streamCarriesReasoningEffortOnlyWhenPresent() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.BEARER,
        "high");
    assertThat(requestBody.get()).contains("\"reasoning_effort\":\"high\"");

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.BEARER,
        null);
    assertThat(requestBody.get()).doesNotContain("reasoning_effort");
  }
}
