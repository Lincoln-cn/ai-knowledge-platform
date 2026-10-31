package cn.lincoln.aiplatform.platform.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 指标<b>抓取面</b>的端到端验证（{@code #73} 判据 ① ②）。
 *
 * <p>★ 它对着真实启动的应用发 HTTP，而不是 MockMvc：MockMvc 会绕过真实的过滤器链，而这里要验的恰恰是 「Prometheus
 * 采集端不带令牌能不能读到」——那件事只由安全链决定。
 *
 * <p>★ 为什么必须先记一笔再看：**没被记录过的指标不会出现在导出里**（Micrometer 只在第一次 {@code counter()/timer()}
 * 时才注册序列）。所以"读得到"这句话只有在"真的记过一次"之后才有意义 —— 这也是一条反证：把埋点删掉，这个测试会红在"正文里没有那三行"，而不是红在"端点 404"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class BusinessMetricsEndpointTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String SCRAPE_PATH = "/actuator/prometheus";

  @LocalServerPort int port;

  @Autowired BusinessMetrics metrics;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void prometheusEndpointIsReadableWithoutTokenAndExportsTheThreeBusinessMetrics()
      throws Exception {
    // 先真的记一笔（口径见 BusinessMetrics 的类注释）
    metrics.upstreamCall(BusinessMetrics.UpstreamOutcome.UNREACHABLE);
    metrics.ingestionFailed(BusinessMetrics.IngestionStage.SEGMENT);
    metrics.firstToken(Duration.ofMillis(900).toNanos());

    HttpResponse<String> response = get(SCRAPE_PATH);

    assertThat(response.statusCode())
        .as("★ Prometheus 采集端只带 URL、不带令牌 ⇒ 该端点必须免认证（SecurityConfig）")
        .isEqualTo(200);
    assertThat(response.body())
        .as("三条指标都要能在导出正文里按 Prometheus 命名读到")
        .contains("aiplatform_upstream_calls_total")
        .contains("aiplatform_chat_first_token_seconds")
        .contains("aiplatform_ingestion_failures_total");
    assertThat(response.body())
        .as("首字延迟要带分位数，否则只能看平均值（会抹平少数极慢请求）")
        .contains("aiplatform_chat_first_token_seconds{quantile=\"0.95\"");
  }

  @Test
  void healthStillWorksSoTheSameChainIsNotOverPermissive() throws Exception {
    // 反方向的对照：放行指标不该顺手把别的 actuator 端点也放开（info 仍需授权）
    assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    assertThat(get("/actuator/env").statusCode())
        .as("只放行 health 与 prometheus，别的管理端点仍然要令牌")
        .isIn(401, 403, 404);
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
