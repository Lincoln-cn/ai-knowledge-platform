package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * 会话分享三条端点的真库读数（#187）。
 *
 * <h3>★ 断言的重心不是"200 了"，而是"公开面真的能按令牌读，撤销后同一个令牌立刻读不到"</h3>
 *
 * <p>撤销若只改一行状态却忘了公开面复判，读数仍然全绿（列表里有 revokedAt、公开面却还开着）—— 所以每条链接都从<strong>公开面</strong>回读一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ConversationShareHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Test
  void shareIsReadableByTokenUntilRevoked() throws Exception {
    String admin = login("admin", "admin");
    String liwei = login("liwei", "123456");
    String conversationId = createConversation(admin);

    HttpResponse<String> created =
        send(
            admin,
            "POST",
            "/api/v1/conversations/" + conversationId + "/shares",
            "{\"scope\":\"CONVERSATION\"}");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    assertThat(created.body()).contains("\"scope\":\"CONVERSATION\"");
    assertThat(created.body()).contains("\"url\":\"/#/share/shr_");
    assertThat(created.body()).contains("\"maxClassification\":\"PUBLIC\"");
    String token = stringField(created.body(), "token");
    assertThat(token).startsWith("shr_").hasSize(28);

    HttpResponse<String> listed =
        send(admin, "GET", "/api/v1/conversations/" + conversationId + "/shares", null);
    assertThat(listed.statusCode()).isEqualTo(200);
    assertThat(listed.body()).contains(token);

    HttpResponse<String> publicRead = send(null, "GET", "/api/v1/public/shares/" + token, null);
    assertThat(publicRead.statusCode()).as(publicRead.body()).isEqualTo(200);
    assertThat(publicRead.body()).contains("\"messages\":[]");

    String messageId = insertMessage(conversationId);

    HttpResponse<String> foreign =
        send(liwei, "GET", "/api/v1/conversations/" + conversationId + "/shares", null);
    assertThat(foreign.statusCode()).as("别人的会话必须是 404，不是 403").isEqualTo(404);

    HttpResponse<String> badScope =
        send(
            admin,
            "POST",
            "/api/v1/conversations/" + conversationId + "/shares",
            "{\"scope\":\"EVERYONE\"}");
    assertThat(badScope.statusCode()).isEqualTo(400);
    assertThat(badScope.body()).contains("APP / CONVERSATION / MESSAGE");

    HttpResponse<String> past =
        send(
            admin,
            "POST",
            "/api/v1/conversations/" + conversationId + "/shares",
            "{\"scope\":\"CONVERSATION\",\"expiresAt\":\""
                + Instant.now().minusSeconds(60)
                + "\"}");
    assertThat(past.statusCode()).isEqualTo(400);
    assertThat(past.body()).contains("有效期必须在将来");

    HttpResponse<String> messageShare =
        send(
            admin,
            "POST",
            "/api/v1/conversations/" + conversationId + "/shares",
            "{\"scope\":\"MESSAGE\",\"messageId\":\"" + messageId + "\"}");
    assertThat(messageShare.statusCode()).as(messageShare.body()).isEqualTo(200);
    String messageToken = stringField(messageShare.body(), "token");
    HttpResponse<String> messageRead =
        send(null, "GET", "/api/v1/public/shares/" + messageToken, null);
    assertThat(messageRead.statusCode()).isEqualTo(200);
    assertThat(messageRead.body()).contains("\"messages\":[");
    assertThat(messageRead.body()).contains(messageId);

    HttpResponse<String> missingMessage =
        send(
            admin,
            "POST",
            "/api/v1/conversations/" + conversationId + "/shares",
            "{\"scope\":\"MESSAGE\",\"messageId\":\"" + UUID.randomUUID() + "\"}");
    assertThat(missingMessage.statusCode()).isEqualTo(404);
    assertThat(missingMessage.body()).contains("消息不存在");

    HttpResponse<String> revoked =
        send(admin, "DELETE", "/api/v1/conversations/" + conversationId + "/shares/" + token, null);
    assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
    assertThat(revoked.body()).contains("\"revokedAt\"");
    HttpResponse<String> afterRevoke = send(null, "GET", "/api/v1/public/shares/" + token, null);
    assertThat(afterRevoke.statusCode()).as("撤销后同一个令牌必须当场失效").isEqualTo(404);
    assertThat(afterRevoke.body()).contains("已撤销");

    HttpResponse<String> revokeAgain =
        send(admin, "DELETE", "/api/v1/conversations/" + conversationId + "/shares/" + token, null);
    assertThat(revokeAgain.statusCode()).as("再撤一次是幂等").isEqualTo(200);

    HttpResponse<String> unknown =
        send(null, "GET", "/api/v1/public/shares/shr_000000000000000000000000", null);
    assertThat(unknown.statusCode()).isEqualTo(404);
  }

  private String insertMessage(String conversationId) {
    UUID messageId = UUID.randomUUID();
    jdbc.update(
        """
        insert into cv_message (id, conversation_id, role, content, status, created_at, updated_at)
        values (?, ?, 'USER', '分享读数用的一条消息', 'GENERATED', now(), now())
        """,
        messageId,
        UUID.fromString(conversationId));
    return messageId.toString();
  }

  private String createConversation(String token) throws Exception {
    HttpResponse<String> response = send(token, "POST", "/api/v1/conversations", "{}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return stringField(response.body(), "id");
  }

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(response.statusCode()).as("登录失败：" + response.body()).isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String stringField(String body, String name) {
    String needle = "\"" + name + "\":\"";
    int start = body.indexOf(needle);
    assertThat(start).as("响应里没有 " + name + "：" + body).isGreaterThanOrEqualTo(0);
    int from = start + needle.length();
    return body.substring(from, body.indexOf('"', from));
  }
}
