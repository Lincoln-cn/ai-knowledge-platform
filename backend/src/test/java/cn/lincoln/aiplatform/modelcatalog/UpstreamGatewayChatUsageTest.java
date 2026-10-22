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

/** {@code #199}：一次性补全的上游 {@code usage} 必须被读出来，供兜底流式路径上报。 */
class UpstreamGatewayChatUsageTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> responseBody = new AtomicReference<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
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
  void readsUsageFromTheNonStreamingResponse() {
    responseBody.set(
        "{\"choices\":[{\"message\":{\"content\":\"答案\"}}],"
            + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}");
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    UpstreamGateway.ChatResult result =
        gateway.chatCompletionWithUsage(
            "m1",
            baseUrl,
            null,
            List.of(Map.of("role", "user", "content", "hi")),
            Duration.ofSeconds(5),
            AuthStyle.NONE);

    assertThat(result.content()).isEqualTo("答案");
    assertThat(result.promptTokens()).isEqualTo(9);
    assertThat(result.completionTokens()).isEqualTo(4);
    assertThat(result.hasUsage()).isTrue();
  }

  @Test
  void noUsageMeansNullNotZero() {
    responseBody.set("{\"choices\":[{\"message\":{\"content\":\"答案\"}}]}");
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    UpstreamGateway.ChatResult result =
        gateway.chatCompletionWithUsage(
            "m1",
            baseUrl,
            null,
            List.of(Map.of("role", "user", "content", "hi")),
            Duration.ofSeconds(5),
            AuthStyle.NONE);

    assertThat(result.content()).isEqualTo("答案");
    assertThat(result.promptTokens()).isNull();
    assertThat(result.completionTokens()).isNull();
    assertThat(result.hasUsage()).isFalse();
  }
}
