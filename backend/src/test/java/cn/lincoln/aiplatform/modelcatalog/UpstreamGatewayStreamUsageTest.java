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
 * {@code #142}：流式上游在最后一个块里给的 {@code usage} 必须被读出来并交给回调。
 *
 * <p>★ 这条是生产适配器的判据：修复前 {@code UpstreamStreamingChatAdapter} 只把 {@code callback::onChunk} 传下去，{@code
 * usage} 在网关上就被丢掉 ⇒ 即使端口有 {@code onUsage}，生产上也不会有人调用它。
 */
class UpstreamGatewayStreamUsageTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private volatile boolean includeUsage = true;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String usage =
              includeUsage ? ",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}" : "";
          String sse =
              "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                  + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]"
                  + usage
                  + "}\n\n"
                  + "data: [DONE]\n\n";
          byte[] body = sse.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
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
  void streamReadsUsageFromTheLastChunkAndForwardsItBeforeEnd() {
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");
    StringBuilder deltas = new StringBuilder();
    AtomicReference<String> usage = new AtomicReference<>();

    boolean streamed =
        gateway.streamChatCompletion(
            "m1",
            baseUrl,
            null,
            List.of(Map.of("role", "user", "content", "hi")),
            Duration.ofSeconds(5),
            deltas::append,
            AuthStyle.NONE,
            null,
            (prompt, completion) -> usage.set(prompt + "/" + completion));

    assertThat(streamed).isTrue();
    assertThat(deltas.toString()).isEqualTo("你好");
    assertThat(usage.get())
        .as("★★ #142：usage 必须在网关上被读出并回调；修复前这里是 null，done.tokens 恒 0")
        .isEqualTo("12/3");
    assertThat(requestBody.get())
        .as("★ OpenAI 只有显式要求才在流末尾回 usage")
        .contains("\"stream_options\":{\"include_usage\":true}");
  }

  @Test
  void streamWithoutUsageDoesNotCallTheUsageCallback() {
    includeUsage = false;
    UpstreamGateway gateway = new UpstreamGateway(baseUrl, "default-key");
    AtomicReference<String> usage = new AtomicReference<>();

    gateway.streamChatCompletion(
        "m1",
        baseUrl,
        null,
        List.of(Map.of("role", "user", "content", "hi")),
        Duration.ofSeconds(5),
        delta -> {},
        AuthStyle.NONE,
        null,
        (prompt, completion) -> usage.set(prompt + "/" + completion));

    assertThat(usage.get()).as("★ 上游没报用量 = 不回调（不是报 0）").isNull();
  }
}
