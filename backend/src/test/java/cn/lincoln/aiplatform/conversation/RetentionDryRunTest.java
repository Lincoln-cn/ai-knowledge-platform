package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.RetentionPolicyProperties;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.IOException;
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
import java.util.stream.Stream;
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
 * 归档清理的「留缝」（`#299` 第三块）——「**今天没有任何东西会自动删**」，三条判据钉住这个现状。
 *
 * <p>★ 处置判定是「留缝」不是「真做」：「超过留存期自动清理」的天数是客户口径（`02 §5.1.1` 行 616「数字是口径不是承诺： 真做清理任务前，必须与客户确认」），issue
 * #299 没给数字 ⇒ 现在做真删只会做错。所以这一批只落三样：配置位（{@link RetentionPolicyProperties}）· dry-run 端点（只数不删）·
 * 本测试。**真删执行体今天不存在，这一格今天没人读**。
 *
 * <p>三条判据：
 *
 * <ol>
 *   <li>{@code dryRunReportsWhatWouldGoButTouchesNothing}：dry-run 按表报数，**前后逐表行数不变**（含磁盘文件）——
 *       「有读数」与「没执行」是两件事，都要证。
 *   <li>{@code retentionNumbersAreNotArmedYet}：配置位默认 {@code enabled=false}、{@code
 *       retainDays=null}（不编数字）。
 *   <li>{@code nothingDeletesConversationDataOnAScheduleToday}：源码里不存在任何「定时删会话数据」的执行体（字面扫描， 与
 *       `AuditActionSurfaceTest` 同法）。谁接上自动清理，这条当场红 —— 红了就必须回来把口径、文档、客户数字一起补齐。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class RetentionDryRunTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired FileStorePort store;

  @Autowired ConversationAttachmentPort attachments;

  @Autowired RetentionPolicyProperties retention;

  @Value("${app.files.root:data/files}")
  String filesRoot;

  private final HttpClient http = HttpClient.newHttpClient();

  /** 判据 1：dry-run 按表报出「若启用会清掉什么」，且**一行都不许动**（库 + 磁盘都算）。 */
  @Test
  void dryRunReportsWhatWouldGoButTouchesNothing() throws Exception {
    String admin = accessToken("admin", "admin");
    UUID ownerId = adminUserId();

    // ── 造一条「软删已过期」的会话（30 天前软删）+ 消息 + 附件（kb_document 三域 + cv_file + 磁盘）──
    String conv = createConversation(admin);
    insertMessage(conv, "USER", "待清理会话的消息");
    UUID file = uploadFakeFile(ownerId, "待清理附件.txt", "待清理的附件正文");
    UUID doc =
        attachments.register(
            UUID.fromString(conv),
            ownerId,
            file,
            "待清理附件.txt",
            "待清理的附件正文".getBytes(StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    insertVectorsFor(doc);
    jdbc.update(
        "update cv_conversation set deleted_at = now() - interval '30 days' where id = ?::uuid",
        conv);

    // ── 删前逐表读数 ──
    long convBefore = count("select count(*) from cv_conversation where id = ?::uuid", conv);
    long msgBefore = count("select count(*) from cv_message where conversation_id = ?::uuid", conv);
    long docBefore = count("select count(*) from kb_document where id = ?::uuid", doc);
    long segBefore = count("select count(*) from kb_segment where document_id = ?::uuid", doc);
    long vecBefore =
        count(
            "select count(*) from vec_embedding_768 where id in (select id from kb_segment where"
                + " document_id = ?::uuid)",
            doc);
    long fileBefore = count("select count(*) from cv_file where id = ?::uuid", file);
    String storageKey =
        jdbc.queryForObject(
            "select storage_key from cv_file where id = ?::uuid", String.class, file);
    Path disk = Path.of(filesRoot, storageKey);
    assertThat(disk).as("删前磁盘字节必须在").exists();
    assertThat(convBefore).as("删前会话行必须在").isEqualTo(1);
    assertThat(msgBefore).as("删前消息必须在").isPositive();
    assertThat(segBefore).as("删前分段必须在").isPositive();

    // ── dry-run（days=15 ⇒ 30 天前软删的会话已过期）──
    HttpResponse<String> res = get("/api/v1/conversations/retention/dry-run?days=15", admin);
    assertThat(res.statusCode()).as("dry-run 失败：%s", res.body()).isEqualTo(200);
    String body = res.body();
    assertThat(jsonLong(body, "conversations")).as("dry-run 必须报出这条过期会话").isGreaterThanOrEqualTo(1);
    assertThat(jsonLong(body, "messages"))
        .as("dry-run 必须把它的消息数报出来")
        .isGreaterThanOrEqualTo(msgBefore);
    assertThat(jsonLong(body, "attachmentDocuments"))
        .as("dry-run 必须把附件文档数报出来")
        .isGreaterThanOrEqualTo(1);
    assertThat(jsonLong(body, "attachmentSegments"))
        .as("dry-run 必须把分段数报出来")
        .isGreaterThanOrEqualTo(segBefore);
    assertThat(jsonLong(body, "files")).as("dry-run 必须把文件数报出来").isGreaterThanOrEqualTo(1);
    assertThat(body).as("dry-run 必须自报 enabled 状态").contains("\"deletionEnabled\":false");

    // ── 删后逐表读数：一行都不许动（dry-run 是只读试算）──
    assertThat(count("select count(*) from cv_conversation where id = ?::uuid", conv))
        .as("[★ dry-run 不许删会话行 —— 它只是试算]")
        .isEqualTo(convBefore);
    assertThat(count("select count(*) from cv_message where conversation_id = ?::uuid", conv))
        .as("[★ dry-run 不许删消息行]")
        .isEqualTo(msgBefore);
    assertThat(count("select count(*) from kb_document where id = ?::uuid", doc))
        .as("[★ dry-run 不许删附件文档行]")
        .isEqualTo(docBefore);
    assertThat(count("select count(*) from kb_segment where document_id = ?::uuid", doc))
        .as("[★ dry-run 不许删分段]")
        .isEqualTo(segBefore);
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id in (select id from kb_segment"
                    + " where document_id = ?::uuid)",
                doc))
        .as("[★ dry-run 不许删向量]")
        .isEqualTo(vecBefore);
    assertThat(count("select count(*) from cv_file where id = ?::uuid", file))
        .as("[★ dry-run 不许删 cv_file 行]")
        .isEqualTo(fileBefore);
    assertThat(disk).as("[★ dry-run 不许删磁盘字节]").exists();
  }

  /** 判据 2：配置位默认不开、天数默认 null（issue #299 没给数字 ⇒ 不许编，待客户确认）。 */
  @Test
  void retentionNumbersAreNotArmedYet() {
    assertThat(retention.enabled()).as("[★ 自动清理默认必须关着 —— 开关还没到打开的时候（天数待客户确认）]").isFalse();
    assertThat(retention.retainDays()).as("[★ 留存期天数默认必须是 null（待客户确认），不许编一个数字进来]").isNull();
  }

  /**
   * 判据 3：源码里不存在任何「定时删会话数据」的执行体 —— 今天没有任何东西会自动删。
   *
   * <p>★ 字面扫描（与 {@code AuditActionSurfaceTest} 同法）：含 {@code @Scheduled} 的文件里不许出现会话数据的删除入口 （{@code
   * deleteByConversationId} / {@code purgeConversationAttachments} / {@code purgeAttachmentFile} /
   * {@code ConversationPurgeService}）。谁接上自动清理，这条当场红 —— 红了就必须回来把口径、文档、客户数字一起补齐。
   */
  @Test
  void nothingDeletesConversationDataOnAScheduleToday() throws IOException {
    Path mainJava = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(mainJava)) {
      List<Path> scheduled =
          files
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> containsLiteral(p, "@Scheduled"))
              .toList();
      for (Path file : scheduled) {
        String source = Files.readString(file);
        for (String forbidden :
            List.of(
                "deleteByConversationId",
                "purgeConversationAttachments",
                "purgeAttachmentFile",
                "ConversationPurgeService")) {
          assertThat(source)
              .as(
                  "[★ 今天没有任何东西会自动删会话数据 —— %s 出现在了 @Scheduled 文件 %s 里：接上自动清理前，"
                      + "天数口径必须先和客户确认（02 §5.1.1 行 616），并把这条断言、口径文档一起更新]",
                  forbidden, file)
              .doesNotContain(forbidden);
        }
      }
    }
  }

  /* ── 夹具与请求（与 ConversationPurgeTest 同形） ── */

  private boolean containsLiteral(Path file, String literal) {
    try {
      return Files.readString(file).contains(literal);
    } catch (IOException e) {
      throw new IllegalStateException("读不了源码文件：" + file, e);
    }
  }

  private String createConversation(String token) throws Exception {
    HttpResponse<String> res = post("/api/v1/conversations", "{}", token);
    assertThat(res.statusCode()).as("建会话失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private void insertMessage(String conversationId, String role, String content) {
    jdbc.update(
        "insert into cv_message (id, conversation_id, role, content, status,"
            + " prompt_tokens, completion_tokens, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?, ?, 'GENERATED', 0, 0, now(), now())",
        UUID.randomUUID().toString(),
        conversationId,
        role,
        content);
    jdbc.update(
        "update cv_conversation set message_count = message_count + 1, last_message_at = now()"
            + " where id = ?::uuid",
        conversationId);
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

  /** 给 register 同步解析出来的每条分段补一行向量（本机没有嵌入模型，向量手插 —— 与 ConversationPurgeTest 同法）。 */
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

  private static String jsonField(String body, String field) {
    int idx = body.indexOf("\"" + field + "\":");
    if (idx < 0) {
      throw new IllegalArgumentException("响应里没有字段 " + field + "：" + body);
    }
    int start = body.indexOf('"', idx + field.length() + 3) + 1;
    int end = body.indexOf('"', start);
    return body.substring(start, end);
  }

  private static long jsonLong(String body, String field) {
    int idx = body.indexOf("\"" + field + "\":");
    if (idx < 0) {
      throw new IllegalArgumentException("响应里没有字段 " + field + "：" + body);
    }
    int start = idx + field.length() + 3;
    int end = start;
    while (end < body.length()
        && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
      end++;
    }
    return Long.parseLong(body.substring(start, end));
  }
}
