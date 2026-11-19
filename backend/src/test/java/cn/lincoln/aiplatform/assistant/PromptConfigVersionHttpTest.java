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
 * 提示词版本历史（`ROADMAP §11.62`）的真 HTTP 读数。
 *
 * <p>判据（每条都能红）：
 *
 * <ol>
 *   <li>改一次 ⇒ 历史里出现 `v1`，且 `v1` 存的是**改之前**的正文（历史回答"以前长什么样"）；
 *   <li>内容没变再保存一次 ⇒ 版本数不变（点开编辑器又保存不该多一版噪声）；
 *   <li>回滚 ⇒ 生成**新的一版**，旧版本一条都不删（"回滚过"这件事必须留痕）。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class PromptConfigVersionHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void everyRealEditKeepsThePreviousTextAndRollbackOnlyAppends() throws Exception {
    String token = login();
    HttpResponse<String> type =
        send(token, "POST", "/api/v1/prompt-types", "{\"typeName\":\"版本测试类\",\"icon\":\"📄\"}");
    assertThat(type.statusCode()).as("建类型应 201，实际：%s", type.body()).isEqualTo(201);
    String typeId = stringField(type.body(), "id");

    HttpResponse<String> created =
        send(
            token,
            "POST",
            "/api/v1/prompt-configs",
            "{\"roleName\":\"版本测试\",\"callWord\":\"第一版正文\",\"typeId\":\""
                + typeId
                + "\",\"description\":\"\",\"icon\":\"📄\"}");
    assertThat(created.statusCode()).as("建模板应 201，实际：%s", created.body()).isEqualTo(201);
    String configId = stringField(created.body(), "id");

    // 新建不写历史：历史记的是"被换下来"的版本，新建时还没有任何东西被换下来。
    assertThat(versions(token, configId)).as("新建后不该有历史").doesNotContain("\"versionNo\"");

    // ① 改一次 ⇒ v1 存改组之前的正文。
    HttpResponse<String> firstEdit =
        send(token, "PUT", "/api/v1/prompt-configs/" + configId, "{\"callWord\":\"第二版正文\"}");
    assertThat(firstEdit.statusCode()).as("改模板应 200，实际：%s", firstEdit.body()).isEqualTo(200);
    String afterFirst = versions(token, configId);
    assertThat(afterFirst).contains("\"versionNo\":1");
    HttpResponse<String> v1 =
        send(token, "GET", "/api/v1/prompt-configs/" + configId + "/versions/1", null);
    assertThat(v1.statusCode()).isEqualTo(200);
    assertThat(v1.body()).as("v1 必须是改之前的正文 —— 历史表回答的是「以前长什么样」").contains("第一版正文");

    // ② 内容没变再保存一次 ⇒ 不多一版。
    HttpResponse<String> noop =
        send(token, "PUT", "/api/v1/prompt-configs/" + configId, "{\"callWord\":\"第二版正文\"}");
    assertThat(noop.statusCode()).isEqualTo(200);
    assertThat(countVersions(versions(token, configId))).as("内容没变不该产生新版本").isEqualTo(1);

    // ③ 再真改一次 ⇒ v2；回滚到 v1 ⇒ 多出 v3，v1/v2 都还在。
    send(token, "PUT", "/api/v1/prompt-configs/" + configId, "{\"callWord\":\"第三版正文\"}");
    assertThat(countVersions(versions(token, configId))).isEqualTo(2);

    HttpResponse<String> restored =
        send(token, "POST", "/api/v1/prompt-configs/" + configId + "/versions/1/restore", "");
    assertThat(restored.statusCode()).as("回滚应 200，实际：%s", restored.body()).isEqualTo(200);
    assertThat(restored.body()).as("回滚后当前正文应回到 v1 的内容").contains("第一版正文");

    String afterRestore = versions(token, configId);
    assertThat(countVersions(afterRestore)).as("回滚是一次修改，应当再生成一版；删掉历史就没人知道谁把版本倒回去过").isEqualTo(3);
    assertThat(afterRestore).contains("\"versionNo\":2").contains("\"versionNo\":1");

    // 收尾：删模板再删类型，避免影响同容器里的其它测试。
    assertThat(send(token, "DELETE", "/api/v1/prompt-configs/" + configId, null).statusCode())
        .isIn(204, 200);
    assertThat(send(token, "DELETE", "/api/v1/prompt-types/" + typeId, null).statusCode())
        .isIn(204, 200);
  }

  private String versions(String token, String configId) throws Exception {
    HttpResponse<String> response =
        send(token, "GET", "/api/v1/prompt-configs/" + configId + "/versions", null);
    assertThat(response.statusCode()).as("版本列表应 200，实际：%s", response.body()).isEqualTo(200);
    return response.body();
  }

  private static int countVersions(String body) {
    int count = 0;
    int at = 0;
    while ((at = body.indexOf("\"versionNo\"", at)) >= 0) {
      count++;
      at += "\"versionNo\"".length();
    }
    return count;
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
