package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.SpringAiChatAdapter;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采样参数必须真的到达上游请求体（{@code #371} 第 135 条）。
 *
 * <p>★ 这一族判据只认<strong>上游收到了什么</strong>：参数存在 Java 字段里、存在数据库里、界面渲染出来了，都不能算 ——
 * 「收下但不用」比空表单更糟，因为它让人以为调过了。
 *
 * <p>★ 两条路都要测：{@link UpstreamGateway}（兜底直连）与 {@link SpringAiChatAdapter}（生产默认）。只测一条的后果是 "启不启用
 * Spring AI"决定参数有没有效，而那种差异线上最难查。
 */
class ModelParamUpstreamTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> lastBody = new AtomicReference<>("");

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"答案\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
                  .getBytes(StandardCharsets.UTF_8);
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
  @DisplayName("兜底网关：参数出现在请求体里")
  void gatewayCarriesParams() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.chatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        AuthStyle.NONE,
        Map.of("temperature", 0.35, "top_k", 40));

    assertThat(lastBody.get()).contains("\"temperature\":0.35");
    assertThat(lastBody.get()).contains("\"top_k\":40");
  }

  @Test
  @DisplayName("Spring AI 那条路：参数覆盖默认温度，也出现在请求体里")
  void springAiCarriesParams() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, baseUrl, "k");

    adapter.complete(
        ResolvedUpstream.legacy(baseUrl, null, ProviderEgress.LOCAL),
        "m1",
        "hi",
        Map.of("temperature", 0.35),
        Duration.ofSeconds(5));

    assertThat(lastBody.get()).contains("\"temperature\":0.35");
  }

  @Test
  @DisplayName("不给参数时保持原样：仍是默认 0.2（不是「永远带」）")
  void withoutParamsKeepsTheDefault() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.chatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        AuthStyle.NONE,
        Map.of());

    assertThat(lastBody.get()).doesNotContain("top_k");
  }

  @Test
  @DisplayName("Spring AI 那条路不给参数时温度是默认 0.2")
  void springAiWithoutParamsKeepsTheDefault() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, baseUrl, "k");

    adapter.complete(
        ResolvedUpstream.legacy(baseUrl, null, ProviderEgress.LOCAL),
        "m1",
        "hi",
        Map.of(),
        Duration.ofSeconds(5));

    assertThat(lastBody.get()).contains("\"temperature\":0.2");
  }
}
