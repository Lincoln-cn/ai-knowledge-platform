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
 * {@code #196}：提示词类型 / 模板管理端点的真 HTTP 读数。
 *
 * <p>判据：① 建类型 201，重复名 400；② 建模板 201，列表能按类型筛且回 typeName；③ 类型下还有模板时删类型 400（不能静默把模板变成孤儿）；④ 删模板后可删类型，均
 * 204。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class PromptCatalogHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void typeAndConfigLifecycleIsGuardedByReferences() throws Exception {
    String token = login();

    HttpResponse<String> type =
        send(token, "POST", "/api/v1/prompt-types", "{\"typeName\":\"合同类\",\"icon\":\"📄\"}");
    assertThat(type.statusCode()).as("建类型应 201，实际：%s", type.body()).isEqualTo(201);
    String typeId = stringField(type.body(), "id");
    assertThat(typeId).isNotBlank();

    HttpResponse<String> duplicate =
        send(token, "POST", "/api/v1/prompt-types", "{\"typeName\":\"合同类\",\"icon\":\"📄\"}");
    assertThat(duplicate.statusCode()).as("同名类型应 400").isEqualTo(400);

    HttpResponse<String> config =
        send(
            token,
            "POST",
            "/api/v1/prompt-configs",
            "{\"roleName\":\"合同审查助手\",\"callWord\":\"你是合同审查助手\",\"typeId\":\""
                + typeId
                + "\",\"description\":\"审合同\",\"icon\":\"📄\"}");
    assertThat(config.statusCode()).as("建模板应 201，实际：%s", config.body()).isEqualTo(201);
    String configId = stringField(config.body(), "id");
    assertThat(configId).isNotBlank();
    assertThat(config.body()).contains("\"typeName\":\"合同类\"").contains("\"usageCount\":0");

    HttpResponse<String> list = send(token, "GET", "/api/v1/prompt-configs?typeId=" + typeId, null);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("合同审查助手").contains("合同类");

    HttpResponse<String> updateType =
        send(
            token,
            "PUT",
            "/api/v1/prompt-types/" + typeId,
            "{\"typeName\":\"合同类-改\",\"icon\":\"📄\"}");
    assertThat(updateType.statusCode()).as("改类型应 200").isEqualTo(200);
    assertThat(updateType.body()).contains("合同类-改");

    HttpResponse<String> updateConfig =
        send(
            token,
            "PUT",
            "/api/v1/prompt-configs/" + configId,
            "{\"roleName\":\"合同审查助手-改\",\"callWord\":\"你是合同审查助手（改）\"}");
    assertThat(updateConfig.statusCode()).as("改模板应 200").isEqualTo(200);
    assertThat(updateConfig.body()).contains("合同审查助手-改");

    HttpResponse<String> listAfterUpdate =
        send(token, "GET", "/api/v1/prompt-configs?typeId=" + typeId, null);
    assertThat(listAfterUpdate.statusCode()).isEqualTo(200);
    assertThat(listAfterUpdate.body()).contains("合同审查助手-改").contains("合同类-改");

    HttpResponse<String> deleteType = send(token, "DELETE", "/api/v1/prompt-types/" + typeId, null);
    assertThat(deleteType.statusCode()).as("类型下还有模板时不得删除").isEqualTo(400);

    HttpResponse<String> deleteConfig =
        send(token, "DELETE", "/api/v1/prompt-configs/" + configId, null);
    assertThat(deleteConfig.statusCode()).isEqualTo(204);

    HttpResponse<String> deleteTypeAgain =
        send(token, "DELETE", "/api/v1/prompt-types/" + typeId, null);
    assertThat(deleteTypeAgain.statusCode()).isEqualTo(204);
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
