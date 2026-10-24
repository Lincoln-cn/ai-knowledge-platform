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
 * {@code #195} 的「可用鉴权链」读数：{@code X-API-Key} 真的能换到一个身份，且这把 Key 只代表签发它的应用。
 *
 * <h3>为什么必须走真 HTTP</h3>
 *
 * <p>这条链路跨四层：servlet 过滤器 → SecurityContext → JWT 声明（角色/范围）→ 方法级授权。单测里手工塞一个 {@code Authentication}
 * 只能证明"塞进去之后能用"，证不了"过滤器真的把它塞进去了"——而后者正是本轮补的那一段。
 *
 * <h3>四条断言各钉什么</h3>
 *
 * <ol>
 *   <li>有效 Key ⇒ 200：链真的通了，不是"配了个没人用的 bean"。
 *   <li>无效 Key ⇒ 401：不能静默退化成匿名（那会让程序化调用看起来成功、实际按匿名范围跑）。
 *   <li>停用后同一把 Key ⇒ 401：页面文案承诺的"不再通过鉴权"是真的。
 *   <li>拿 A 应用的 Key 问 B 应用 ⇒ 403：Key 的权限不因所有者看得见 B 而放大。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantApiKeyAuthHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e6");

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void apiKeyAuthenticatesAsOwnerButStaysBoundToItsApp() throws Exception {
    String admin = login("admin", "admin");

    UUID appA = createApp(admin, "Key 鉴权-A-" + suffix());
    UUID appB = createApp(admin, "Key 鉴权-B-" + suffix());

    String key = stringField(createKey(admin, appA), "key");

    // ① 有效 Key ⇒ 以所有者身份读应用详情（该端点要求 assistant:view）。
    HttpResponse<String> ok = sendWithApiKey(key, "GET", "/api/v1/assistants/" + appA, null);
    assertThat(ok.statusCode()).as("★ 有效 API Key 必须能通过鉴权（否则这条链等于没接）").isEqualTo(200);
    assertThat(ok.body()).as("认证出来的是所有者身份 ⇒ 能看见自己的应用").contains(appA.toString());

    // 使用侧回写 last_used_at：管理页的"最近使用"只能由这里写。
    HttpResponse<String> keys =
        send(admin, "GET", "/api/v1/assistants/" + appA + "/api-keys", null);
    assertThat(keys.statusCode()).isEqualTo(200);
    assertThat(keys.body()).as("★ 成功鉴权后 last_used_at 必须被回写").contains("\"lastUsedAt\":\"");

    // ② 无效 Key ⇒ 401，不许静默降级成匿名。
    HttpResponse<String> bad =
        sendWithApiKey("ask_deadbeef", "GET", "/api/v1/assistants/" + appA, null);
    assertThat(bad.statusCode()).as("★ 无效 Key 必须是 401，不能当匿名放过去").isEqualTo(401);

    // ③ 停用后 ⇒ 401（记录保留，只是不再通过鉴权）。
    String secondBody = createKey(admin, appA);
    String second = stringField(secondBody, "key");
    HttpResponse<String> disabled =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/"
                + appA
                + "/api-keys/"
                + stringField(secondBody, "id")
                + "/enabled",
            "{\"enabled\":false}");
    assertThat(disabled.statusCode()).isEqualTo(200);
    HttpResponse<String> afterDisable =
        sendWithApiKey(second, "GET", "/api/v1/assistants/" + appA, null);
    assertThat(afterDisable.statusCode()).as("★ 停用后同一把 Key 必须 401（页面文案承诺的『不再通过鉴权』）").isEqualTo(401);

    // ④ 拿 A 的 Key 问 B 应用 ⇒ 403：绑定检查在任何落库/模型调用之前。
    String chatBody = "{\"appId\":\"" + appB + "\",\"question\":\"你好\",\"mode\":\"NEW\"}";
    HttpResponse<String> crossApp =
        sendWithApiKey(key, "POST", "/api/v1/conversations/chat", chatBody);
    assertThat(crossApp.statusCode()).as("★ Key 绑定签发它的应用：A 的 Key 不能拿来问 B").isEqualTo(403);
    assertThat(crossApp.body()).contains("不属于该应用");
  }

  // ────────────────────────── 工具 ──────────────────────────

  private UUID createApp(String token, String name) throws Exception {
    String body =
        "{\"name\":\""
            + name
            + "\",\"icon\":\"🔑\",\"description\":\"Key 鉴权读数\",\"type\":\"SIMPLE\",\"modelId\":\""
            + MODEL
            + "\",\"rolePrompt\":\"你是鉴权读数助手\"}";
    HttpResponse<String> res = send(token, "POST", "/api/v1/assistants", body);
    assertThat(res.statusCode()).as("前置：建应用应 200，实际：%s", res.body()).isEqualTo(200);
    return UUID.fromString(stringField(res.body(), "id"));
  }

  private String createKey(String token, UUID appId) throws Exception {
    HttpResponse<String> res =
        send(token, "POST", "/api/v1/assistants/" + appId + "/api-keys", null);
    assertThat(res.statusCode()).as("前置：生成 Key 应 200，实际：%s", res.body()).isEqualTo(200);
    return res.body();
  }

  private String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> res =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(res.statusCode()).as("前置：登录必须成功").isEqualTo(200);
    return stringField(res.body(), "accessToken");
  }

  private HttpResponse<String> sendWithApiKey(
      String rawKey, String method, String path, String json) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("X-API-Key", rawKey);
    if (json == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> send(String token, String method, String path, String json)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (json == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String stringField(String body, String name) {
    String needle = "\"" + name + "\":\"";
    int at = body.indexOf(needle);
    if (at < 0) {
      throw new AssertionError("响应里没有字符串字段 " + name + "：" + body);
    }
    int from = at + needle.length();
    return body.substring(from, body.indexOf('"', from));
  }
}
