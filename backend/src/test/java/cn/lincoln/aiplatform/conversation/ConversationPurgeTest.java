package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 会话的彻底删除（`#299` 第一块 · `02 §5.1.1` 第二半）——「彻底删除后数据真的不在」，**按表逐项**断言。
 *
 * <p>★ 判据形状与 `#75` 的 {@code softDeleteKeepsTheRowSoDeletionIsNotWhatItLooksLike} 成对：
 * 那条钉"软删后数据仍在"，这条钉"彻底删除后数据真的不在"。两张读数表合起来才闭合"删除"这个词。
 *
 * <h3>逐项清单（哪张表、删前多少、删后多少）</h3>
 *
 * <p>会话 A（被删）+ 会话 B（对照组，一根手指都不许碰）： {@code cv_conversation} 2→1 · {@code cv_message} 4→2 · {@code
 * cv_conversation_feedback} 2→1 · {@code cv_feedback_state} 2→1 · {@code cv_share} 2→1 · {@code
 * kb_document}（CONVERSATION 域）2→1 · {@code kb_segment}（A 的）→0 · {@code vec_embedding_768}（A 的）→0 ·
 * {@code cv_file} 2→1 · 磁盘字节（A 的）在→不在 · {@code pf_audit_log} +2（{@code conversation.purge} + {@code
 * attachment.purge}）。
 *
 * <p>★ 引用快照该保留的仍保留：{@code pf_audit_log} 只追加不删（`13 §7.2` 第 3 条）—— 业务数据消失得越干净， "发生过什么"越只能靠它回答。
 *
 * <p>★ `#259` 的分段判断：软删时分段不物理删（可恢复）；**彻底删除必须物理删分段与向量**（`02 §5.1.1` 行 587） —— 分段表的 content
 * 就是正文摘录，留着的话"彻底删除"只删了个标题。这条测试就是那句话的读数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ConversationPurgeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired FileStorePort store;

  @Autowired ConversationAttachmentPort attachments;

  @Value("${app.files.root:data/files}")
  String filesRoot;

  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * ★ 核心判据（issue #299 判据 1）：会话彻底删除后，消息、引用快照、该会话上传附件的 {@code kb_document} 行 + 分段 + 向量 + {@code
   * cv_file} 行 + 磁盘字节<strong>都不再存在</strong>，按表逐项；对照组会话 B 与它的全部数据一根手指都不许碰。
   *
   * <p>★ 删前读数同样重要：先证明每样东西真的在，删后的 0 才是证据（删了个不存在的东西也是 0）。
   */
  @Test
  void purgingAConversationTakesEveryTableDownTableByTable() throws Exception {
    String admin = accessToken("admin", "admin");
    UUID ownerId = adminUserId();

    // ── 造数据：会话 A（将被删）+ 会话 B（对照组）──
    //
    // ★ 顺序不能反：create 是幂等的（"同应用 + 一条消息都没有"的会话直接复用），
    //   先建两条再插消息 ⇒ 两次 create 返回同一条，B 会变成 A 的别名。每建一条先插消息打破"空会话"状态。
    String convA = createConversation(admin);
    insertMessage(convA, "USER", "会话消息-用户");
    insertMessage(convA, "ASSISTANT", "会话消息-助手");
    String convB = createConversation(admin);
    insertMessage(convB, "USER", "会话消息-用户");
    insertMessage(convB, "ASSISTANT", "会话消息-助手");
    assertThat(convA).as("两条会话必须是不同的行").isNotEqualTo(convB);
    Path diskA = null;
    Path diskB = null;
    for (String conv : List.of(convA, convB)) {
      insertFeedback(conv, ownerId);
      insertShare(conv, ownerId);
    }
    UUID fileA = uploadFakeFile(ownerId, "A附件.txt", "会话A的附件正文");
    UUID fileB = uploadFakeFile(ownerId, "B附件.txt", "会话B的附件正文");
    diskA = Path.of(filesRoot).resolve(storageKeyOf(fileA));
    diskB = Path.of(filesRoot).resolve(storageKeyOf(fileB));
    UUID docA =
        attachments.register(
            UUID.fromString(convA),
            ownerId,
            fileA,
            "A附件.txt",
            "会话A的附件正文".getBytes(StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    UUID docB =
        attachments.register(
            UUID.fromString(convB),
            ownerId,
            fileB,
            "B附件.txt",
            "会话B的附件正文".getBytes(StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    insertVectorsFor(docA);
    insertVectorsFor(docB);
    // cv_feedback_state 挂在 A/B 各一条消息上
    insertFeedbackState(messageIdsOf(convA).get(0));
    insertFeedbackState(messageIdsOf(convB).get(0));

    // ── 删前读数（每样都要真的在）──
    assertThat(
            count(
                "select count(*) from cv_conversation where id in (?::uuid, ?::uuid)",
                convA,
                convB))
        .as("删前：两条会话都在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from cv_message where conversation_id in (?::uuid, ?::uuid)",
                convA,
                convB))
        .as("删前：四条消息都在")
        .isEqualTo(4);
    assertThat(
            count(
                "select count(*) from cv_conversation_feedback where conversation_id in (?::uuid,"
                    + " ?::uuid)",
                convA,
                convB))
        .as("删前：两条会话评分都在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from cv_feedback_state where message_id in (select id from"
                    + " cv_message where conversation_id in (?::uuid, ?::uuid))",
                convA,
                convB))
        .as("删前：两条待办状态都在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from cv_share where conversation_id in (?::uuid, ?::uuid)",
                convA,
                convB))
        .as("删前：两条分享都在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from kb_document where conversation_id in (?::uuid, ?::uuid)",
                convA,
                convB))
        .as("删前：两条会话附件文档都在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from kb_segment where document_id in (?::uuid, ?::uuid)",
                docA,
                docB))
        .as("删前：分段都在")
        .isGreaterThan(0);
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id in (select id from kb_segment"
                    + " where document_id in (?::uuid, ?::uuid))",
                docA,
                docB))
        .as("删前：向量都在")
        .isGreaterThan(0);
    assertThat(
            count(
                "select count(*) from cv_file where id in (?::uuid, ?::uuid)",
                fileA.toString(),
                fileB.toString()))
        .as("删前：两条 cv_file 行都在")
        .isEqualTo(2);
    assertThat(Files.exists(diskA)).as("删前：A 的磁盘字节在").isTrue();
    assertThat(Files.exists(diskB)).as("删前：B 的磁盘字节在").isTrue();

    // ── 软删 + 彻底删除 ──
    HttpResponse<String> soft = delete("/api/v1/conversations/" + convA, admin);
    assertThat(soft.statusCode()).as("软删第一步应成功：%s", soft.body()).isEqualTo(200);
    HttpResponse<String> purged = delete("/api/v1/conversations/" + convA + "/purge", admin);
    assertThat(purged.statusCode()).as("彻底删除应成功：%s", purged.body()).isEqualTo(200);

    // ── 删后逐表读数（★ 核心判据：按表逐项）──
    assertThat(count("select count(*) from cv_conversation where id = ?::uuid", convA))
        .as("★ 会话行必须消失")
        .isEqualTo(0);
    assertThat(count("select count(*) from cv_conversation where id = ?::uuid", convB))
        .as("对照组会话 B 必须一根手指不碰")
        .isEqualTo(1);
    assertThat(count("select count(*) from cv_message where conversation_id = ?::uuid", convA))
        .as("★ 消息必须跟着走 —— cv_message 无外键，不显式删就是永远没人管的孤儿行")
        .isEqualTo(0);
    assertThat(count("select count(*) from cv_message where conversation_id = ?::uuid", convB))
        .as("对照组 B 的消息必须还在")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from cv_conversation_feedback where conversation_id = ?::uuid",
                convA))
        .as("★ 会话评分随会话行外键级联走")
        .isEqualTo(0);
    assertThat(
            count(
                "select count(*) from cv_conversation_feedback where conversation_id = ?::uuid",
                convB))
        .as("对照组 B 的评分必须还在")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from cv_feedback_state where message_id in (select id from"
                    + " cv_message where conversation_id = ?::uuid)",
                convA))
        .as("★ 待办状态随消息外键级联走")
        .isEqualTo(0);
    assertThat(count("select count(*) from cv_share where conversation_id = ?::uuid", convA))
        .as("★ 分享随会话行外键级联走")
        .isEqualTo(0);
    assertThat(count("select count(*) from kb_document where conversation_id = ?::uuid", convA))
        .as("★ 会话附件文档行必须消失")
        .isEqualTo(0);
    assertThat(count("select count(*) from kb_document where conversation_id = ?::uuid", convB))
        .as("对照组 B 的附件文档必须还在")
        .isEqualTo(1);
    assertThat(count("select count(*) from kb_segment where document_id = ?::uuid", docA))
        .as("★ 分段必须物理删 —— 分段表的 content 就是正文摘录（02 §5.1.1 行 587）")
        .isEqualTo(0);
    assertThat(count("select count(*) from kb_segment where document_id = ?::uuid", docB))
        .as("对照组 B 的分段必须还在")
        .isGreaterThan(0);
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id in (select id from kb_segment"
                    + " where document_id = ?::uuid)",
                docA))
        .as("★ 向量必须物理删")
        .isEqualTo(0);
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileA.toString()))
        .as("★ cv_file 行必须跟着走")
        .isEqualTo(0);
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileB.toString()))
        .as("对照组 B 的 cv_file 行必须还在")
        .isEqualTo(1);
    assertThat(Files.exists(diskA)).as("★ 磁盘字节必须不在 —— 只删库里的行，彻底删除在介质层是撒谎").isFalse();
    assertThat(Files.exists(diskB)).as("对照组 B 的磁盘字节必须还在").isTrue();

    // ── 审计不删（引用快照该保留的仍保留）：conversation.purge / attachment.purge 各一条 ──
    assertThat(
            count(
                "select count(*) from pf_audit_log where action = 'conversation.purge' and"
                    + " resource_id = ?",
                convA))
        .as("★ conversation.purge 必须留一条（审计不删）")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from pf_audit_log where action = 'attachment.purge' and"
                    + " resource_id = ?",
                convA))
        .as("★ attachment.purge 必须留一条（与 conversation.purge 各留一条）")
        .isEqualTo(1);
    assertThat(count("select count(*) from pf_audit_log"))
        .as("★ 审计只追加：删前删后只会多不会少")
        .isGreaterThanOrEqualTo(2);

    // ── 幂等边界：再删一次 ⇒ 404（行已经不在了）──
    HttpResponse<String> again = delete("/api/v1/conversations/" + convA + "/purge", admin);
    assertThat(again.statusCode()).as("删完再删必须 404，不许假装成功").isEqualTo(404);
  }

  /**
   * ★ issue #299 实现纪律：软删与彻底删除是两个独立动作 —— 彻底删除只对**已软删**的会话开放。 还在使用中的会话直接 purge ⇒ 400 并指路"先删除它"（与
   * DocumentPurgeTest 的文档 purge 同形）。
   */
  @Test
  void purgingAConversationThatIsStillInUseIsRejected() throws Exception {
    String admin = accessToken("admin", "admin");
    String conv = createConversation(admin);

    HttpResponse<String> rejected = delete("/api/v1/conversations/" + conv + "/purge", admin);
    assertThat(rejected.statusCode()).as("没先软删就彻底删除必须 400：%s", rejected.body()).isEqualTo(400);
    assertThat(rejected.body()).as("必须说清下一步").contains("还在使用中");
    assertThat(count("select count(*) from cv_conversation where id = ?::uuid", conv))
        .as("拒绝之后一行都不许动")
        .isEqualTo(1);
  }

  /* ── 夹具与请求 ── */

  private String createConversation(String token) throws Exception {
    HttpResponse<String> res = post("/api/v1/conversations", "{}", token);
    assertThat(res.statusCode()).as("建会话失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  /** 直插一条消息（cv_message 无外键到会话，这正是彻底删除必须显式删它的原因）。 */
  private void insertMessage(String conversationId, String role, String content) {
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status,"
            + " prompt_tokens, completion_tokens, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?, ?, 'GENERATED', 0, 0, now(), now())",
        UUID.randomUUID().toString(),
        conversationId,
        role,
        content);
    // ★ 同步抬消息计数（生产走 ConversationRepository.recordMessage；直插绕过了它）——
    //   不抬的话会话还是"空会话"，create 的幂等复用会把 B 复用成 A（实测踩过）。
    jdbc.update(
        "update cv_conversation set message_count = message_count + 1, last_message_at = now()"
            + " where id = ?::uuid",
        conversationId);
  }

  private List<String> messageIdsOf(String conversationId) {
    return jdbc.queryForList(
        "select id::text from cv_message where conversation_id = ?::uuid order by created_at, id",
        String.class,
        conversationId);
  }

  private void insertFeedback(String conversationId, UUID userId) {
    jdbc.update(
        "insert into cv_conversation_feedback (id, conversation_id, user_id, score)"
            + " values (?::uuid, ?::uuid, ?::uuid, 5)",
        UUID.randomUUID().toString(),
        conversationId,
        userId.toString());
  }

  private void insertFeedbackState(String messageId) {
    jdbc.update(
        "insert into cv_feedback_state (message_id, status) values (?::uuid, 'PENDING')",
        messageId);
  }

  private void insertShare(String conversationId, UUID userId) {
    jdbc.update(
        "insert into cv_share (id, token, conversation_id, scope, max_classification, created_by)"
            + " values (?::uuid, ?, ?::uuid, 'CONVERSATION', 'INTERNAL', ?::uuid)",
        UUID.randomUUID().toString(),
        "tok-" + UUID.randomUUID().toString().replace("-", ""),
        conversationId,
        userId.toString());
  }

  private UUID uploadFakeFile(UUID ownerId, String name, String content) {
    String storageKey = store.store(name, content.getBytes(StandardCharsets.UTF_8));
    UUID fileId = UUID.randomUUID();
    jdbc.update(
        "insert into cv_file (id, name, size_bytes, mime, storage_key, owner_id, created_at)"
            + " values (?::uuid, ?, ?, 'text/plain', ?, ?::uuid, now())",
        fileId,
        name,
        (long) content.getBytes(StandardCharsets.UTF_8).length,
        storageKey,
        ownerId);
    return fileId;
  }

  private String storageKeyOf(UUID fileId) {
    return jdbc.queryForObject(
        "select storage_key from cv_file where id = ?::uuid", String.class, fileId);
  }

  /** 给 register 同步解析出来的每条分段补一行向量（本机没有嵌入模型，向量手插 —— 与 DocumentPurgeTest 同法）。 */
  private void insertVectorsFor(UUID documentId) {
    List<String> segmentIds =
        jdbc.queryForList(
            "select id::text from kb_segment where document_id = ?::uuid",
            String.class,
            documentId);
    assertThat(segmentIds).as("register 的同步解析必须真的建出分段").isNotEmpty();
    for (String segmentId : segmentIds) {
      jdbc.update(
          "insert into vec_embedding_768 (id, content, metadata, embedding) values (?::uuid,"
              + " '会话附件正文', ?::json, cast(array_fill(0.1::real, array[768]) as vector))",
          segmentId,
          "{\"document_id\":\"" + documentId + "\"}");
    }
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
