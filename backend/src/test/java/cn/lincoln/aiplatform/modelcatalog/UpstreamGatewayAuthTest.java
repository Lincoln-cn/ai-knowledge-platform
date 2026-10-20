package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
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
