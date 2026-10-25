package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * A7「权限生效」的接口侧读数：普通用户（USER 角色）在前端看不到「新建应用」按钮，直接调接口也必须被拒。
 *
 * <p>只断言状态码与「不是 200」不够 —— 403 与 404 的区别是 A7 的全部意义：
 *
 * <ul>
 *   <li>没有 {@code assistant:edit} ⇒ <strong>403</strong>（权限不足，不是资源不存在）；
 *   <li>有 {@code assistant:view} ⇒ 列表 200（权限点不是"一刀切"）。
 * </ul>
 *
 * <p>★ 这条用例的价值在「删掉 {@code @PreAuthorize} 后它会不会红」：把注解去掉，创建接口会对普通用户返回 200 或
 * 400，两种都会让断言失败。它不替代前端按钮的可见性读数（那是 {@code v-permission} 的事），两者合起来才是 A7。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantPermissionHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void normalUserCanReadButCannotCreateOrPublish() throws Exception {
    String admin = login("admin", "admin");
    String user = login("chenjun", "123456");

    HttpResponse<String> list = send(user, "GET", "/api/v1/assistants", null);
    assertThat(list.statusCode())
        .as("USER 有 assistant:view，列表必须 200：%s", list.body())
        .isEqualTo(200);

    HttpResponse<String> create =
        send(
            user,
            "POST",
            "/api/v1/assistants",
            """
            {"name":"越权创建-%s","type":"SIMPLE","modelId":"00000000-0000-4000-8000-0000000000e7","rolePrompt":"越权"}
            """
                .formatted(UUID.randomUUID().toString().substring(0, 8)));
    assertThat(create.statusCode())
        .as("USER 没有 assistant:edit，创建必须 403（不是 404，也不是悄悄 200）：%s", create.body())
        .isEqualTo(403);

    HttpResponse<String> publish =
        send(
            user,
            "PATCH",
            "/api/v1/assistants/00000000-0000-4000-8000-0000000000aa/published",
            "{\"published\":true}");
    assertThat(publish.statusCode())
        .as("USER 没有 assistant:publish，发布必须 403：%s", publish.body())
        .isEqualTo(403);

    // 反向对照：ADMIN 同一路径不是 403（随机 id 不存在 ⇒ 404，但鉴权先过）
    HttpResponse<String> adminPublish =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/00000000-0000-4000-8000-0000000000aa/published",
            "{\"published\":true}");
    assertThat(adminPublish.statusCode())
        .as("同一请求 ADMIN 不该 403，否则上面那条 403 可能只是别的原因：%s", adminPublish.body())
        .isNotEqualTo(403);
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    assertThat(response.statusCode()).as("登录 %s：%s", username, response.body()).isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String stringField(String json, String field) {
    String marker = "\"" + field + "\":\"";
    int start = json.indexOf(marker);
    if (start < 0) {
      throw new AssertionError("响应里没有字符串字段 " + field + "：" + json);
    }
    start += marker.length();
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }
}
