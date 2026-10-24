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
 * {@code #195} 导入子项的真 HTTP 读数（真 PostgreSQL）。
 *
 * <h3>为什么走真库</h3>
 *
 * <p>导入要复算三类依赖（模型 / 知识库 / 提示词）并生成新应用；用假仓储只能验"调了哪个方法"，验不到两件最关键的事：
 *
 * <ol>
 *   <li>★ <strong>导入真的落库了</strong>：新 id ≠ 源 id，且新应用能再被读出来；
 *   <li>★ <strong>缺引用时两条路都通</strong>：不跳过 ⇒ 400 说清缺什么；跳过 ⇒ 200 且响应里的 {@code kbIds} 为空
 *       （不是"以为跳过了、其实还挂着一条不可用的引用"）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantImportHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 简单应用创建时**必须**绑一个对话模型（服务层校验）；这里给一个未加载的固定 id，导入时正好走「跳过引用」。 */
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e4");

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void exportThenImportRoundTripsAndMissingReferencesAreExplicit() throws Exception {
    String admin = login("admin", "admin");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String sourceName = "导入源-" + suffix;

    // ── 1. 建一个应用（模型 id 固定但未加载），导出它 ──
    HttpResponse<String> created =
        send(
            admin,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\""
                + sourceName
                + "\",\"icon\":\"📦\",\"description\":\"导入读数\",\"type\":\"SIMPLE\","
                + "\"modelId\":\""
                + MODEL
                + "\",\"rolePrompt\":\"你是导入读数助手\"}");
    assertThat(created.statusCode()).as("建源应用应 200，实际：%s", created.body()).isEqualTo(200);
    String sourceId = stringField(created.body(), "id");

    HttpResponse<String> exported =
        send(admin, "GET", "/api/v1/assistants/" + sourceId + "/export", null);
    assertThat(exported.statusCode()).as("导出应 200，实际：%s", exported.body()).isEqualTo(200);
    String bundle = objectField(exported.body(), "data");
    assertThat(bundle)
        .as("导出包里应有 format 与 config")
        .contains("assistant-bundle@1")
        .contains("\"config\"");

    // ── 2. 检查：当前格式、无缺失 ──
    HttpResponse<String> checked =
        send(
            admin,
            "POST",
            "/api/v1/assistants/import/check",
            "{\"format\":\"assistant-bundle@1\",\"dependencies\":[]}");
    assertThat(checked.statusCode()).as("检查应 200，实际：%s", checked.body()).isEqualTo(200);
    assertThat(checked.body()).contains("\"legacy\":false").contains("\"hasMissing\":false");

    // ── 3. 导入：新建一个应用，不是覆盖源应用 ──
    HttpResponse<String> imported =
        send(
            admin,
            "POST",
            "/api/v1/assistants/import",
            "{\"bundle\":" + bundle + ",\"skipMissingReferences\":true}");
    assertThat(imported.statusCode())
        .as("导入应 200（源应用的模型在本机未加载 ⇒ 必须跳过引用），实际：%s", imported.body())
        .isEqualTo(200);
    String newId = stringField(imported.body(), "id");
    assertThat(newId).as("★ 导入必须新建一个 id").isNotEqualTo(sourceId);
    assertThat(imported.body()).contains(sourceName).contains("\"favorited\":false");
    assertThat(send(admin, "GET", "/api/v1/assistants/" + newId, null).statusCode()).isEqualTo(200);

    // ── 4. 缺引用：先 400 说清，再按「跳过引用」导入 ⇒ kbIds 为空 ──
    String missingKb = UUID.randomUUID().toString();
    String withMissing =
        "{\"format\":\"assistant-bundle@1\",\"exportedAt\":\"2026-10-24T00:00:00Z\","
            + "\"includesDocuments\":false,\"config\":{\"name\":\"导入缺引用-"
            + suffix
            + "\",\"icon\":\"📦\",\"description\":\"缺引用\",\"type\":\"SIMPLE\",\"modelId\":\""
            + MODEL
            + "\",\"kbIds\":[\""
            + missingKb
            + "\"],\"rolePrompt\":\"你是导入读数助手\",\"promptIds\":[],"
            + "\"retrieval\":{\"mode\":\"HYBRID\",\"threshold\":0.6,\"topN\":5,"
            + "\"noRefStrategy\":\"REFUSE\",\"queryCount\":3,\"decomposeEnabled\":true,"
            + "\"maxSubQuestions\":3},\"multiTurn\":true,\"thinkingEnabled\":false,"
            + "\"prologue\":\"\",\"quickQuestions\":[]},\"dependencies\":[]}";

    HttpResponse<String> blocked =
        send(
            admin,
            "POST",
            "/api/v1/assistants/import",
            "{\"bundle\":" + withMissing + ",\"skipMissingReferences\":false}");
    assertThat(blocked.statusCode()).as("有缺失且不跳过 ⇒ 400").isEqualTo(400);
    assertThat(blocked.body()).as("400 必须说清缺什么（模型未加载 + 知识库不可见）").contains("不满足").contains("知识库");

    HttpResponse<String> skipped =
        send(
            admin,
            "POST",
            "/api/v1/assistants/import",
            "{\"bundle\":" + withMissing + ",\"skipMissingReferences\":true}");
    assertThat(skipped.statusCode()).as("跳过引用后应 200，实际：%s", skipped.body()).isEqualTo(200);
    assertThat(skipped.body()).as("★ 不可用的引用必须真的丢掉").contains("\"kbIds\":[]");

    // ── 5. 不认识的格式 ⇒ 400（不是静默按当前格式读）──
    HttpResponse<String> badFormat =
        send(
            admin,
            "POST",
            "/api/v1/assistants/import",
            "{\"bundle\":"
                + withMissing.replace("assistant-bundle@1", "assistant-bundle@0")
                + ",\"skipMissingReferences\":true}");
    assertThat(badFormat.statusCode()).as("旧/未知格式 ⇒ 400").isEqualTo(400);
    assertThat(badFormat.body()).contains("导入包格式");
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

  /**
   * 从 {@code {"code":0,"data":{...}}} 里取嵌套对象原文（含花括号）。
   *
   * <p>不引 JSON 库是为了与同目录其它 HTTP 测试保持一致；这里只需要"原样搬运"，手写一个尊重引号与转义的括号匹配就够了。
   */
  private static String objectField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    assertThat(keyAt).as("响应里应有字段 %s：%s", field, json).isGreaterThanOrEqualTo(0);
    int start = json.indexOf('{', keyAt);
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return json.substring(start, i + 1);
        }
      }
    }
    throw new AssertionError("字段 " + field + " 不是完整对象：" + json);
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
