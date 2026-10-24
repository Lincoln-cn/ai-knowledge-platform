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
 * {@code #195} 共享写子项的真 HTTP 读数（真 PostgreSQL，不 mock 仓储）。
 *
 * <h3>为什么必须走真库</h3>
 *
 * <p>这个子项最值得验的不是"端点存在"，而是<strong>共享真的改变了谁能看见</strong>：
 *
 * <ol>
 *   <li>★ 写 grant 后，被共享人的<strong>详情</strong>从 404 变 200 —— 这条走 {@code ScopeFilter.canSee}，假仓储能验；
 *   <li>★ 被共享人的<strong>列表</strong>也能命中 —— 这条走 {@code AssistantRepositoryAdapter.grantedToMe} 的
 *       EXISTS 子查询， 只有真 SQL 能验。少了它会出现"详情能看、列表看不到"，而那种故障在页面上看起来像"应用丢了"。
 *   <li>★ 撤回到 PRIVATE 后两条都恢复 404 / 空列表；ROLE 分支同样要能命中（chenjun 的角色是 USER）。
 *   <li>★ 写错主体必须 400：静默写进去的授权永远不会被任何人命中。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantShareHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V29 的 {@code model_id} 没有外键（不是"必须先建模型"），但用一个固定 UUID 让读数稳定。 */
  private static final UUID MODEL = UUID.fromString("00000000-0000-4000-8000-0000000000e3");

  /** 种子账号 chenjun：角色 USER、data_scope=shared ⇒ 只有被共享才看得见 admin 的私有应用。 */
  private static final String CHENJUN = "10000000-0000-0000-0000-000000000004";

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Test
  void grantMakesAppVisibleToListAndDetailUntilRevoked() throws Exception {
    String admin = login("admin", "admin");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String appId = createApp(admin, "共享读数-" + suffix);

    String chenjun = login("chenjun", "123456");

    // ── 初始：私有应用对 chenjun 不存在（404，不是 403） ──
    assertThat(send(chenjun, "GET", "/api/v1/assistants/" + appId, null).statusCode())
        .as("未共享的私有应用应 404")
        .isEqualTo(404);

    // ── 定向共享给 chenjun ──
    HttpResponse<String> granted =
        send(
            admin,
            "PUT",
            "/api/v1/assistants/" + appId + "/grants",
            "{\"grants\":[{\"subject\":{\"kind\":\"USER\",\"id\":\""
                + CHENJUN
                + "\"},\"actions\":[\"assistant:view\"]}]}");
    assertThat(granted.statusCode()).as("写共享应 200，实际：%s", granted.body()).isEqualTo(200);
    assertThat(granted.body())
        .as("有名单 ⇒ visibility 应是 SHARED")
        .contains("\"visibility\":\"SHARED\"");
    assertThat(granted.body()).as("响应要回显名单，共享对话框靠它回填").contains(CHENJUN);

    // ── 被共享人：详情 200；★ 列表也必须命中（EXISTS 子查询） ──
    HttpResponse<String> detail = send(chenjun, "GET", "/api/v1/assistants/" + appId, null);
    assertThat(detail.statusCode()).as("被共享后详情应 200，实际：%s", detail.body()).isEqualTo(200);
    HttpResponse<String> chenjunList =
        send(chenjun, "GET", "/api/v1/assistants?keyword=" + suffix + "&size=100", null);
    assertThat(chenjunList.body())
        .as("★ 被共享的应用必须出现在被共享人的列表里（否则详情能看、列表看不到）")
        .contains(appId)
        .contains("\"total\":1");

    // ── 撤回：PRIVATE ⇒ 名单清空、两条读数都回 404 / 空 ──
    HttpResponse<String> privatized =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/" + appId + "/visibility",
            "{\"visibility\":\"PRIVATE\"}");
    assertThat(privatized.statusCode()).as("切回私有应 200，实际：%s", privatized.body()).isEqualTo(200);
    assertThat(privatized.body()).as("切回私有必须清空名单").doesNotContain(CHENJUN);
    assertThat(send(chenjun, "GET", "/api/v1/assistants/" + appId, null).statusCode())
        .as("撤回后详情应恢复 404")
        .isEqualTo(404);
    assertThat(
            send(chenjun, "GET", "/api/v1/assistants?keyword=" + suffix + "&size=100", null).body())
        .as("撤回后列表也不许再命中")
        .contains("\"total\":0");

    // ── 空名单 + SHARED ⇒ 400（否则界面显示已共享、实际谁都不共享） ──
    HttpResponse<String> emptyShared =
        send(
            admin,
            "PATCH",
            "/api/v1/assistants/" + appId + "/visibility",
            "{\"visibility\":\"SHARED\"}");
    assertThat(emptyShared.statusCode())
        .as("空名单 + SHARED 必须 400：%s", emptyShared.body())
        .isEqualTo(400);
    assertThat(emptyShared.body()).contains("定向共享至少要选一个账号或角色");

    // ── ★ ROLE 分支：共享给角色 USER（chenjun 正是该角色） ──
    HttpResponse<String> roleGrant =
        send(
            admin,
            "PUT",
            "/api/v1/assistants/" + appId + "/grants",
            "{\"grants\":[{\"subject\":{\"kind\":\"ROLE\",\"id\":\"USER\"},\"actions\":[\"assistant:view\"]}]}");
    assertThat(roleGrant.statusCode()).as("按角色共享应 200：%s", roleGrant.body()).isEqualTo(200);
    assertThat(
            send(chenjun, "GET", "/api/v1/assistants?keyword=" + suffix + "&size=100", null).body())
        .as("★ 按角色共享后，角色成员也必须在列表里命中（ROLE 分支）")
        .contains(appId);

    // ── 写错主体 ⇒ 400：静默写入的授权永远不会被任何人命中 ──
    HttpResponse<String> unknownSubject =
        send(
            admin,
            "PUT",
            "/api/v1/assistants/" + appId + "/grants",
            "{\"grants\":[{\"subject\":{\"kind\":\"USER\",\"id\":\""
                + UUID.randomUUID()
                + "\"},\"actions\":[\"assistant:view\"]}]}");
    assertThat(unknownSubject.statusCode())
        .as("未知主体必须 400：%s", unknownSubject.body())
        .isEqualTo(400);
    assertThat(unknownSubject.body()).contains("共享主体不存在");

    // ── 看不见的应用 ⇒ 404（不泄露 id 是否存在） ──
    assertThat(
            send(
                    admin,
                    "PUT",
                    "/api/v1/assistants/" + UUID.randomUUID() + "/grants",
                    "{\"grants\":[]}")
                .statusCode())
        .as("未知应用应 404")
        .isEqualTo(404);
  }

  private String createApp(String token, String name) throws Exception {
    HttpResponse<String> created =
        send(
            token,
            "POST",
            "/api/v1/assistants",
            "{\"name\":\""
                + name
                + "\",\"icon\":\"🤝\",\"description\":\"共享端点读数\",\"type\":\"SIMPLE\","
                + "\"modelId\":\""
                + MODEL
                + "\",\"rolePrompt\":\"你是共享读数助手\"}");
    assertThat(created.statusCode()).as("建应用应 200，实际：%s", created.body()).isEqualTo(200);
    return stringField(created.body(), "id");
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
