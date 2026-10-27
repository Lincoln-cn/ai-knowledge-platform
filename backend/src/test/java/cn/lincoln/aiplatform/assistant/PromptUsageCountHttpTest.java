package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * {@code #217}：提示词「被引用数」必须只数<strong>还活着</strong>的应用。
 *
 * <p>为什么它需要一条真 HTTP 读数：删应用是<strong>软删</strong>（{@code Assistant#markDeleted} 只写 {@code
 * deleted_at}），绑定表 {@code as_assistant_prompt} 的行不会跟着消失。早期口径只 {@code count} 绑定表 ⇒ 任何被应用用过的提示词
 * <strong>永久删不掉</strong>，而那个应用早就从界面上消失，用户没有任何办法解开这个环；连它的类型也被「该类型下还有 N 个提示词」一起锁死。这条缺陷在 CI
 * 里表现为闸门收不了尾（清夹具时两条 400），而不是表现为某个页面报错。
 *
 * <p>★ 两条用例是<strong>一对</strong>：只有「活着的应用仍然挡删除」也在，才说明修复不是把检查删掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class PromptUsageCountHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void deletingAnAppReleasesThePromptsItBound() throws Exception {
    String token = login();
    String typeId = createType(token, "计口径类型");
    String configId = createConfig(token, "计口径助手", typeId);
    String appId = createApp(token, "计口径应用", configId);

    assertThat(usageCountOf(token, typeId, configId))
        .as("应用还在时，引用数必须是 1 —— 否则下面那条 0 是恒真的")
        .isEqualTo(1);

    HttpResponse<String> deleteApp = send(token, "DELETE", "/api/v1/assistants/" + appId, null);
    // 应用删除走包络（200 + ok:true），提示词删除走 204 —— 两者口径不同，断言也要分开写。
    assertThat(deleteApp.statusCode()).as("删应用应 200，实际：%s", deleteApp.body()).isEqualTo(200);
    assertThat(deleteApp.body()).as("删应用的包络必须是 ok：%s", deleteApp.body()).contains("\"ok\":true");

    assertThat(usageCountOf(token, typeId, configId)).as("★ #217：应用软删之后，引用数必须回到 0").isZero();

    HttpResponse<String> deleteConfig =
        send(token, "DELETE", "/api/v1/prompt-configs/" + configId, null);
    assertThat(deleteConfig.statusCode())
        .as("★ #217：不再被活着的应用引用 ⇒ 提示词必须能删掉，实际：%s", deleteConfig.body())
        .isEqualTo(204);

    HttpResponse<String> deleteType = send(token, "DELETE", "/api/v1/prompt-types/" + typeId, null);
    assertThat(deleteType.statusCode()).as("提示词删掉之后，类型也必须能删").isEqualTo(204);
  }

  @Test
  void aStillLiveAppStillBlocksDeletingItsPrompt() throws Exception {
    String token = login();
    String typeId = createType(token, "计口径类型二");
    String configId = createConfig(token, "计口径助手二", typeId);
    String appId = createApp(token, "计口径应用二", configId);

    HttpResponse<String> blocked =
        send(token, "DELETE", "/api/v1/prompt-configs/" + configId, null);
    assertThat(blocked.statusCode()).as("活着的应用仍必须挡住删除 —— 否则这条修复只是把检查删了").isEqualTo(400);
    assertThat(blocked.body()).contains("该提示词已被 1 个应用引用");

    assertThat(send(token, "DELETE", "/api/v1/assistants/" + appId, null).statusCode())
        .as("删应用应 200（包络口径，与提示词的 204 不同）")
        .isEqualTo(200);
    assertThat(send(token, "DELETE", "/api/v1/prompt-configs/" + configId, null).statusCode())
        .as("同一个提示词，应用删掉之后就该能删")
        .isEqualTo(204);
    assertThat(send(token, "DELETE", "/api/v1/prompt-types/" + typeId, null).statusCode())
        .isEqualTo(204);
  }

  private String createType(String token, String typeName) throws Exception {
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/prompt-types",
            "{\"typeName\":\"" + typeName + "\",\"icon\":\"📄\"}");
    assertThat(response.statusCode()).as("建类型应 201，实际：%s", response.body()).isEqualTo(201);
    return stringField(response.body(), "id");
  }

  private String createConfig(String token, String roleName, String typeId) throws Exception {
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/prompt-configs",
            "{\"roleName\":\""
                + roleName
                + "\",\"callWord\":\"你是"
                + roleName
                + "\",\"typeId\":\""
                + typeId
                + "\",\"description\":\"夹具\",\"icon\":\"📄\"}");
    assertThat(response.statusCode()).as("建提示词应 201，实际：%s", response.body()).isEqualTo(201);
    return stringField(response.body(), "id");
  }

  private String createApp(String token, String name, String configId) throws Exception {
    // ★ 简单应用必须绑一个对话模型（否则 400「简单应用必须绑定一个对话模型」）；
    // 这里只要一个「存在且能绑」的模型 —— 用例从不发问，所以不加载它。
    String modelId = createChatModel(token, name + "模型");
    String body =
        "{\"name\":\""
            + name
            + "\",\"icon\":\"🤖\",\"description\":\"夹具\",\"type\":\"SIMPLE\","
            + "\"modelId\":\""
            + modelId
            + "\",\"rolePrompt\":\"\",\"kbIds\":[],\"promptIds\":[\""
            + configId
            + "\"],\"retrieval\":{\"mode\":\"HYBRID\",\"topK\":5,\"threshold\":0},"
            + "\"multiTurn\":true,\"thinkingEnabled\":false,\"prologue\":\"\",\"quickQuestions\":[]}";
    HttpResponse<String> response = send(token, "POST", "/api/v1/assistants", body);
    assertThat(response.statusCode()).as("建应用应 200，实际：%s", response.body()).isEqualTo(200);
    String id = stringField(response.body(), "id");
    assertThat(id).as("建应用必须回 id").isNotBlank();
    return id;
  }

  private String createChatModel(String token, String name) throws Exception {
    HttpResponse<String> response =
        send(
            token,
            "POST",
            "/api/v1/models",
            "{\"name\":\""
                + name
                + "\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\","
                + "\"baseModel\":\"stub-plain\",\"apiBase\":\"http://localhost:11434/v1\"}");
    assertThat(response.statusCode()).as("建对话模型应 200/201，实际：%s", response.body()).isIn(200, 201);
    return stringField(response.body(), "id");
  }

  private long usageCountOf(String token, String typeId, String configId) throws Exception {
    HttpResponse<String> list = send(token, "GET", "/api/v1/prompt-configs?typeId=" + typeId, null);
    assertThat(list.statusCode()).isEqualTo(200);
    String body = list.body();
    int at = body.indexOf(configId);
    assertThat(at).as("列表里必须能找到刚建的提示词：%s", body).isGreaterThanOrEqualTo(0);
    int keyAt = body.indexOf("\"usageCount\"", at);
    assertThat(keyAt).as("列表项必须带 usageCount：%s", body).isGreaterThanOrEqualTo(0);
    int start = body.indexOf(':', keyAt) + 1;
    int end = start;
    while (end < body.length()
        && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
      end++;
    }
    return Long.parseLong(body.substring(start, end).trim());
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

  private String login() throws Exception {
    HttpResponse<String> response =
        send(null, "POST", "/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}");
    assertThat(response.statusCode()).as("admin 必须能登录").isEqualTo(200);
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
