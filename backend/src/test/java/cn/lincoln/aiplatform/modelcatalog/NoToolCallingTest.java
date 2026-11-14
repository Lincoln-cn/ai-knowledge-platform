package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamGateway;
import cn.lincoln.aiplatform.platform.infrastructure.NoopToolProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ROADMAP §11.45.4}（0.8.3）：<strong>今天没有任何工具调用被发出去</strong>。
 *
 * <p>★ 这条测试存在的理由，是"没有工具调用"这句话在加接口之前<strong>无法被证伪</strong> ——
 * 它只是在陈述"没人写过那行代码"，而没人写过的事随时可能被别人顺手写出来。所以判据不是 "我们没做工具调用"，而是<strong>"发出去的请求体里不存在 {@code tools}
 * 键"</strong>：
 *
 * <ul>
 *   <li>它读的是<strong>真正上网的那一字节</strong>（桩服务收到的请求体），不是我们以为发了什么；
 *   <li>它<strong>能红</strong>：任何一处往 payload 里加 {@code payload.put("tools", …)}， 这两条立刻失败 —— 无论加在非流式
 *       {@code :313} 还是流式 {@code :444} 上。
 * </ul>
 *
 * <p>★ 只断"没有 tools"而不去断"应该有工具"是刻意的：0.9.0 的沙箱落地之前，
 * 正确的行为就是不发。等到真要做工具调用时，这条测试应当被<strong>改写</strong>成"tools 只在 该发的时候发"，而不是被删掉。
 */
class NoToolCallingTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> requestBody = new AtomicReference<>();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requestBody.set(body);
          // 同一路径两种形态：流式请求返回 SSE，其余返回一次性 JSON。
          if (body.contains("\"stream\":true")) {
            String sse =
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } else {
            byte[] bytes =
                "{\"choices\":[{\"message\":{\"content\":\"答案\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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
  void nonStreamingRequestCarriesNoTools() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.chatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "你好")),
        Duration.ofSeconds(5),
        AuthStyle.BEARER);

    String body = requestBody.get();
    assertThat(body).as("非流式对话请求体").contains("\"model\"").contains("\"messages\"");
    // ★ 判据本身：两个键都不出现。tools 与 tool_choice 是协议里仅有的两个"声明工具"的入口。
    assertThat(body).doesNotContain("\"tools\"").doesNotContain("\"tool_choice\"");
  }

  @Test
  void streamingRequestCarriesNoTools() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "你好")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.BEARER);

    String body = requestBody.get();
    assertThat(body)
        .as("流式对话请求体")
        .contains("\"model\"")
        .contains("\"messages\"")
        .contains("\"stream\":true");
    assertThat(body).doesNotContain("\"tools\"").doesNotContain("\"tool_choice\"");
  }

  /**
   * 端口今天唯一允许的实现是"空表"。
   *
   * <p>★ 这条不是为了覆盖 {@link NoopToolProvider} 的两行代码，而是为了钉住
   * <strong>"没有工具"是一种被写下来的决定</strong>，而不是"恰好没有实现"。
   */
  @Test
  void theOnlyProviderTodayOffersNothing() {
    assertThat(new NoopToolProvider().toolsFor(UUID.randomUUID())).isEmpty();
  }
}
