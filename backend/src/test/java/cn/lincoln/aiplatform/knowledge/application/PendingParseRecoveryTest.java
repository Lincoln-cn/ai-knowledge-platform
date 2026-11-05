package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
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
 * issue #304 判据 ①③ 的集成读数（{@code PENDING_PARSE} 卡死修复）。
 *
 * <h3>★ 判据对照（issue #304 / 父代理交付口径）</h3>
 *
 * <ol>
 *   <li><b>判据 ①</b>：连接池压到 2、20 份并发上传 ⇒ {@code PENDING_PARSE} <b>最终为 0</b>（要么成功到待向量化、要么 {@code
 *       SEGMENT_FAILED} 可重试）。
 *   <li><b>判据 ③</b>：「正常等待」与「任务没起来」两类的原因<b>文案不同</b>且<b>界面可见</b> —— 任务没起来被回收时写「解析任务未能启动」档， 与 {@code
 *       #303} 的「处理任务已中断」档（任务中途死）不是一个文案，用户能读出"该重试"。
 *   <li><b>正常等待不许误杀</b>：只登记元数据（无字节）的文档永远停在 {@code PENDING_PARSE} 是<b>正常的</b>，回收器必须跳过。
 * </ol>
 *
 * <p>★ 池压到 2 = {@code spring.datasource.hikari.maximum-pool-size=2}；两个 reaper 都开着（bean 在，供手动调
 * {@code reap(now)} 取可控读数）， 阈值压到 1 分钟（下限）。★ 手动 {@code reap(now+30m)} 是"最终"的加速读数 —— 生产配置是阈值 2 分钟 + 每
 * 60s 扫一轮。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      "spring.datasource.hikari.maximum-pool-size=2",
      "app.ingestion.pending-parse-timeout-minutes=1",
      "app.ingestion.segmenting-timeout-minutes=1"
    })
class PendingParseRecoveryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID ROOT_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  private static final Pattern TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired StuckPendingParseReaper pendingReaper;

  @Autowired StuckSegmentingReaper segmentingReaper;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * ★<b>判据 ①</b>：池压到 2、20 份并发上传 ⇒ {@code PENDING_PARSE} 最终为 0。
   *
   * <p>修复前的机理：事件监听器 {@code onUploaded} 经 Modulith {@code CompletionRegisteringAdvisor} 在方法体之前
   * {@code markProcessing} 开新事务拿连接，池满抛在代理外层 ⇒ 解析任务<b>从未创建</b>、文档永远停在 {@code PENDING_PARSE}。
   * 修复后排队挪进纯内存 afterCommit 回调，任务必然创建；解析途中连接竞争失败的由回收器兜底成 {@code SEGMENT_FAILED}（可重试）。
   *
   * <p>★ 读数三段：① 自然稳定后的分布；② 手动 {@code reap(now+30m)} 把"任务没起来"的收掉；③ 断言 {@code PENDING_PARSE} 计数 = 0 且
   * 每份终态 ∈ {PENDING_EMBEDDING, SEGMENT_FAILED}，{@code SEGMENT_FAILED} 的原因含「可重试」。
   */
  @Test
  @DisplayName("★ 判据①：池压到 2、20 份并发上传 ⇒ PENDING_PARSE 最终为 0（成功或 SEGMENT_FAILED 可重试）")
  void poolSqueezedToTwoWithTwentyConcurrentUploadsEndsWithZeroPendingParse() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);

    // ① 20 份真并发上传（每份一个线程：HTTP 请求在上传事务里建档 + 投事件，池 2 下真实抢连接）
    List<String> docIds = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(20);
    try {
      List<CompletableFuture<String>> futures = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        final int n = i;
        futures.add(CompletableFuture.supplyAsync(() -> uploadSmall(admin, kbId, n), pool));
      }
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      for (CompletableFuture<String> future : futures) {
        docIds.add(future.join());
      }
    } finally {
      pool.shutdown();
    }
    assertThat(docIds).as("20 份上传必须都拿到文档 id").hasSize(20);

    // ② 自然稳定（等正常解析跑完；任务没起来的会留在 PENDING_PARSE）—— 不强求全部落定，下面 reap 兜底
    waitSettled(docIds, 90);
    int pendingBeforeReap = countPendingOf(docIds);

    // ③ 兜底回收（注入 now+30m ⇒ 阈值 1 分钟的都算超期）= "最终"的加速读数。
    //    残留可能只是收尾慢一拍（inFlight 守卫跳过）⇒ 循环再收，最多 60s
    pendingReaper.reap(Instant.now().plus(Duration.ofMinutes(30)));
    for (int i = 0; i < 12 && countPendingOf(docIds) > 0; i++) {
      Thread.sleep(5000);
      pendingReaper.reap(Instant.now().plus(Duration.ofMinutes(30)));
    }

    int pending = countPendingOf(docIds);
    System.out.printf(
        "[判据①读数] 上传 20 份 · 自然稳定后 PENDING_PARSE=%d · reap 后 PENDING_PARSE=%d（只算这 20 份）%n",
        pendingBeforeReap, pending);
    if (countByStatus("PENDING_PARSE") > 0) {
      System.out.printf(
          "[判据③对照·正常等待] 全库 PENDING_PARSE 残留明细（应只有元数据文档、parse_expected_at 为空）：%s%n",
          jdbc.queryForList(
              "select name, parse_expected_at from kb_document where status = 'PENDING_PARSE'"));
    }

    assertThat(pending).as("★ 判据①：PENDING_PARSE 必须最终为 0（修复前这里永远是 >0 的死数）").isZero();
    for (String docId : docIds) {
      String status = status(docId);
      assertThat(status)
          .as(
              "每份的终态必须是「成功到待向量化」或「SEGMENT_FAILED 可重试」，不能是 %s（fail_reason=%s）",
              status, failReason(docId))
          .isIn("PENDING_EMBEDDING", "SEGMENT_FAILED");
      if ("SEGMENT_FAILED".equals(status)) {
        assertThat(failReason(docId))
            .as("被回收/失败的必须给出可重试的可读原因")
            .contains("可重试")
            .doesNotContain("文件");
      }
    }
  }

  /**
   * ★<b>判据 ③</b>：「任务没起来」与「任务中途死」两类卡死的原因<b>文案不同</b>，且都能从文档接口读到（= 界面可见）。
   *
   * <p>任务没起来（{@code PENDING_PARSE} 超期无主）⇒「解析任务未能启动」档；任务中途死（{@code SEGMENTING} 超期无主， {@code #303}
   * 那套）⇒「处理任务已中断」档。两档都能被用户读出"该重试"，但"为什么会这样"不同 —— 前者是服务繁忙时解析在启动前就失败， 后者是解析跑到一半任务没了。
   */
  @Test
  @DisplayName("★ 判据③：任务没起来与任务中途死的原因文案不同，且界面（文档接口）可见")
  void pendingParseReapReasonDistinguishesFromSegmentingReapReason() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);

    // ① 场景 A「任务没起来」：一份上传成功的文档被打回 PENDING_PARSE + parse_expected_at 超期（模拟 afterCommit 排队也失败的世界）
    // ★ 先等它自己的解析任务收尾（inFlight 清空）再打回 —— 否则回收器的"任务在跑不判死"守卫会跳过它
    String docA = uploadSmall(admin, kbId, 100);
    waitSettled(List.of(docA), 90);
    jdbc.update(
        "update kb_document set status = 'PENDING_PARSE', fail_reason = null, parse_expected_at = ?"
            + " where id = ?::uuid",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
        docA);
    pendingReaper.reap(Instant.now().plus(Duration.ofMinutes(30)));
    String reasonA = failReason(docA);

    // ② 场景 B「任务中途死」：SEGMENTING 超期无主（#303 那套的收尾）—— 同样先等任务收尾
    String docB = uploadSmall(admin, kbId, 101);
    waitSettled(List.of(docB), 90);
    jdbc.update(
        "update kb_document set status = 'SEGMENTING', fail_reason = null, updated_at = ? where id"
            + " = ?::uuid",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
        docB);
    segmentingReaper.reap();
    String reasonB = failReason(docB);

    System.out.printf("[判据③读数] 任务没起来=%s || 任务中途死=%s%n", reasonA, reasonB);

    // ③ 两类文案必须不同，且分别落在各自的档上
    assertThat(reasonA).as("任务没起来的文案").contains("解析任务未能启动").doesNotContain("已中断");
    assertThat(reasonB).as("任务中途死的文案（#303 档）").contains("处理任务已中断").doesNotContain("未能启动");
    assertThat(reasonA).as("两类文案必须不同（判据③的核心）").isNotEqualTo(reasonB);

    // ④ 「界面可见」：文档列表接口原样带出 failReason（DocumentView.failReason）
    HttpResponse<String> list =
        send("GET", "/api/v1/knowledge-bases/" + kbId + "/documents", null, admin);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).as("界面可见：API 响应里必须带着两类原因").contains("解析任务未能启动").contains("处理任务已中断");
  }

  /**
   * ★ 「正常等待」不许误杀：只登记元数据（无字节）的文档永远停在 {@code PENDING_PARSE} 是<b>正常的</b>，回收器必须跳过。
   *
   * <p>这正是 issue #304 里"读数要能区分两类"的另一半：{@code parse_expected_at} 为 NULL = 没人欠它一次解析。
   */
  @Test
  @DisplayName("★ 正常等待：只登记元数据（无字节）的文档 parse_expected_at 为 NULL，回收器必须跳过")
  void metadataOnlyDocumentIsNeverReaped() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);

    // JSON 形态建档 = 只登记元数据，不带字节（DocumentController#register 传 contents=null）
    String body =
        "{\"names\":[\"只登记元数据-%s.md\"],\"docType\":\"制度\",\"orgId\":\"%s\",\"classification\":\"INTERNAL\"}"
            .formatted(shortId(), ROOT_ORG_ID);
    HttpResponse<String> res =
        send("POST", "/api/v1/knowledge-bases/" + kbId + "/documents", body, admin);
    assertThat(res.statusCode()).as("元数据建档失败：%s", res.body()).isEqualTo(200);
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("建档响应里没有 id：%s", res.body()).isTrue();
    String docId = m.group(1);

    assertThat(parseExpectedAtOf(docId)).as("无字节建档不许写 parse_expected_at（没人欠它一次解析）").isNull();

    // 超大 now ⇒ 只要它进了回收器的查询范围就会被收 —— 它必须不在
    pendingReaper.reap(Instant.now().plus(Duration.ofMinutes(30)));

    assertThat(status(docId)).as("元数据文档必须停留在 PENDING_PARSE（正常等待，不是卡死）").isEqualTo("PENDING_PARSE");
    assertThat(
            jdbc.queryForObject(
                "select fail_reason from kb_document where id = ?::uuid", String.class, docId))
        .as("正常等待不许产生失败记录")
        .isNull();
  }

  // ────────────────────────── 夹具与工具 ──────────────────────────

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    Matcher m = TOKEN.matcher(res.body());
    assertThat(m.find()).as("登录失败：%s", res.body()).isTrue();
    return m.group(1);
  }

  private String createKb(String admin) throws Exception {
    String body =
        """
        {"name":"304 回收哨兵库-%s","category":"制度规范","description":"issue #304 判据用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"INTERNAL"}\
        """
            .formatted(shortId(), UUID.randomUUID());
    HttpResponse<String> res = send("POST", "/api/v1/knowledge-bases", body, admin);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("建库响应里没有 id：%s", res.body()).isTrue();
    return m.group(1);
  }

  /** multipart 上传一份小文档（不等解析）—— 20 并发压池用。 */
  private String uploadSmall(String admin, String kbId, int n) {
    try {
      String filename = "并发压测-%02d.md".formatted(n);
      String boundary = "----PendingParseBoundary" + UUID.randomUUID();
      var buf = new java.io.ByteArrayOutputStream();
      for (String[] field :
          new String[][] {
            {"docType", "制度"}, {"orgId", ROOT_ORG_ID.toString()}, {"classification", "INTERNAL"}
          }) {
        buf.write(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
                .formatted(boundary, field[0], field[1])
                .getBytes(StandardCharsets.UTF_8));
      }
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\n"
                  + "Content-Type: text/markdown\r\n\r\n")
              .formatted(boundary, filename)
              .getBytes(StandardCharsets.UTF_8));
      buf.write(("# 并发压测 %d\n\n一小段内容。".formatted(n)).getBytes(StandardCharsets.UTF_8));
      buf.write(("\r\n--%s--\r\n").formatted(boundary).getBytes(StandardCharsets.UTF_8));

      HttpResponse<String> res =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/knowledge-bases/"
                              + kbId
                              + "/documents"))
                  .timeout(Duration.ofSeconds(120))
                  .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                  .header("Authorization", "Bearer " + admin)
                  .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertThat(res.statusCode()).as("上传文档失败：%s", res.body()).isEqualTo(200);
      Matcher m = ID.matcher(res.body());
      assertThat(m.find()).as("上传响应里没有 id：%s", res.body()).isTrue();
      return m.group(1);
    } catch (Exception e) {
      throw new IllegalStateException("上传失败：" + n, e);
    }
  }

  /** 等自然稳定：每份要么到终态（PENDING_EMBEDDING / SEGMENT_FAILED），要么留 PENDING_PARSE（等 reap）。 */
  private void waitSettled(List<String> docIds, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      Integer unfinished =
          jdbc.queryForObject(
              "select count(*) from kb_document where id::text = any(?) and status in"
                  + " ('PENDING_PARSE','SEGMENTING')",
              Integer.class,
              (Object) docIds.toArray(new String[0]));
      if (unfinished == 0) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private int countByStatus(String status) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from kb_document where status = ?", Integer.class, status);
    return count == null ? 0 : count;
  }

  /** 只统计本用例上传的那些（全库会被别的用例的合法元数据文档污染 —— 它停 PENDING_PARSE 是正常的）。 */
  private int countPendingOf(List<String> docIds) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from kb_document where id::text = any(?) and status = 'PENDING_PARSE'",
            Integer.class,
            (Object) docIds.toArray(new String[0]));
    return count == null ? 0 : count;
  }

  private String status(String docId) {
    return jdbc.queryForObject(
        "select status from kb_document where id = ?::uuid", String.class, docId);
  }

  private String failReason(String docId) {
    return jdbc.queryForObject(
        "select coalesce(fail_reason, '(无)') from kb_document where id = ?::uuid",
        String.class,
        docId);
  }

  private Instant parseExpectedAtOf(String docId) {
    return jdbc.queryForObject(
        "select parse_expected_at from kb_document where id = ?::uuid", Instant.class, docId);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(60));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      if (bearer != null) {
        builder.header("Authorization", "Bearer " + bearer);
      }
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("请求失败：" + method + " " + path, e);
    }
  }
}
