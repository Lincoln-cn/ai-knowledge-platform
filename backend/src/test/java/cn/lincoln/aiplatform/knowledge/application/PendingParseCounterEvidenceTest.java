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
import java.util.UUID;
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
 * issue #304 <b>判据 ② 的反证读数</b>：把回收器关掉（{@code pending-parse-reaper.enabled=false}）⇒ 造出的超期 {@code
 * PENDING_PARSE} 必须原地停留 —— 「停留超过阈值」是可复现的。
 *
 * <h3>★ 为什么反证是硬要求</h3>
 *
 * <p>"修闸门"与"把闸门改成绿的"读数完全一样（都是归 0）；唯一能区分的是<b>被测对象坏掉时它会不会报红</b>。 这里把修复的兜底闸门（{@link
 * StuckPendingParseReaper}）摘掉，证明「停留超阈值的 {@code PENDING_PARSE}」确实是可复现的死数，
 * 而不是碰巧没出现。<b>反证跑完即还原</b>：本类只是测试属性不开闸门，生产默认 {@code true}（application.yml / deploy/README.md）。
 *
 * <p>★ 机理级反证（临时把 {@code onUploaded} 改回 {@code @TransactionalEventListener} + 池 2 真压测复现 {@code
 * CannotCreateTransactionException}）是运行时读数，见交付报告 —— 本类是它在 CI 里的可重复等价物（"没人管就永远卡住"）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      "spring.datasource.hikari.maximum-pool-size=2",
      "app.ingestion.pending-parse-reaper.enabled=false",
      "app.ingestion.pending-parse-timeout-minutes=1"
    })
class PendingParseCounterEvidenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID ROOT_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  private static final Pattern TOKEN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  @DisplayName("★ 判据②反证：回收器关掉 ⇒ 超期的 PENDING_PARSE 停留不动（没人管就永远卡住）")
  void reaperDisabledMeansStuckPendingParseStaysForever() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadSmall(admin, kbId);

    // ★ 先等它自己的解析任务收尾（inFlight 清空）—— 反证要的是"超期无主没人管"，不是"任务还在跑"
    waitSettled(docId, 90);

    // 造"任务没起来且早已超期"的形态：parse_expected_at = 1 小时前（阈值才 1 分钟）
    jdbc.update(
        "update kb_document set status = 'PENDING_PARSE', fail_reason = null, parse_expected_at = ?"
            + " where id = ?::uuid",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
        docId);

    // ★ 停留读数：远超阈值（1 分钟）+ 足够跑好几轮扫描的时间后，必须原地不动
    Thread.sleep(8000);

    System.out.printf(
        "[判据②反证读数] 超期 1 小时的 PENDING_PARSE（阈值 1 分钟）· 关掉回收器 8s 后 status=%s · fail_reason=%s%n",
        status(docId), failReason(docId));

    assertThat(status(docId))
        .as("★ 反证：没有回收器时它必须仍然卡在 PENDING_PARSE（修复前的死数形态）")
        .isEqualTo("PENDING_PARSE");
    assertThat(
            jdbc.queryForObject(
                "select fail_reason from kb_document where id = ?::uuid", String.class, docId))
        .as("反证：没有任何失败记录（issue 现象「没有解析任务、没有失败记录」）")
        .isNull();

    // ★ 开闸即回收（读数由 PendingParseRecoveryTest 判据③ 用例给出）：这里不再重复，避免与"关闸"混在一起
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
        {"name":"304 反证库-%s","category":"制度规范","description":"issue #304 判据②反证用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"INTERNAL"}\
        """
            .formatted(shortId(), UUID.randomUUID());
    HttpResponse<String> res = send("POST", "/api/v1/knowledge-bases", body, admin);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("建库响应里没有 id：%s", res.body()).isTrue();
    return m.group(1);
  }

  private String uploadSmall(String admin, String kbId) throws Exception {
    String boundary = "----CounterEvidenceBoundary" + UUID.randomUUID();
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
            .formatted(boundary, "反证文档.md")
            .getBytes(StandardCharsets.UTF_8));
    buf.write("# 反证\n\n一小段内容。".getBytes(StandardCharsets.UTF_8));
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
  }

  private void waitSettled(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String current = status(docId);
      if (!"PENDING_PARSE".equals(current) && !"SEGMENTING".equals(current)) {
        return;
      }
      Thread.sleep(500);
    }
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
