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
 * 彻底删除（`#75` 判据 ④）：软删之后还能把行删掉，且**分段与向量一起消失**。
 *
 * <p>★ 这条测试要证明的不是「能删」，而是「删干净」：只删文档行会让分段变成孤儿，而孤儿分段在检索里**照样命中** （检索谓词按 `kb_segment`
 * 查，从不看文档是否还在）。所以三条断言必须一起出现： 文档行没了、分段行没了、向量行没了。少任何一条，"彻底删除"就只是"列表里看不见"。
 *
 * <p>★ 第二条断言的方向是**拒绝**：没软删过的文档不许彻底删除。两者共用一个入口时， 少传一个参数就会把"从列表消失"当成"材料不再存在"执行 —— 那一步不可恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentPurgeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void purgingRemovesTheRowItsSegmentsAndItsVectors() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "彻底删除库");
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "彻底删除-已软删.docx");

    assertThat(delete("/api/v1/documents/" + doc.get("documentId"), admin).statusCode())
        .as("软删必须先成功")
        .isEqualTo(200);
    // ★ 软删之后：文档行还在（deleted_at 非空），分段与向量已被软删删掉
    assertThat(count("select count(*) from kb_document where id = ?::uuid", doc.get("documentId")))
        .as("软删只落聚合根：行应该还在")
        .isEqualTo(1);

    HttpResponse<String> purge =
        delete("/api/v1/documents/" + doc.get("documentId") + "/purge", admin);
    assertThat(purge.statusCode()).as("彻底删除失败：%s", purge.body()).isEqualTo(200);

    assertThat(count("select count(*) from kb_document where id = ?::uuid", doc.get("documentId")))
        .as("文档行必须真的没了（不是只把 deleted_at 改成非空）")
        .isZero();
    assertThat(
            count(
                "select count(*) from kb_segment where document_id = ?::uuid",
                doc.get("documentId")))
        .as("★ 分段必须一起删：孤儿分段在检索里照样命中，而列表里看不见")
        .isZero();
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id = ?::uuid", doc.get("segmentId")))
        .as("★ 向量必须一起删（否则按向量召回的路径仍能命中已删材料）")
        .isZero();

    // ★ 审计留痕：材料没了，但"它被彻底删除了"这条记录必须在
    Map<String, Object> row =
        jdbc.queryForMap(
            "select actor_name, resource_type, detail->>'name' as name from pf_audit_log"
                + " where resource_id = ? and action = 'document.purge'",
            doc.get("documentId"));
    assertThat(row.get("actor_name")).isEqualTo("管理员");
    assertThat(row.get("resource_type")).isEqualTo("DOCUMENT");
    assertThat(row.get("name")).isEqualTo("彻底删除-已软删.docx");
  }

  @Test
  void purgingADocumentThatIsStillInUseIsRejected() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "彻底删除-未软删库");
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "彻底删除-在用.docx");

    HttpResponse<String> res =
        delete("/api/v1/documents/" + doc.get("documentId") + "/purge", admin);

    assertThat(res.statusCode()).as("未软删就彻底删除必须是 400：%s", res.body()).isEqualTo(400);
    assertThat(res.body()).as("拒绝理由要可读，并指出下一步").contains("还在使用中").contains("先从文档列表里删除");
    assertThat(count("select count(*) from kb_document where id = ?::uuid", doc.get("documentId")))
        .as("被拒绝时什么都不许发生")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from pf_audit_log where resource_id = ? and action ="
                    + " 'document.purge'",
                doc.get("documentId")))
        .as("没发生的动作不留痕")
        .isZero();
  }

  /* ── 夹具与请求 ── */

  /** 建库 + 建档 + 直接写入一条分段与一条向量：不跑真实向量化（本机没有嵌入模型）。 */
  private Map<String, Object> createDocumentWithSegments(
      String adminToken, String kbId, String name) throws Exception {
    String documentId = createDocument(adminToken, kbId, name);
    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, content,"
            + " search_text, content_hash, char_count, enabled, embedding_status, created_at,"
            + " updated_at, classification) values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, 0, ?,"
            + " ?, ?, ?, true, 'EMBEDDED', now(), now(), (select classification from kb_document"
            + " where id = ?::uuid))",
        segmentId,
        documentId,
        kbId,
        kbId,
        "彻底删除测试正文",
        "彻底删除测试正文",
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        "彻底删除测试正文".length(),
        documentId);
    // ★ 表名带维度后缀（`V16__knowledge_vec_embedding.sql`）：`vec_embedding_768` 的列是
    //   `id / content / metadata / embedding`，**主键就是 segment id**（一对一，幂等 upsert 的根据）
    jdbc.update(
        "insert into vec_embedding_768 (id, content, metadata, embedding)"
            + " values (?, '彻底删除测试正文', ?::json,"
            + " cast(array_fill(0.1::real, array[768]) as vector))",
        segmentId,
        "{\"document_id\":\"" + documentId + "\",\"kb_id\":\"" + kbId + "\"}");
    return Map.of("documentId", documentId, "segmentId", segmentId.toString());
  }

  private long count(String sql, Object... args) {
    Long value = jdbc.queryForObject(sql, Long.class, args);
    return value == null ? 0 : value;
  }

  private UUID adminUserId() {
    return UUID.fromString(
        jdbc.queryForObject(
            "select id::text from iam_user where username = 'admin'", String.class));
  }

  private String createKb(String adminToken, String name) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"彻底删除测试用库","icon":"📘",\
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
    assertThat(res.statusCode()).as("登录失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "accessToken");
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
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .DELETE()
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String jsonField(String body, String field) {
    int idx = body.indexOf("\"" + field + "\":");
    if (idx < 0) {
      throw new IllegalArgumentException("响应里没有字段 " + field + "：" + body);
    }
    int start = body.indexOf('"', idx + field.length() + 3) + 1;
    int end = body.indexOf('"', start);
    return body.substring(start, end);
  }
}
