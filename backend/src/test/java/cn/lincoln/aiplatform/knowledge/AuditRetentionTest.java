package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 审计记录的**留存期清理**（`#75`）。
 *
 * <p>`V56` 把审计钉成"只允许插入"，这是对的 —— 但"永远不删"不是企业级：`13 §2.4` 与 `deploy/README.md` 写下的留存期 （审计类 ≥6 个月、默认 12
 * 个月）到了就得能删，否则那个数字只是文档里的一句话。
 *
 * <p>★ 这一条测试要证明的不是"能删"，而是**"只能这样删"**：
 *
 * <ol>
 *   <li>直接 {@code delete} 仍然被触发器挡住（唯一入口是库函数 {@code pf_audit_log_purge}）；
 *   <li>接口语法上只能表达"清理到某个时间点为止" —— 没有"删掉某一条"的形状；
 *   <li>清理自己留一条 {@code audit.purge}：只删不留痕的清理会把审计变成"可选择性追溯"。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AuditRetentionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void purgingRemovesOnlyRowsOlderThanTheCutoffAndAuditsItself() throws Exception {
    String admin = accessToken("admin", "admin");
    String tag = UUID.randomUUID().toString();
    insertAudit(tag + "-old-1", "400 days");
    insertAudit(tag + "-old-2", "400 days");
    insertAudit(tag + "-fresh", "1 hour");
    assertThat(countRowsLike(tag + "%")).isEqualTo(3);

    Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

    // ① dryRun 只数不删：留存期这件事先要看"会删掉多少"，直接删是不可逆的
    HttpResponse<String> dry = purge(admin, cutoff, true);
    assertThat(dry.statusCode()).as("dryRun 失败：%s", dry.body()).isEqualTo(200);
    assertThat(number(dry.body(), "candidates")).as("★ 两条过期记录要数进来").isEqualTo(2L);
    assertThat(number(dry.body(), "deleted")).as("★ dryRun 一行都不许删").isZero();
    assertThat(countRowsLike(tag + "%")).as("★ dryRun 之后库里的行数不许变").isEqualTo(3);

    // ② 真删：只删过期的那两条
    HttpResponse<String> real = purge(admin, cutoff, false);
    assertThat(real.statusCode()).as("清理失败：%s", real.body()).isEqualTo(200);
    assertThat(number(real.body(), "deleted")).isEqualTo(2L);
    assertThat(countRowsLike(tag + "%")).as("★ 新鲜的记录必须还在").isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pf_audit_log where resource_id = ?",
                Long.class,
                tag + "-fresh"))
        .as("★ 没到留存期的记录不能被顺手删掉")
        .isEqualTo(1L);

    // ③ 清理自己留痕：谁清的、清到什么时候、删了多少行
    Map<String, Object> purgeRow =
        jdbc.queryForMap(
            "select actor_name, resource_type, resource_id, detail->>'deleted' as deleted from"
                + " pf_audit_log where action = 'audit.purge' order by created_at desc limit 1");
    assertThat(purgeRow.get("actor_name")).as("审计里的操作人是快照").isEqualTo("管理员");
    assertThat(purgeRow.get("resource_type")).isEqualTo("AUDIT_LOG");
    assertThat(purgeRow.get("resource_id")).isEqualTo("pf_audit_log");
    assertThat(purgeRow.get("deleted")).as("★ 删了几行要写进 detail，不能事后靠猜").isEqualTo("2");
  }

  /** ★ 判据的核心：清理通道存在，不等于审计变成了普通表 —— 直接 DELETE / UPDATE 仍然被挡住。 */
  @Test
  void appendOnlyStillHoldsForEveryoneElse() {
    String tag = UUID.randomUUID().toString();
    insertAudit(tag + "-keep", "400 days");

    assertThatThrownBy(
            () -> jdbc.update("delete from pf_audit_log where resource_id = ?", tag + "-keep"))
        .as("★ 直接删审计必须被触发器挡住（唯一入口是 pf_audit_log_purge）")
        .hasMessageContaining("只允许插入");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update pf_audit_log set actor_name = '有人改过' where resource_id = ?",
                    tag + "-keep"))
        .as("★ 改历史记录在任何场景下都不允许（含清理通道：它只删不改）")
        .hasMessageContaining("只允许插入");

    assertThat(countRowsLike(tag + "%")).isEqualTo(1);
  }

  /** 清理是系统级运维动作，与"读审计"同级（`13 §7.2` 第 1 条）。 */
  @Test
  void purgingRequiresSystemManage() throws Exception {
    String operator = accessToken("zhangyun", "123456");
    HttpResponse<String> res = purge(operator, Instant.now().minus(3650, ChronoUnit.DAYS), false);
    assertThat(res.statusCode()).as("知识运营不该能清理审计：%s", res.body()).isEqualTo(403);
  }

  /** 缺截止时间 ⇒ 400，而且要明确指出缺的是什么（否则调用方会以为是权限问题）。 */
  @Test
  void purgingWithoutCutoffIsRejected() throws Exception {
    String admin = accessToken("admin", "admin");
    HttpResponse<String> res = post("/api/v1/audit-logs/purge", "{\"dryRun\":false}", admin);
    assertThat(res.statusCode()).as("缺截止时间必须是 400：%s", res.body()).isEqualTo(400);
    assertThat(res.body()).contains("截止时间");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private void insertAudit(String resourceId, String age) {
    jdbc.update(
        "insert into pf_audit_log (id, actor_id, actor_name, action, resource_type, resource_id,"
            + " created_at) values (?, null, null, 'document.delete', 'DOCUMENT', ?,"
            + " now() - interval '"
            + age
            + "')",
        UUID.randomUUID(),
        resourceId);
  }

  private long countRowsLike(String like) {
    Long n =
        jdbc.queryForObject(
            "select count(*) from pf_audit_log where resource_id like ?", Long.class, like);
    return n == null ? 0L : n;
  }

  private HttpResponse<String> purge(String token, Instant beforeAt, boolean dryRun)
      throws Exception {
    String body = "{\"beforeAt\":\"" + beforeAt.toString() + "\",\"dryRun\":" + dryRun + "}";
    return post("/api/v1/audit-logs/purge", body, token);
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
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String jsonField(String body, String key) {
    if (body == null) {
      return null;
    }
    var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }

  /** 数字字段（`"deleted":2`）—— 断言数字而不是整段 JSON 的文本形状。 */
  private static long number(String body, String key) {
    var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(body);
    if (!m.find()) {
      throw new IllegalStateException("响应里没有 " + key + "：" + body);
    }
    return Long.parseLong(m.group(1));
  }
}
