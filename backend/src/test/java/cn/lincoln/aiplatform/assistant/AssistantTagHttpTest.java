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
 * {@code #195} 标签子项的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>为什么必须走真库</h3>
 *
 * <p>{@code GET /assistants/tags} 的实现在 SQL 里展开 {@code text[]}（{@code select distinct
 * unnest(tags)}）并复用数据范围谓词。 假仓储只能回答"内存里 distinct"，验不到这两件最值得验的事：
 *
 * <ol>
 *   <li>★ <strong>去重</strong>：两个应用用了同一个标签 ⇒ 列表里只出现一次；
 *   <li>★ <strong>范围</strong>：别人的私有应用上的标签<strong>不许</strong>出现在我的下拉框里（看不见的数据从侧面漏出去）；
 *   <li>不按 {@code published} 过滤：草稿/私有应用只要我看得见，它的标签就进筛选（与 mock 语义一致）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantTagHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V29 的 {@code model_id} 没有外键（不是"必须先建模型"），但用一个固定 UUID 让读数稳定。 */
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e2");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void tagsAreDistinctAndScopedToVisibleApps() throws Exception {
    String admin = login("admin", "admin");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String shared = "共用-" + suffix;
    String onlyFirst = "甲-" + suffix;
    String onlySecond = "乙-" + suffix;

    createApp(admin, "标签读数-一-" + suffix, "[\"" + shared + "\",\"" + onlyFirst + "\"]");
    createApp(admin, "标签读数-二-" + suffix, "[\"" + shared + "\",\"" + onlySecond + "\"]");

    HttpResponse<String> adminTags = send(admin, "GET", "/api/v1/assistants/tags", null);
    assertThat(adminTags.statusCode()).as("标签端点应 200，实际：%s", adminTags.body()).isEqualTo(200);
    assertThat(occurrences(adminTags.body(), "\"" + shared + "\""))
        .as("★ 两个应用共用同一个标签 ⇒ 只出现一次（去重）：%s", adminTags.body())
        .isEqualTo(1);
    assertThat(adminTags.body()).contains(onlyFirst).contains(onlySecond);
    assertThat(adminTags.body())
        .as("★ 私有且未发布的应用，只要我看得见，它的标签就该进筛选（不按 published 过滤）")
        .contains(shared);

    // ── ★ 范围：chenjun 是 USER（shared 范围），看不见 admin 的私有应用 ⇒ 也不该看见它们的标签 ──
    String chenjun = login("chenjun", "123456");
    HttpResponse<String> chenjunTags = send(chenjun, "GET", "/api/v1/assistants/tags", null);
    assertThat(chenjunTags.statusCode()).isEqualTo(200);
    assertThat(chenjunTags.body())
        .as("★ 看不见的应用，其标签也不许出现（否则市场下拉框泄露别人私有应用的主题）")
        .doesNotContain(shared)
        .doesNotContain(onlyFirst)
        .doesNotContain(onlySecond);
  }

  private void createApp(String token, String name, String tagsJson) throws Exception {
    HttpResponse<String> created =
        send(
            token,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\""
                + name
                + "\",\"icon\":\"🏷️\",\"description\":\"标签端点读数\",\"type\":\"SIMPLE\","
                + "\"modelId\":\""
                + MODEL
                + "\",\"rolePrompt\":\"你是标签读数助手\",\"tags\":"
                + tagsJson
                + "}");
    assertThat(created.statusCode()).as("建应用应 200，实际：%s", created.body()).isEqualTo(200);
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

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
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
