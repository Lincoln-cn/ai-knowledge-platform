package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 审计**读得出来**（`#258`）。
 *
 * <p>`#257` 只做了写：表在、四类动作都留痕，但没有任何入口能把它读出来 —— 要查"谁把这份文档的密级改了"得连库手写 SQL。那满足"可存储"，不满足等保 2.0 的"可追溯"。
 *
 * <p>★ 这一条测试的形状值得说清：它**先做一次真实的业务动作**（改密级），再去查审计 —— 如果只在库里手插一行再查，那测的是"查询语法对不对"，而不是"真操作会不会留痕、留得够不够查"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AuditReadTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void reclassifyCanBeFoundThroughTheAuditEndpoint() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "审计读-改密级库");
    String docId = createDocument(admin, kbId, "审计读-改密级.docx");
    assertThat(patchClassification(admin, docId, "SECRET").statusCode()).isEqualTo(200);

    HttpResponse<String> res =
        get("/api/v1/audit-logs?resourceType=document&resourceId=" + docId, admin);
    assertThat(res.statusCode()).as("查审计失败：%s", res.body()).isEqualTo(200);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_name, action, resource_type from pf_audit_log"
                + " where resource_id = ? and action = 'document.reclassify'",
            docId);
    assertThat(res.body())
        .as("★ 端点查得到那一条：action / 操作人快照 / 明细都要在响应里")
        .contains("document.reclassify")
        .contains(String.valueOf(row.get("actor_name")))
        // detail 是**字符串里的 JSON**，响应里键名被转义成 \"to\" —— 断言值而不是引号形状
        .contains("SECRET")
        .contains("INTERNAL");
  }

  /** 零记录时端点必须返回 200 + 空列表，不是 404 —— "没有记录"本身是一条有效答案。 */
  @Test
  void noMatchReturnsAnEmptyPageNotAnError() throws Exception {
    String admin = accessToken("admin", "admin");
    HttpResponse<String> res = get("/api/v1/audit-logs?resourceId=" + UUID.randomUUID(), admin);
    assertThat(res.statusCode()).as("查不到不该是错误：%s", res.body()).isEqualTo(200);
    assertThat(res.body()).as("★ 要返回空列表与 total=0，而不是让调用方去猜 null").contains("\"total\":0");
  }

  /** 没有 {@code system:manage} 的人看不到审计（`13 §7.2` 第 1 条：审计比业务数据更敏感）。 */
  @Test
  void auditRequiresSystemManage() throws Exception {
    String operator = accessToken("zhangyun", "123456");
    HttpResponse<String> res = get("/api/v1/audit-logs", operator);
    assertThat(res.statusCode()).as("知识运营不该能读审计：%s", res.body()).isEqualTo(403);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private HttpResponse<String> get(String path, String token) throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private HttpResponse<String> patchClassification(String token, String docId, String level)
      throws Exception {
    return send(
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://127.0.0.1:" + port + "/api/v1/documents/" + docId + "/classification"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .method(
                "PATCH",
                HttpRequest.BodyPublishers.ofString("{\"classification\":\"" + level + "\"}"))
            .timeout(Duration.ofSeconds(20))
            .build());
  }

  private String createKb(String adminToken, String name) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"审计读测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(name, UUID.randomUUID());
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, adminToken);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private String createDocument(String adminToken, String kbId, String name) throws Exception {
    String orgId =
        jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, "LEGAL");
    String body =
        """
        {"names":["%s"],"docType":"制度","orgId":"%s","classification":"INTERNAL"}\
        """
            .formatted(name, orgId);
    HttpResponse<String> res =
        post("/api/v1/knowledge-bases/" + kbId + "/documents", body, adminToken);
    assertThat(res.statusCode()).as("建档失败：%s", res.body()).isEqualTo(200);
    return jdbc.queryForObject(
        "select id::text from kb_document where kb_id = ?::uuid and name = ?"
            + " order by created_at desc limit 1",
        String.class,
        kbId,
        name);
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    String token = jsonField(res.body(), "accessToken");
    if (token == null) {
      throw new IllegalStateException("登录失败：" + res.body());
    }
    return token;
  }

  private HttpResponse<String> post(String path, String body, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return send(builder.build());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String jsonField(String body, String key) {
    if (body == null) {
      return null;
    }
    var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }
}
