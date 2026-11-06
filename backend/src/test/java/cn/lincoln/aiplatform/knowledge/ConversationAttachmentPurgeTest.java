package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
 * 附件的彻底删除（`#299` 第二块 · `02 §5.1.1` 第二半）：会话附件文档彻底删除时，**磁盘上的字节与 `cv_file` 行一起走**。
 *
 * <p>★ 与 {@link DocumentPurgeTest} 的分工：那条测"库域文档删干净（行 + 分段 + 向量）"， 这条测"会话域附件多两样东西 ——
 * 来源上传文件（`cv_file` 行 + `backend/data/files/` 磁盘字节）"。 只删库里的行、字节留在磁盘上，"彻底删除"在介质层就是撒谎 ——
 * 而残留的文件是数据安全事故，不是 nice-to-have。
 *
 * <p>★ 追溯链（`#299` · V80）：{@code kb_document.attachment_id → cv_file.id → storage_key → 磁盘文件}。
 * 这条链是这批加上去的：此前 kb_document 会话附件行没有任何指向上传文件的列， 会话删掉之后磁盘文件没有任何可靠追溯 —— 那正是 issue 里"附件没人管"的根因。
 *
 * <p>★ 引用计数：一份上传文件可以被多个会话引用（同一个 `cv_file.id` 出现在多条 `kb_document` 行上）。 只有当**没有任何文档行还引用它**时才许删字节 ——
 * 删早了就是把另一个会话的材料也毁了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ConversationAttachmentPurgeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired FileStorePort store;

  @Autowired ConversationAttachmentPort attachments;

  @org.springframework.beans.factory.annotation.Value("${app.files.root:data/files}")
  String filesRoot;

  private final HttpClient http = HttpClient.newHttpClient();

  /**
   * ★ 判据 ①：登记附件时把来源上传文件记下来（`kb_document.attachment_id = cv_file.id`）。
   *
   * <p>这条是整条追溯链的地基：没有它，后面"彻底删除连字节一起删"根本没有依据去找到那个文件。
   */
  @Test
  void registeringAnAttachmentRecordsWhereItsBytesCameFrom() {
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = adminUserId();
    UUID fileId = uploadFakeFile(ownerId, "来源追溯.txt", "来源追溯测试正文");

    UUID documentId =
        attachments.register(
            conversationId,
            ownerId,
            fileId,
            "来源追溯.txt",
            "来源追溯测试正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);

    Map<String, Object> row =
        jdbc.queryForMap(
            "select attachment_id::text as attachment_id, scope_kind, scope_id::text as scope_id"
                + " from kb_document where id = ?::uuid",
            documentId);
    assertThat(row.get("attachment_id"))
        .as("★ 附件行必须记下来源上传文件（V80 的 attachment_id 列）—— 否则彻底删除时磁盘文件无从找起")
        .isEqualTo(fileId.toString());
    assertThat(row.get("scope_kind")).isEqualTo("CONVERSATION");
    assertThat(row.get("scope_id")).isEqualTo(conversationId.toString());
  }

  /**
   * ★ 判据 ②（核心按表逐项）：会话附件文档彻底删除后，`kb_document` / `kb_segment` / `vec_embedding_768` / `cv_file` /
   * 磁盘文件<strong>逐项</strong>读数 —— 一样都不许剩。
   *
   * <p>★ 删前读数同样重要：先证明每样东西真的在，删后的 0 才算证据（不然删了个不存在的东西也是 0）。
   */
  @Test
  void purgingAnAttachmentTakesItsBytesWithIt() throws Exception {
    String admin = accessToken("admin", "admin");
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = adminUserId();
    UUID fileId = uploadFakeFile(ownerId, "彻底删除-附件.txt", "附件彻底删除测试正文");
    Path disk = Path.of(filesRoot).resolve(storageKeyOf(fileId));
    assertThat(Files.exists(disk)).as("磁盘字节必须先在（删后才有得断言）").isTrue();

    UUID documentId =
        attachments.register(
            conversationId,
            ownerId,
            fileId,
            "彻底删除-附件.txt",
            "附件彻底删除测试正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    insertVectorsFor(documentId);

    // ── 删前读数（每一样都在，删后的 0 才是证据）──
    assertThat(count("select count(*) from kb_document where id = ?::uuid", documentId))
        .as("删前：文档行")
        .isEqualTo(1);
    long segmentsBefore =
        count("select count(*) from kb_segment where document_id = ?::uuid", documentId);
    assertThat(segmentsBefore).as("删前：分段行（register 时同步解析建的）").isPositive();
    long vectorsBefore =
        count(
            "select count(*) from vec_embedding_768 where id in (select id from kb_segment where"
                + " document_id = ?::uuid)",
            documentId);
    assertThat(vectorsBefore).as("删前：向量行").isEqualTo(segmentsBefore);
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileId))
        .as("删前：cv_file 行")
        .isEqualTo(1);
    assertThat(Files.exists(disk)).as("删前：磁盘字节").isTrue();

    // ── 软删（与彻底删除是两个独立动作，不许合并）──
    assertThat(delete("/api/v1/documents/" + documentId, admin).statusCode())
        .as("软删必须先成功")
        .isEqualTo(200);
    assertThat(count("select count(*) from kb_document where id = ?::uuid", documentId))
        .as("软删只落聚合根：行还在")
        .isEqualTo(1);

    HttpResponse<String> purge = delete("/api/v1/documents/" + documentId + "/purge", admin);
    assertThat(purge.statusCode()).as("彻底删除失败：%s", purge.body()).isEqualTo(200);

    // ── 删后读数（按表逐项）──
    assertThat(count("select count(*) from kb_document where id = ?::uuid", documentId))
        .as("★ 文档行必须真的没了")
        .isZero();
    assertThat(count("select count(*) from kb_segment where document_id = ?::uuid", documentId))
        .as("★ 分段必须一起删（孤儿分段在检索里照样命中）")
        .isZero();
    assertThat(
            count(
                "select count(*) from vec_embedding_768 where id in (select id from kb_segment"
                    + " where document_id = ?::uuid)",
                documentId))
        .as("★ 向量必须一起删")
        .isZero();
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileId))
        .as("★ cv_file 行必须跟着走 —— 只删文档行的话它就是没人管的孤儿")
        .isZero();
    assertThat(Files.exists(disk)).as("★ 磁盘字节必须删掉 —— 残留的文件是数据安全事故，不是 nice-to-have").isFalse();
    // ★ 审计不删：材料没了，"它被彻底删除了"这条记录必须在（resource_id 是 varchar，传文本）
    assertThat(
            count(
                "select count(*) from pf_audit_log where resource_id = ? and action ="
                    + " 'document.purge'",
                documentId.toString()))
        .as("审计留痕")
        .isEqualTo(1);
  }

  /**
   * ★ 判据 ③（引用计数）：一份上传文件被两个会话引用时，一个会话的附件彻底删除<strong>不许</strong>动字节 —— 另一个会话的材料还在用它。最后一个引用走掉时字节才消失。
   *
   * <p>★ 反证方向：把 {@code countByAttachmentId == 0} 的条件去掉，第一步就红（字节提前没了）。
   */
  @Test
  void sharedAttachmentBytesSurviveUntilTheLastReferenceGoes() throws Exception {
    String admin = accessToken("admin", "admin");
    UUID ownerId = adminUserId();
    UUID fileId = uploadFakeFile(ownerId, "共享附件.txt", "共享附件测试正文");
    Path disk = Path.of(filesRoot).resolve(storageKeyOf(fileId));

    UUID docA =
        attachments.register(
            UUID.randomUUID(),
            ownerId,
            fileId,
            "共享附件-A.txt",
            "共享附件测试正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    UUID docB =
        attachments.register(
            UUID.randomUUID(),
            ownerId,
            fileId,
            "共享附件-B.txt",
            "共享附件测试正文".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    assertThat(count("select count(*) from kb_document where attachment_id = ?::uuid", fileId))
        .as("两个会话都引用这一份上传文件")
        .isEqualTo(2);

    // ── 会话 A 的附件彻底删除：文档行没了，但字节与 cv_file 行必须留着 ──
    assertThat(delete("/api/v1/documents/" + docA, admin).statusCode()).isEqualTo(200);
    assertThat(delete("/api/v1/documents/" + docA + "/purge", admin).statusCode()).isEqualTo(200);
    assertThat(count("select count(*) from kb_document where attachment_id = ?::uuid", fileId))
        .as("A 的引用走了，还剩 B 的")
        .isEqualTo(1);
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileId))
        .as("★ 还有别人在用：cv_file 行不许动")
        .isEqualTo(1);
    assertThat(Files.exists(disk)).as("★ 还有别人在用：磁盘字节不许动 —— 删早了就是把另一个会话的材料也毁了").isTrue();

    // ── 会话 B 的附件也走：最后一个引用没了，字节与行一起消失 ──
    assertThat(delete("/api/v1/documents/" + docB, admin).statusCode()).isEqualTo(200);
    assertThat(delete("/api/v1/documents/" + docB + "/purge", admin).statusCode()).isEqualTo(200);
    assertThat(count("select count(*) from kb_document where attachment_id = ?::uuid", fileId))
        .isZero();
    assertThat(count("select count(*) from cv_file where id = ?::uuid", fileId))
        .as("最后一个引用走掉，cv_file 行跟着走")
        .isZero();
    assertThat(Files.exists(disk)).as("最后一个引用走掉，磁盘字节跟着走").isFalse();
  }

  /**
   * ★ 判据 ④（幂等键）：同会话两份<strong>同名不同内容</strong>（用户重新上传）是<strong>两份</strong>材料。
   *
   * <p>★ 反证方向：幂等键退回 (conversationId, name) 时，第二份会被当成第一份的重复登记 —— 它的 cv_file
   * 从此无人引用（追溯链断），会话彻底删除时那个文件会永远留成孤儿。 这条断言钉住"按附件 id 幂等，不按名字顶掉"。
   */
  @Test
  void sameNameDifferentBytesAreTwoAttachments() {
    UUID conversationId = UUID.randomUUID();
    UUID ownerId = adminUserId();
    UUID fileP = uploadFakeFile(ownerId, "重传.txt", "第一份内容");
    UUID fileQ = uploadFakeFile(ownerId, "重传.txt", "第二份内容");

    UUID docP =
        attachments.register(
            conversationId,
            ownerId,
            fileP,
            "重传.txt",
            "第一份内容".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    UUID docQ =
        attachments.register(
            conversationId,
            ownerId,
            fileQ,
            "重传.txt",
            "第二份内容".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);

    assertThat(docQ).as("同名不同内容不是重复登记，必须是两个文档").isNotEqualTo(docP);
    assertThat(
            count(
                "select count(*) from kb_document where conversation_id = ?::uuid", conversationId))
        .as("同会话两份同名附件 = 两条文档行")
        .isEqualTo(2);
    assertThat(
            count(
                "select count(*) from kb_document where conversation_id = ?::uuid and attachment_id"
                    + " is not null",
                conversationId))
        .as("两份都各自记着来源文件 —— 少一份就有一份的字节清不掉")
        .isEqualTo(2);
    // ★ 同一份文件再登记（同一 attachmentId）仍是幂等的：一条都不许多
    UUID again =
        attachments.register(
            conversationId,
            ownerId,
            fileP,
            "重传.txt",
            "第一份内容".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            ClassificationLevel.INTERNAL);
    assertThat(again).as("同一上传文件重复登记必须幂等").isEqualTo(docP);
  }

  /* ── 夹具与请求 ── */

  /**
   * 造一份上传文件：磁盘字节（{@link FileStorePort#store}）+ {@code cv_file} 行（与 FileService.upload 同序：先字节后行）。
   */
  private UUID uploadFakeFile(UUID ownerId, String name, String content) {
    String storageKey =
        store.store(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID fileId = UUID.randomUUID();
    jdbc.update(
        "insert into cv_file (id, name, size_bytes, mime, storage_key, owner_id, created_at)"
            + " values (?::uuid, ?, ?, 'text/plain', ?, ?::uuid, now())",
        fileId,
        name,
        (long) content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
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
          "insert into vec_embedding_768 (id, content, metadata, embedding)"
              + " values (?::uuid, '附件彻底删除测试正文', ?::json,"
              + " cast(array_fill(0.1::real, array[768]) as vector))",
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
