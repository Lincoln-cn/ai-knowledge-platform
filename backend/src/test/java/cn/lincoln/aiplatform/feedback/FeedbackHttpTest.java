package cn.lincoln.aiplatform.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
 * {@code #194}：反馈管理六个端点的真 HTTP 读数。
 *
 * <p>判据：① 列表能从 {@code cv_message.feedback} 读出问题/答案与默认 PENDING；② 统计四态齐全；③ 单条办理 落状态/处理人/说明；④ 重开回到
 * PROCESSING 且清处理时间；⑤ 批量逐条回执（一条成功、一条失败）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class FeedbackHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Test
  void feedbackManagementLifecycleIsServedFromMessageFeedback() throws Exception {
    UUID conversationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    UUID answerId = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        """
        insert into cv_conversation
          (id, user_id, title, message_count, max_classification, created_at, updated_at)
        values (?, ?, ?, 2, 'PUBLIC', ?, ?)
        """,
        conversationId,
        userId,
        "反馈测试会话",
        Timestamp.from(now),
        Timestamp.from(now));
    jdbc.update(
        """
        insert into cv_message (id, conversation_id, role, content, status, created_at, updated_at)
        values (?, ?, 'USER', ?, 'GENERATED', ?, ?)
        """,
        questionId,
        conversationId,
        "报销标准是多少？",
        Timestamp.from(now.minusSeconds(120)),
        Timestamp.from(now.minusSeconds(120)));
    jdbc.update(
        """
        insert into cv_message
          (id, conversation_id, role, content, status, feedback, created_at, updated_at)
        values (?, ?, 'ASSISTANT', ?, 'GENERATED', 'DOWN', ?, ?)
        """,
        answerId,
        conversationId,
        "报销标准见附件。",
        Timestamp.from(now.minusSeconds(60)),
        Timestamp.from(now.minusSeconds(60)));

    String token = login();

    HttpResponse<String> list = send(token, "GET", "/api/v1/feedbacks?status=PENDING", null);
    assertThat(list.statusCode()).as("列表应 200，实际：%s", list.body()).isEqualTo(200);
    assertThat(list.body())
        .contains("\"total\":1")
        .contains("报销标准是多少？")
        .contains("报销标准见附件。")
        .contains("\"status\":\"PENDING\"")
        .contains("\"type\":\"DOWN\"");

    HttpResponse<String> stats = send(token, "GET", "/api/v1/feedbacks/stats", null);
    assertThat(stats.statusCode()).isEqualTo(200);
    assertThat(stats.body())
        .contains("\"PENDING\":1")
        .contains("\"PROCESSING\":0")
        .contains("\"RESOLVED\":0")
        .contains("\"IGNORED\":0");

    HttpResponse<String> noResolution =
        send(
            token,
            "PATCH",
            "/api/v1/feedbacks/" + answerId,
            "{\"status\":\"RESOLVED\",\"assignee\":\"张三\",\"resolution\":\"\"}");
    assertThat(noResolution.statusCode()).as("RESOLVED 无处理说明应 400").isEqualTo(400);

    HttpResponse<String> handled =
        send(
            token,
            "PATCH",
            "/api/v1/feedbacks/" + answerId,
            "{\"status\":\"RESOLVED\",\"assignee\":\"张三\",\"resolution\":\"已回复用户\"}");
    assertThat(handled.statusCode()).as("办理应 200，实际：%s", handled.body()).isEqualTo(200);
    assertThat(handled.body())
        .contains("\"status\":\"RESOLVED\"")
        .contains("\"assignee\":\"张三\"")
        .contains("\"resolution\":\"已回复用户\"")
        .contains("\"handledAt\":\"");

    HttpResponse<String> reopened =
        send(token, "POST", "/api/v1/feedbacks/" + answerId + "/reopen", null);
    assertThat(reopened.statusCode()).isEqualTo(200);
    assertThat(reopened.body())
        .contains("\"status\":\"PROCESSING\"")
        .contains("\"handledAt\":null");

    HttpResponse<String> batch =
        send(
            token,
            "PATCH",
            "/api/v1/feedbacks/batch",
            "{\"ids\":[\""
                + answerId
                + "\",\"00000000-0000-0000-0000-000000000000\"],\"status\":\"IGNORED\"}");
    assertThat(batch.statusCode()).as("批量应 200，实际：%s", batch.body()).isEqualTo(200);
    assertThat(batch.body()).contains("\"accepted\":[").contains("\"rejected\":[");
    assertThat(batch.body()).contains("反馈不存在");
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(20));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String login() throws Exception {
    HttpResponse<String> response =
        send(null, "POST", "/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}");
    assertThat(response.statusCode()).as("admin 必须能登录").isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      return null;
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return end < 0 ? null : json.substring(start, end);
  }
}
