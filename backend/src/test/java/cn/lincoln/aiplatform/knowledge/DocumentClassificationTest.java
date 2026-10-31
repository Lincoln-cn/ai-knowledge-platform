package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 文档密级变更（{@code #247}）—— 升密必须<b>同事务</b>把引用过它的会话水位抬上来；降密不动水位。
 *
 * <h3>★ 这个类钉的是"两个写入口之间的那条不变量"</h3>
 *
 * <p>{@code 13 §2.2}：会话密级 = 其引用集合的最大密级。它在生产上一直由 {@code ChatTurnWriter} 保证（引用落库的同一事务里抬水位）。 但文档密级
 * <b>入库时定死</b>这个假设一旦被 {@code #247} 的变更入口打破，"历史会话的水位"就没有任何路径会更新 —— 而它低于自己的引用内容之后，
 * 症状是"这篇文档看得见名字、检索不到正文"，看起来像索引坏了。
 *
 * <h3>★ 夹具为什么直接写 SQL</h3>
 *
 * <p>要验的是"改文档密级"这**一个**动作对会话表的副作用，而不是"一次完整问答会不会落引用"。走真问答要一个能流式的上游（本机没有）， 而引用快照的形状是后端自己的契约（{@code
 * SourceRef} 十字段），直接写一行 jsonb 更贴近被测的不变量：{@code e->>'documentId'} 这一条就是全部依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentClassificationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * ★ 判据 ①：升密 ⇒ 引用过它的会话水位抬到新值，且文档自己也变了。
   *
   * <p>两条断言必须同时存在：只验文档变了，等于没验补偿；只验会话变了，可能是一次"别人的"抬升。
   */
  @Test
  void raisingTheClassificationRaisesTheWatermarkOfReferencingConversations() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "升密库", "SECRET");
    String docId = createDocument(admin, kbId, "升密.docx", "INTERNAL");

    UUID conversationId = UUID.randomUUID();
    seedConversation(conversationId, "INTERNAL");
    seedMessageReferencing(conversationId, docId);
    seedSegment(docId, kbId, "INTERNAL");

    HttpResponse<String> res = patchClassification(admin, docId, "SECRET");

    assertThat(res.statusCode()).as("升密失败：%s", res.body()).isEqualTo(200);
    assertThat(classificationOfDocument(docId))
        .as("文档自己的密级要变 —— 否则补偿抬的是一个没发生的变更")
        .isEqualTo("SECRET");
    assertThat(classificationOfSegment(docId))
        .as("★ 分段是独立列（V22 冗余密级）：不跟着重打，低 clearance 用户照样检索得到，" + "而界面上那篇文档已经显示成「秘密」—— 升密看起来生效、实际没生效")
        .isEqualTo("SECRET");
    assertThat(classificationOfConversation(conversationId))
        .as("★ 会话水位必须跟上：13 §2.2 的不变量一旦破，症状是「文档看得见名字、检索不到正文」")
        .isEqualTo("SECRET");
  }

  /**
   * ★ 判据 ②：降密<strong>允许</strong>，且水位<strong>不跟着降</strong>。
   *
   * <p>会话密级是单调水位（{@code #108}）：停在旧的高档位是安全的严格方向，也是"这个会话的回答曾经引用过什么"的如实记录。 反过来（把水位往下拉）才是真正的越权 ——
   * 这条断言正是为了拦住将来有人"顺手补一个对称的补偿"。
   */
  @Test
  void loweringTheClassificationLeavesTheWatermarkUntouched() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "降密库", "SECRET");
    String docId = createDocument(admin, kbId, "降密.docx", "SECRET");

    UUID conversationId = UUID.randomUUID();
    seedConversation(conversationId, "SECRET");
    seedMessageReferencing(conversationId, docId);

    HttpResponse<String> res = patchClassification(admin, docId, "INTERNAL");

    assertThat(res.statusCode()).as("降密应当允许：%s", res.body()).isEqualTo(200);
    assertThat(classificationOfDocument(docId)).isEqualTo("INTERNAL");
    assertThat(classificationOfConversation(conversationId))
        .as("★ 降密后水位必须仍是 SECRET —— 单调水位只升不降（#108），往下拉就是越权")
        .isEqualTo("SECRET");
  }

  /**
   * ★ 同值变更（幂等）：不报错、不改任何东西。
   *
   * <p>它看起来是个"没用的分支"，但它挡住一种很常见的误读：把"密级已经是这个值"当成"升密"而再抬一次别的会话 —— 接口被重复调用时就会出现。
   */
  @Test
  void changingToTheSameClassificationIsANoOp() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "同值库", "SECRET");
    String docId = createDocument(admin, kbId, "同值.docx", "INTERNAL");

    UUID conversationId = UUID.randomUUID();
    seedConversation(conversationId, "PUBLIC");
    seedMessageReferencing(conversationId, docId);

    HttpResponse<String> res = patchClassification(admin, docId, "INTERNAL");

    assertThat(res.statusCode()).as("同值不算错：%s", res.body()).isEqualTo(200);
    assertThat(classificationOfDocument(docId)).isEqualTo("INTERNAL");
    assertThat(classificationOfConversation(conversationId))
        .as("明文没变 ⇒ 不是一次升密 ⇒ 不该抬任何会话")
        .isEqualTo("PUBLIC");
  }

  /**
   * ★ 库上限（ceiling）不许被这个入口绕过（{@code 13 §6}）：建档时校验了，变更时也必须校验 —— 否则"先建一个 INTERNAL 文档，再改成
   * CONFIDENTIAL"就是一条后门。
   */
  @Test
  void classificationAboveTheKnowledgeBaseCeilingIsRejected() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "上限库", "INTERNAL");
    String docId = createDocument(admin, kbId, "越界.docx", "INTERNAL");

    HttpResponse<String> res = patchClassification(admin, docId, "CONFIDENTIAL");

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).as("原因要含两个密级值，用户才知道差在哪").contains("机密").contains("内部");
    assertThat(classificationOfDocument(docId)).as("被拒 ⇒ 文档密级不变").isEqualTo("INTERNAL");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private String createKb(String adminToken, String name, String maxClassification)
      throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"密级变更测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"%s"}\
        """
            .formatted(name, UUID.randomUUID(), maxClassification);
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, adminToken);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  /** 建档走真接口（元数据校验是它的一部分），随后按库取回刚建的那一篇。 */
  private String createDocument(String adminToken, String kbId, String name, String classification)
      throws Exception {
    String orgId =
        jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, "LEGAL");
    String body =
        """
        {"names":["%s"],"docType":"制度","orgId":"%s","classification":"%s"}\
        """
            .formatted(name, orgId, classification);
    HttpResponse<String> res =
        post("/api/v1/knowledge-bases/" + kbId + "/documents", body, adminToken);
    assertThat(res.statusCode()).as("建档失败：%s", res.body()).isEqualTo(200);
    return jdbc.queryForObject(
        "select id::text from kb_document where kb_id = ?::uuid and name = ? order by created_at"
            + " desc limit 1",
        String.class,
        kbId,
        name);
  }

  private void seedConversation(UUID conversationId, String watermark) {
    UUID userId =
        jdbc.queryForObject("select id from iam_user where username = ?", UUID.class, "admin");
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, message_count, max_classification,"
            + " created_at, updated_at) values (?::uuid, ?::uuid, '密级变更夹具会话', 1, ?, now(),"
            + " now())",
        conversationId.toString(),
        userId.toString(),
        watermark);
  }

  /**
   * 一条助手消息，引用快照里含那篇文档。
   *
   * <p>★ jsonb 的键名是契约字段名 {@code documentId}（{@code SourceRef} 的组件名），不是列名 —— 反查 SQL 用的就是它。
   */
  private void seedMessageReferencing(UUID conversationId, String documentId) {
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status, created_at,"
            + " updated_at, reference_snapshot) values (?::uuid, ?::uuid, 'ASSISTANT', '夹具回答',"
            + " 'GENERATED', now(), now(), ?::jsonb)",
        UUID.randomUUID().toString(),
        conversationId.toString(),
        "[{\"index\":1,\"documentId\":\""
            + documentId
            + "\",\"segmentId\":\""
            + UUID.randomUUID()
            + "\",\"title\":\"夹具\"}]");
  }

  private HttpResponse<String> patchClassification(String token, String docId, String level)
      throws Exception {
    HttpResponse<String> res =
        send(
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/v1/documents/"
                            + docId
                            + "/classification"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(
                    "PATCH",
                    HttpRequest.BodyPublishers.ofString("{\"classification\":\"" + level + "\"}"))
                .timeout(Duration.ofSeconds(20))
                .build());
    return res;
  }

  private String classificationOfDocument(String docId) {
    return jdbc.queryForObject(
        "select classification from kb_document where id = ?::uuid", String.class, docId);
  }

  /**
   * 造一条分段（{@code V22} 的冗余密级是<b>独立列</b>，不是从文档生成的）—— 升密时它必须跟着重打。
   *
   * <p>★ 直接写 SQL 而不走 {@code Segmenter}：这里要验的不是"怎么切块"，而是"切出来的行会不会跟着文档走"。
   */
  private void seedSegment(String docId, String kbId, String level) {
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, content,"
            + " search_text, content_hash, char_count, classification, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, 0, '分段正文', '分段正文', ?, 4, ?,"
            + " now(), now())",
        UUID.randomUUID().toString(),
        docId,
        kbId,
        kbId,
        "0".repeat(64),
        level);
  }

  private String classificationOfSegment(String docId) {
    return jdbc.queryForObject(
        "select classification from kb_segment where document_id = ?::uuid limit 1",
        String.class,
        docId);
  }

  private String classificationOfConversation(UUID conversationId) {
    return jdbc.queryForObject(
        "select max_classification from cv_conversation where id = ?::uuid",
        String.class,
        conversationId.toString());
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
