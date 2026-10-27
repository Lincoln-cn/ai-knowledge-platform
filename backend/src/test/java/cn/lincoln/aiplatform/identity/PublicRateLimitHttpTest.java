package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 公开面限流的<strong>真 HTTP</strong>读数（{@code #214}，A8 的那一格）。
 *
 * <h3>★ 它与 {@code PublicRateLimitFilterTest} 分别回答什么</h3>
 *
 * <p>那个类验<strong>算得对不对</strong>（第几次开始拒、拒的形状、桶按什么分）， 这个类验<strong>装没装进去</strong>：请求真的走完了整条 Security
 * 链，并且 <strong>在业务代码之前</strong>就被 429 拦住（所以这里只打一条不存在的链接 —— 如果限流器没装上，它会一直是 404，永远不会变成 429）。
 *
 * <p>★ 容量压到 3 而不是用生产值 60：用 60 的话这个类要发 61 次请求才能看到一次 429， 而那 60 次全都会真的进业务层 —— 既慢，也把"限流"变成了"给数据库做压测"。
 * 压小容量不改变被测形状：{@code capacity} 本来就是可配口径。
 *
 * <h3>★ 反证（这条测试为什么不可能"因为实现写了就绿"）</h3>
 *
 * <p>① 把 {@code SecurityConfig} 里的 {@code addFilterBefore(publicRateLimitFilter, …)} 删掉，本类的 {@code
 * theFourthRequestOnTheSameLinkIsRejected} 会退回 404 而红； ② 把 {@link
 * cn.lincoln.aiplatform.platform.security.PublicRateLimitFilter} 的键改成常量 （所有链接共用一个桶），{@code
 * aDifferentLinkKeepsItsOwnQuota} 会红。
 *
 * <p>★★ <strong>反证 ① 已经实测过，而且它第一次没有红 —— 那一次不红本身就是本批最重要的一条读数</strong>：
 *
 * <ul>
 *   <li>只删 {@code addFilterBefore} ⇒ <strong>仍然 3/3 绿</strong>。原因：Boot 会把容器里每个 {@code Filter} 类型
 *       bean 自动注册到 servlet 链，真正干活的是那一份；两个实例又都是 {@code OncePerRequestFilter}， 同请求去重让计数没翻倍，所以缺陷一直隐形。
 *   <li>补上 {@code SecurityConfig#publicRateLimitFilterRegistration}（{@code
 *       setEnabled(false)}，摘掉容器注册） 之后，只删 {@code addFilterBefore} ⇒ <strong>{@code Tests run: 3,
 *       Failures: 2}</strong>（{@code expected: 429}，实得 404）；恢复那一行 ⇒ <strong>3/3 绿</strong>。
 * </ul>
 *
 * <p>⇒ 也就是说：<strong>"位置生效"这件事是被这条测试钉住的，而不是被注释里的写法钉住的</strong>。同族欠账 {@code #218}（{@code
 * ApiKeyAuthenticationFilter} 也是同一个形状）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      "app.security.public-rate-limit.capacity=3",
      // 窗口拉长到 5 分钟：本类的请求都发生在一瞬间，绝不能让"窗口自然滚过去"参与读数
      "app.security.public-rate-limit.window-seconds=300"
    })
class PublicRateLimitHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void theFourthRequestOnTheSameLinkIsRejected() throws Exception {
    String token = "rate-limit-probe-" + java.util.UUID.randomUUID();

    for (int i = 1; i <= 3; i++) {
      HttpResponse<String> allowed = get("/api/v1/public/" + token);
      assertThat(allowed.statusCode())
          .as("第 %d 次在额度内 —— 这条链接不存在，应当是 404 而不是 429", i)
          .isEqualTo(404);
    }

    HttpResponse<String> rejected = get("/api/v1/public/" + token);

    assertThat(rejected.statusCode())
        .as("★ 第 4 次必须是 429：容量配的是 3。若是 404，说明限流器根本没装进链里")
        .isEqualTo(429);
    assertThat(rejected.headers().firstValue("Retry-After"))
        .as("429 必须带 Retry-After，否则客户端只能瞎猜")
        .isPresent();
    assertThat(rejected.body()).as("包络里也要给码与可行动的说法").contains("\"code\":429").contains("秒");
  }

  @Test
  void aDifferentLinkKeepsItsOwnQuota() throws Exception {
    String busy = "rate-limit-busy-" + java.util.UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      get("/api/v1/public/" + busy);
    }
    assertThat(get("/api/v1/public/" + busy).statusCode()).isEqualTo(429);

    HttpResponse<String> other =
        get("/api/v1/public/" + "rate-limit-quiet-" + java.util.UUID.randomUUID());

    assertThat(other.statusCode()).as("配额跟着链接走：一条被刷爆不该让另一条也打不开").isEqualTo(404);
  }

  @Test
  void authenticatedPathsAreNeverThrottledByThisFilter() throws Exception {
    for (int i = 1; i <= 5; i++) {
      HttpResponse<String> response = get("/api/v1/users/me");

      assertThat(response.statusCode())
          .as("第 %d 次未认证访问仍是 401（限流器只管公开面，不该把登录面也按 IP 掐）", i)
          .isEqualTo(401);
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
