package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code #195} 收藏子项的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>为什么必须走真库</h3>
 *
 * <p>{@code favorite=true} 的筛选是一条<strong>相关 EXISTS 子查询</strong>，{@code favorited} 是另一条批量 IN 查询 ——
 * 两者在单元测试的假仓储里都退化成"内存里 contains"，那正好会把最值得验的东西（SQL 谓词写对没有）验成绿。 所以这里只断言<strong>端点读数</strong>：
 *
 * <ol>
 *   <li>默认 {@code favorited:false}；收藏 ⇒ 200 且 {@code true}，<strong>重复收藏不报错</strong>；
 *   <li>列表 {@code favorite=true} 只回收藏过的（{@code total} 与页内条数同口径）；
 *   <li>取消 ⇒ {@code false}、列表 {@code total:0}，<strong>重复取消也不报错</strong>；
 *   <li>★ <strong>按用户隔离</strong>：liwei 看到的 admin 收藏过/没收藏过的状态互不影响；
 *   <li>看不见 / 不存在的应用 ⇒ 404（与 {@code get} 同口径）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantFavoriteHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V29 的 {@code model_id} 没有外键（不是"必须先建模型"），但用一个固定 UUID 让读数稳定。 */
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e1");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void favoriteIsIdempotentPerUserAndFiltersTheList() throws Exception {
    String admin = login("admin", "admin");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String name = "收藏读数-" + suffix;

    HttpResponse<String> created =
        send(
            admin,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\""
                + name
                + "\",\"icon\":\"⭐\",\"description\":\"收藏端点读数\",\"type\":\"SIMPLE\","
                + "\"modelId\":\""
                + MODEL
                + "\",\"rolePrompt\":\"你是收藏读数助手\"}");
    assertThat(created.statusCode()).as("建应用应 200，实际：%s", created.body()).isEqualTo(200);
    String id = stringField(created.body(), "id");
    assertThat(id).isNotBlank();
    assertThat(created.body()).as("新应用默认未收藏").contains("\"favorited\":false");

    HttpResponse<String> detailBefore = send(admin, "GET", "/api/v1/assistants/" + id, null);
    assertThat(detailBefore.statusCode()).isEqualTo(200);
    assertThat(detailBefore.body()).contains("\"favorited\":false");

    HttpResponse<String> favorited =
        send(admin, "POST", "/api/v1/assistants/" + id + "/favorite", null);
    assertThat(favorited.statusCode()).as("收藏应 200，实际：%s", favorited.body()).isEqualTo(200);
    assertThat(favorited.body()).contains("\"favorited\":true");

    HttpResponse<String> favoritedAgain =
        send(admin, "POST", "/api/v1/assistants/" + id + "/favorite", null);
    assertThat(favoritedAgain.statusCode()).as("重复收藏必须幂等").isEqualTo(200);
    assertThat(favoritedAgain.body()).contains("\"favorited\":true");

    HttpResponse<String> detailAfter = send(admin, "GET", "/api/v1/assistants/" + id, null);
    assertThat(detailAfter.body()).as("详情页也要点亮心形").contains("\"favorited\":true");

    HttpResponse<String> filtered =
        send(admin, "GET", "/api/v1/assistants?favorite=true&keyword=" + suffix, null);
    assertThat(filtered.statusCode()).isEqualTo(200);
    assertThat(filtered.body()).as("只看收藏应回这一条").contains("\"total\":1").contains(id);

    // ── ★ 按用户隔离：liwei 看到的是"自己没收藏" ──
    String liwei = login("liwei", "123456");
    HttpResponse<String> liweiDetail = send(liwei, "GET", "/api/v1/assistants/" + id, null);
    assertThat(liweiDetail.statusCode()).isEqualTo(200);
    assertThat(liweiDetail.body()).as("收藏是按用户存的，不是应用上的全局开关").contains("\"favorited\":false");

    HttpResponse<String> liweiFavorited =
        send(liwei, "POST", "/api/v1/assistants/" + id + "/favorite", null);
    assertThat(liweiFavorited.statusCode()).isEqualTo(200);
    assertThat(liweiFavorited.body()).contains("\"favorited\":true");

    // ── admin 取消，不影响 liwei ──
    HttpResponse<String> unfavorited =
        send(admin, "DELETE", "/api/v1/assistants/" + id + "/favorite", null);
    assertThat(unfavorited.statusCode()).as("取消收藏应 200").isEqualTo(200);
    assertThat(unfavorited.body()).contains("\"favorited\":false");

    HttpResponse<String> unfavoritedAgain =
        send(admin, "DELETE", "/api/v1/assistants/" + id + "/favorite", null);
    assertThat(unfavoritedAgain.statusCode()).as("重复取消必须幂等").isEqualTo(200);
    assertThat(unfavoritedAgain.body()).contains("\"favorited\":false");

    HttpResponse<String> adminEmpty =
        send(admin, "GET", "/api/v1/assistants?favorite=true&keyword=" + suffix, null);
    assertThat(adminEmpty.body()).as("取消后 admin 的收藏列表应为空").contains("\"total\":0");

    HttpResponse<String> liweiStill =
        send(liwei, "GET", "/api/v1/assistants?favorite=true&keyword=" + suffix, null);
    assertThat(liweiStill.body())
        .as("★ liwei 的收藏不受 admin 取消影响")
        .contains("\"total\":1")
        .contains(id);

    // ── 看不见 / 不存在 ⇒ 404（不泄露 id 是否存在）──
    HttpResponse<String> unknown =
        send(admin, "POST", "/api/v1/assistants/" + UUID.randomUUID() + "/favorite", null);
    assertThat(unknown.statusCode()).as("不存在应 404，实际：%s", unknown.body()).isEqualTo(404);
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(response.statusCode()).as("%s 必须能登录：%s", username, response.body()).isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return end < 0 ? null : json.substring(start, end);
  }
}
