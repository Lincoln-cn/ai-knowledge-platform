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
 * {@code #195} API Key 子项的真 HTTP 读数（真 PostgreSQL）。
 *
 * <h3>为什么走真库</h3>
 *
 * <p>三条端点都要落到 {@code as_assistant_api_key} 上，用假仓储验不到"轮换出来的是两行、停用的是同一行"。这里钉四件事：
 *
 * <ol>
 *   <li>新应用没有 Key；生成后能列出，且<strong>明文 key 带 {@code ask_} 前缀</strong>；
 *   <li>★ <strong>轮换只新增</strong>：两把 Key 的 id/值都不同，列表里两把都在（旧 Key 没被悄悄吊销）；
 *   <li>停用是真的落库（再查列表仍能看到它，但 {@code enabled=false}）；不属于该应用的 Key ⇒ 404；
 *   <li>★ <strong>只有所有者能管理</strong>：同租户能编辑别人应用的人（liwei）拿到 403，而不是 200。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantApiKeyHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 简单应用创建时必须绑一个对话模型（服务层校验）。 */
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e5");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void apiKeysRotateKeepOldOnesAndOnlyOwnerManagesThem() throws Exception {
    String admin = login("admin", "admin");
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    HttpResponse<String> created =
        send(
            admin,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\"Key 读数-"
                + suffix
                + "\",\"icon\":\"🔑\",\"description\":\"API Key 读数\",\"type\":\"SIMPLE\","
                + "\"modelId\":\""
                + MODEL
                + "\",\"rolePrompt\":\"你是 Key 读数助手\"}");
    assertThat(created.statusCode()).as("建应用应 200，实际：%s", created.body()).isEqualTo(200);
    String appId = stringField(created.body(), "id");

    // ── 1. 新应用没有 Key ──
    HttpResponse<String> empty =
        send(admin, "GET", "/api/v1/assistants/" + appId + "/api-keys", null);
    assertThat(empty.statusCode()).as("列表应 200，实际：%s", empty.body()).isEqualTo(200);
    assertThat(empty.body()).contains("\"data\":[]");

    // ── 2. 生成两把：轮换只新增，旧的仍在 ──
    HttpResponse<String> first =
        send(admin, "POST", "/api/v1/assistants/" + appId + "/api-keys", "{}");
    assertThat(first.statusCode()).as("生成应 200，实际：%s", first.body()).isEqualTo(200);
    String firstId = stringField(first.body(), "id");
    String firstKey = stringField(first.body(), "key");
    assertThat(firstKey).as("Key 前缀").startsWith("ask_");
    assertThat(first.body()).contains("\"enabled\":true");

    HttpResponse<String> second =
        send(admin, "POST", "/api/v1/assistants/" + appId + "/api-keys", "{}");
    assertThat(second.statusCode()).as("再次生成应 200，实际：%s", second.body()).isEqualTo(200);
    String secondId = stringField(second.body(), "id");
    String secondKey = stringField(second.body(), "key");
    assertThat(secondId).isNotEqualTo(firstId);
    assertThat(secondKey).isNotEqualTo(firstKey);

    HttpResponse<String> listed =
        send(admin, "GET", "/api/v1/assistants/" + appId + "/api-keys", null);
    assertThat(listed.body())
        .as("★ 轮换不吊销旧的：两把都必须在列表里")
        .contains(firstId)
        .contains(secondId)
        .doesNotContain("\"data\":[]");

    // ── 3. 停用第一把：真的落库；不存在的 Key ⇒ 404 ──
    HttpResponse<String> disabled =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/" + appId + "/api-keys/" + firstId + "/enabled",
            "{\"enabled\":false}");
    assertThat(disabled.statusCode()).as("停用应 200，实际：%s", disabled.body()).isEqualTo(200);
    assertThat(disabled.body()).contains("\"enabled\":false");

    HttpResponse<String> relisted =
        send(admin, "GET", "/api/v1/assistants/" + appId + "/api-keys", null);
    assertThat(relisted.body())
        .as("停用的 Key 仍留在列表里（不是删除）")
        .contains(firstId)
        .contains("\"enabled\":false");

    HttpResponse<String> unknownKey =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/" + appId + "/api-keys/" + UUID.randomUUID() + "/enabled",
            "{\"enabled\":false}");
    assertThat(unknownKey.statusCode()).as("不属于该应用的 Key ⇒ 404").isEqualTo(404);

    // ── 4. 只有所有者能管理：liwei 有 assistant:edit、data_scope=all，能看见但拿不到 Key ──
    String liwei = login("liwei", "123456");
    HttpResponse<String> foreign =
        send(liwei, "POST", "/api/v1/assistants/" + appId + "/api-keys", "{}");
    assertThat(foreign.statusCode()).as("非所有者签发 Key ⇒ 403，实际：%s", foreign.body()).isEqualTo(403);
    assertThat(foreign.body()).contains("所有者");

    // ── 5. 不存在的应用 ⇒ 404 ──
    HttpResponse<String> unknownApp =
        send(admin, "GET", "/api/v1/assistants/" + UUID.randomUUID() + "/api-keys", null);
    assertThat(unknownApp.statusCode()).as("看不见的应用 ⇒ 404").isEqualTo(404);
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
