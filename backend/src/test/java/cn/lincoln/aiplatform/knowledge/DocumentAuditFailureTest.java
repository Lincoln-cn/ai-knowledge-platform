package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.AuditWriteFailedException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 审计写不进去时，业务必须跟着失败（{@code #257} 判据 ④）。
 *
 * <h3>★ 为什么这是"不许静默成功"的判据形状</h3>
 *
 * <p>审计单独提交（{@code REQUIRES_NEW}）看起来很"稳"：业务照常成功，审计失败了记个日志就行。但那会造出 <b>一条没有痕迹的变更</b> ——
 * 事后既审计不到、也无法重放，而这正是审计要防的事。所以端口故意不给"尽力而为"的重载， 写入与业务同事务：审计失败 ⇒ 整笔回滚。
 *
 * <p>本类用一个<b>必抛异常</b>的 {@link AuditLogPort} 替换真实实现，然后打一次改密级：
 *
 * <ol>
 *   <li>请求必须<b>失败</b>（500）—— 不许回 200；
 *   <li>文档的密级必须<b>还是旧值</b> —— 这才证明事务真的回滚了，而不是"先改了再说"。
 * </ol>
 *
 * <p>★ 只有第 1 条会漏掉"审计失败了但业务已经提交"这种实现；两条一起才钉得住原子性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@Import(DocumentAuditFailureTest.BrokenAudit.class)
class DocumentAuditFailureTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @TestConfiguration
  static class BrokenAudit {

    @Bean
    @Primary
    AuditLogPort brokenAuditLogPort() {
      return (action, resourceType, resourceId, actorId, detail) -> {
        // ★ 抛的是真实实现会抛的那个类型（AuditLogAdapter 在数据访问失败时包成它），
        //   不是随手一个 IllegalStateException —— 后者会被全局处理器映射成 400「你传错了」。
        throw new AuditWriteFailedException(
            "审计写入失败（本测试故意制造）：" + action.code(), new IllegalStateException("boom"));
      };
    }
  }

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void auditFailureRollsBackTheBusinessChange() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "审计失败库");
    String docId = createDocument(admin, kbId, "审计失败.docx");

    HttpResponse<String> res = patchClassification(admin, docId, "SECRET");

    assertThat(res.statusCode())
        .as("★ 审计写不进去时请求必须失败 —— 回 200 就等于『变更发生了却没有记录』：%s", res.body())
        .isEqualTo(500);
    assertThat(classificationOf(docId)).as("★ 文档密级必须还是旧值：事务真的回滚了，而不是先改完再报错").isEqualTo("INTERNAL");
    assertThat(countAudit(docId)).as("失败的那一笔也不许留下半截审计").isZero();
  }

  private int countAudit(String docId) {
    return jdbc.queryForObject(
        "select count(*) from pf_audit_log where resource_id = ?", Integer.class, docId);
  }

  private String classificationOf(String docId) {
    return jdbc.queryForObject(
        "select classification from kb_document where id = ?::uuid", String.class, docId);
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
        {"name":"%s","category":"制度规范","description":"审计失败测试用库","icon":"📘",\
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
