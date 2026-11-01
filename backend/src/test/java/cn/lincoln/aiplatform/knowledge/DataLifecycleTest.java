package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 数据生命周期的<b>当前真相</b>（{@code #75} 判据 ②）。
 *
 * <p>★ 这条测试要防的不是"删除功能坏了"，而是<b>"以为删了，其实没删"</b>：
 *
 * <ul>
 *   <li><b>软删</b>（业务删除）只写 {@code deleted_at} —— 行仍在表里，备份里当然也有。
 *   <li>因此"数据主体要求删除"<b>不能用软删来回答</b>；彻底删除（物理删除 + 脱敏摘要）是 {@code #75} 第二段（0.5.x）的事。
 *   <li>★ 但这篇文档的<b>内容</b>已经真的没了：{@code DocumentService#delete} 把分段与向量<b>物理删除</b>，
 *       否则"列表看不见、检索却答得出来"—— 那是最难排查的一种。
 * </ul>
 *
 * <p>★ 将来实现彻底删除时，这条断言要<b>被替换</b>而不是被删掉：换成"行也不在了 + 审计里留了脱敏摘要"。 直接删掉它等于失去"曾经有一条断言守过这件事"的证据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DataLifecycleTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void softDeleteKeepsTheRowSoDeletionIsNotWhatItLooksLike() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "生命周期库", "INTERNAL");
    UUID docId = DocumentFixture.insertPending(jdbc, kbId, "留存期取证文档", "INTERNAL");

    HttpResponse<String> deleted = send("DELETE", "/api/v1/documents/" + docId, null, admin);
    assertThat(deleted.statusCode()).as("删除接口本身要成功").isEqualTo(200);

    // ★ 判据 ② 的正面：界面与接口上"删掉了"
    assertThat(idsInList(admin, kbId)).doesNotContain(docId.toString());

    // ★ 判据 ② 的反面：库里那一行还在，只是 deleted_at 有值。
    //   如果哪天有人把它改成物理删除，这条会红 —— 那一刻必须同步 #75 的处置与 02 §5.1.1 的口径。
    Map<String, Object> row =
        jdbc.queryForMap(
            "select deleted_at, updated_by from kb_document where id = ?::uuid", docId.toString());
    assertThat(row.get("deleted_at"))
        .as("★ 软删 = 行仍在 + deleted_at 有值。这一条就是「删了」与「看不见了」的分界")
        .isNotNull();
    // ★ 如实记账：表里**没有 deleted_by 列**，谁删的只落在 updated_by 上。
    //   这条断言说明"有人删过"，但也暴露了口径的脆弱：之后任何一次改名都会把 updated_by 覆盖掉，
    //   于是「谁删的」就查不出来了 —— 属 #75 记录的缺口之一，不是这里的 bug。
    assertThat(row.get("updated_by")).as("删除人只落在 updated_by 上（没有独立列）").isNotNull();
  }

  private java.util.List<String> idsInList(String token, String kbId) throws Exception {
    HttpResponse<String> res =
        get("/api/v1/knowledge-bases/" + kbId + "/documents?page=1&size=100", token);
    assertThat(res.statusCode()).isEqualTo(200);
    java.util.List<String> ids = new java.util.ArrayList<>();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(res.body());
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return ids;
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        send(
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    String token = field(res.body(), "accessToken");
    if (token == null) {
      throw new IllegalStateException("登录失败：" + res.body());
    }
    return token;
  }

  private String createKb(String token, String name, String maxClassification) throws Exception {
    String body =
        "{\"name\":\""
            + name
            + "\",\"category\":\"制度规范\",\"description\":\"生命周期测试用库\",\"icon\":\"📘\","
            + "\"importType\":\"LOCAL\",\"embeddingModelId\":\""
            + UUID.randomUUID()
            + "\",\"maxClassification\":\""
            + maxClassification
            + "\"}";
    HttpResponse<String> res = send("POST", "/api/v1/knowledge-bases", body, token);
    String id = field(res.body(), "id");
    if (id == null) {
      throw new IllegalStateException("建库失败：" + res.body());
    }
    return id;
  }

  private static String field(String json, String key) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send("GET", path, null, token);
  }

  private HttpResponse<String> send(String method, String path, String body, String token)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
