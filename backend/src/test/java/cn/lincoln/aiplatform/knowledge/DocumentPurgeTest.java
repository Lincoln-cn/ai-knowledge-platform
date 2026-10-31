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

  @Autowired cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort segmentSearch;

  @Autowired cn.lincoln.aiplatform.knowledge.domain.DocumentRepository documentRepository;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void purgingRemovesTheRowItsSegmentsAndItsVectors() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "彻底删除库");
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "彻底删除-已软删.docx");

    assertThat(delete("/api/v1/documents/" + doc.get("documentId"), admin).statusCode())
        .as("软删必须先成功")
        .isEqualTo(200);
    // ★ 软删之后：文档行还在（deleted_at 非空），分段与向量**原样保留**（`#259`）
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

  /**
   * ★★ 软删<b>保留内容、但把它拿出检索面</b>（`#259`）。
   *
   * <p>这条断言此前是反的：当时删除会物理删分段与向量，断言"内容真的没了"。改成留内容之后， 同样的三处读数必须换成新的含义 —— 行还在（能恢复）· `deleted_at`
   * 非空（标记在）· <b>检索里查不到</b>（否则就是"删了还能被答出来"，比不可恢复更糟）。
   *
   * <p>★ 第三条是这条用例真正的价值：`deleted_at` 只被写进库、没进谓词时，前两条照样绿。
   */
  @Test
  void deletingADocumentKeepsContentButTakesItOutOfSearch() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "软删保留内容库");
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "软删-有分段.docx");
    UUID kb = UUID.fromString(kbId);

    assertThat(searchHits(kb)).as("删除之前应该搜得到这一条，否则后面的 0 不算证据").isEqualTo(1);

    assertThat(delete("/api/v1/documents/" + doc.get("documentId"), admin).statusCode())
        .isEqualTo(200);

    assertThat(count("select count(*) from kb_document where id = ?::uuid", doc.get("documentId")))
        .as("软删只落聚合根：行应该还在（这是回收站能看到它的原因）")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from kb_segment where document_id = ?::uuid",
                doc.get("documentId")))
        .as("★ 分段原样留在库里 —— 这是「恢复」不必重新解析、重新向量化的前提")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id = ?::uuid", doc.get("segmentId")))
        .as("★ 向量也留着：重新向量化的成本比重算一次哈希高得多")
        .isEqualTo(1);
    assertThat(searchHits(kb)).as("★ 删除之后检索必须查不到 —— 内容留着不等于仍然答得出来").isZero();
  }

  /** ★ 恢复：把内容放回检索面（`#259`），并留一条 `document.restore` 审计。 */
  @Test
  void restoringBringsTheContentBackIntoSearch() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "回收站恢复库");
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "可恢复.docx");
    UUID kb = UUID.fromString(kbId);
    String id = String.valueOf(doc.get("documentId"));

    assertThat(delete("/api/v1/documents/" + id, admin).statusCode()).isEqualTo(200);
    assertThat(searchHits(kb)).isZero();

    HttpResponse<String> res = post("/api/v1/documents/" + id + "/restore", admin);
    assertThat(res.statusCode()).as("恢复失败：%s", res.body()).isEqualTo(200);
    assertThat(res.body()).contains("可恢复.docx");

    assertThat(
            count("select count(*) from kb_document where id = ?::uuid and deleted_at is null", id))
        .as("文档行回到在用状态")
        .isEqualTo(1);
    assertThat(count("select count(*) from kb_segment where document_id = ?::uuid", id))
        .as("★ 分段从头到尾都在（删除时没动它们）—— 恢复只是让文档那一行的 deleted_at 归零")
        .isEqualTo(1);
    assertThat(searchHits(kb)).as("★ 恢复之后必须重新搜得到").isEqualTo(1);
    assertThat(
            count(
                "select count(*) from pf_audit_log where resource_id = ? and action ="
                    + " 'document.restore'",
                id))
        .as("恢复也是「边界被移动」：删除有痕、恢复没痕等于只记了一半")
        .isEqualTo(1);
  }

  /** ★ 超过保留期不能恢复（默认 7 天；把删除时间回拨 30 天来模拟）。 */
  @Test
  void restoringBeyondTheWindowIsRejected() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "回收站超期库");
    UUID kb = UUID.fromString(kbId);
    Map<String, Object> doc = createDocumentWithSegments(admin, kbId, "超期.docx");
    String id = String.valueOf(doc.get("documentId"));

    assertThat(delete("/api/v1/documents/" + id, admin).statusCode()).isEqualTo(200);
    jdbc.update(
        "update kb_document set deleted_at = now() - interval '30 days' where id = ?::uuid", id);

    HttpResponse<String> res = post("/api/v1/documents/" + id + "/restore", admin);
    assertThat(res.statusCode()).as("超期恢复必须是 400：%s", res.body()).isEqualTo(400);
    assertThat(res.body()).as("拒绝理由要说清是保留期，而不是笼统的「不能恢复」").contains("保留期");
    assertThat(
            count(
                "select count(*) from kb_document where id = ?::uuid and deleted_at is not null",
                id))
        .as("被拒绝时文档必须还留在回收站里")
        .isEqualTo(1);
    assertThat(searchHits(kb)).as("被拒绝时内容不许回到检索面").isZero();
  }

  /**
   * 回收站：软删的进得来，在用的进不来（`#75` 判据 ④ 的"入口"那一半）。
   *
   * <p>★ 这条不是为了测分页：是为了证明「彻底删除有地方可点」这件事真的成立 —— 端点存在但列表看不见已删文档时，那个不可恢复的动作只能由运维用 curl 调。
   */
  @Test
  void theRecycleBinListsSoftDeletedDocumentsOnly() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "回收站库");
    String gone = createDocument(admin, kbId, "回收站-已删.docx");
    String kept = createDocument(admin, kbId, "回收站-在用.docx");
    assertThat(delete("/api/v1/documents/" + gone, admin).statusCode()).isEqualTo(200);

    HttpResponse<String> res = get("/api/v1/knowledge-bases/" + kbId + "/documents/deleted", admin);
    assertThat(res.statusCode()).as("回收站查不了：%s", res.body()).isEqualTo(200);
    assertThat(res.body()).contains("回收站-已删.docx");
    assertThat(res.body()).as("在用的文档不该出现在回收站").doesNotContain("回收站-在用.docx");
    assertThat(res.body()).contains("\"total\":1");
    assertThat(kept).isNotBlank();
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

  /**
   * ★ 超期清理用的那条查询（`#259`）：按「删除时间早于 cutoff」跨库捞，且**不按库、不按密级过滤** （清理是系统在按留存期执行策略，不是某个人在读）。
   *
   * <p>★ 这条走真栈而不只是 Mockito：`Specification` 拼错了只有真查一次才看得见 —— 而清理作业恰恰是"平时没人看"的那类，错了往往等到磁盘满了才发现。
   */
  @Test
  void theRetentionQueryFindsOnlyExpiredDocuments() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "超期清理查询库");
    Map<String, Object> fresh = createDocumentWithSegments(admin, kbId, "刚删.docx");
    Map<String, Object> old = createDocumentWithSegments(admin, kbId, "早就删了.docx");
    for (Map<String, Object> doc : java.util.List.of(fresh, old)) {
      assertThat(delete("/api/v1/documents/" + doc.get("documentId"), admin).statusCode())
          .isEqualTo(200);
    }
    jdbc.update(
        "update kb_document set deleted_at = now() - interval '30 days' where id = ?::uuid",
        old.get("documentId"));

    var expired =
        documentRepository.findDeletedBefore(
            java.time.Instant.now().minus(java.time.Duration.ofDays(7)), 10);

    assertThat(expired)
        .extracting(d -> d.id().toString())
        .contains(String.valueOf(old.get("documentId")));
    assertThat(expired)
        .extracting(d -> d.id().toString())
        .as("刚删的（未超期）不许被清理作业捞走")
        .doesNotContain(String.valueOf(fresh.get("documentId")));
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

  /**
   * 用这条用例里的正文去检索，返回命中条数。
   *
   * <p>★ 走真实的 {@link cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort}（不是直接查 SQL）： 谓词在
   * {@code JdbcSegmentSearchQuery.SECURITY_PREDICATE} 里，只有经过它才证明得了「软删不进检索面」。
   */
  private long searchHits(UUID kbId) {
    UUID me = adminUserId();
    return cn.lincoln.aiplatform.support.SecurityContexts.asUser(
        me,
        cn.lincoln.aiplatform.platform.access.ClassificationLevel.CONFIDENTIAL,
        () ->
            (long)
                segmentSearch
                    .fullText(
                        new cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort.TextQuery(
                            "彻底删除测试正文",
                            new cn.lincoln.aiplatform.platform.retrieval.RetrievalScope(
                                java.util.List.of(kbId), null, null, me),
                            20))
                    .size());
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

  private HttpResponse<String> post(String path, String token) throws Exception {
    return post(path, null, token);
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .GET()
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
