package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ★ #244 的取样点之二：<b>Spring AI 这一层到底逐帧回调，还是攒到最后一次给</b>。
 *
 * <p>为什么需要一个进程内的小上游而不是复用桩：这条判据要测的是"本进程解析 SSE 的节奏"，与"桩有没有节拍"无关 —— 以前把两件事混在一起，
 * 于是"帧攒到结束"被误记成"桩没节拍"（{@code #237} 的第一种读数）。进程内的小服务器按固定间隔写帧，任何攒包都只能发生在被测代码里。
 *
 * <p>被排除的两层（各自的读数都在 issue 里）：{@code ChatStreamPort → ChatService.pump → assembler → HTTP} 已由 {@code
 * ChatStreamHttpTest.deltaFramesReachTheClientWhileTheModelIsStillGenerating} 证明逐帧；开发服务器代理已由"直连
 * 8080 读数相同"排除。
 */
class SpringAiStreamDeltaTimingTest {

  private static final int FRAMES = 5;
  private static final long GAP_MS = 300;

  private HttpServer upstream;
  private String baseUrl;

  @BeforeEach
  void startUpstream() throws IOException {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.setExecutor(Executors.newCachedThreadPool());
    upstream.createContext(
        "/v1/chat/completions",
        exchange -> {
          try (OutputStream body = exchange.getResponseBody()) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            for (int i = 0; i < FRAMES; i++) {
              String chunk =
                  "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                      + "\"model\":\"timing-probe\",\"choices\":[{\"index\":0,\"delta\":"
                      + "{\"content\":\"第"
                      + (i + 1)
                      + "片\"},\"finish_reason\":null}]}\n\n";
              body.write(chunk.getBytes(StandardCharsets.UTF_8));
              body.flush();
              if (i + 1 < FRAMES) {
                Thread.sleep(GAP_MS);
              }
            }
            body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
            // 客户端提前断开（用例失败时会发生）—— 这里不再抛，避免掩盖真正的断言失败。
          } finally {
            exchange.close();
          }
        });
    upstream.start();
    baseUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
  }

  @AfterEach
  void stopUpstream() {
    upstream.stop(0);
  }

  @Test
  void deltaCallbacksArriveWhileUpstreamIsStillStreaming() {
    SpringAiChatAdapter adapter = new SpringAiChatAdapter(List.of(), true, baseUrl);
    List<Long> stamps = new ArrayList<>();
    long startedAt = System.nanoTime();

    boolean streamed =
        adapter.streamConversation(
            ResolvedUpstream.legacy(baseUrl, "probe-key", ProviderEgress.LOCAL),
            "timing-probe",
            List.of(new ChatMessage(ChatMessage.Role.USER, "数到五")),
            null,
            Duration.ofSeconds(30),
            chunk -> stamps.add((System.nanoTime() - startedAt) / 1_000_000),
            null);

    assertThat(streamed).isTrue();
    assertThat(stamps).hasSize(FRAMES);
    List<Long> gaps = new ArrayList<>();
    for (int i = 1; i < stamps.size(); i++) {
      gaps.add(stamps.get(i) - stamps.get(i - 1));
    }
    assertThat(stamps.get(FRAMES - 1) - stamps.get(0))
        .as(
            "首片到末片的跨度（ms）：%s；相邻间隔：%s —— 若远小于 %dms，说明这一层把帧攒到了最后",
            stamps, gaps, (FRAMES - 1) * GAP_MS)
        .isGreaterThan((FRAMES - 1) * GAP_MS / 2);
  }
}
