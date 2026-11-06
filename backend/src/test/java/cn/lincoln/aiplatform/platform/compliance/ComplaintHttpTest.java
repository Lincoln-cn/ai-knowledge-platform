package cn.lincoln.aiplatform.platform.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 投诉举报 + 合规设置的端到端读数（{@code #83} 能力 1）。
 *
 * <h3>★ 这些断言怎么"能红"</h3>
 *
 * <ul>
 *   <li>状态机回退（RESOLVED→PROCESSING）放行 ⇒ {@code complaintLifecycleHonorsStateAndRequiredNote} 红。
 *   <li>终态缺处理说明放行 ⇒ 同方法红（"办结无说明"是留痕举证的失守点）。
 *   <li>越权读取返回 200/泄露存在性 ⇒ {@code complaintVisibilityIsScopedAndUnauthorizedIs404} 红。
 *   <li>无 {@code complaint:handle} 也能办理 ⇒ 同方法红（403 断言）。
 *   <li>反馈时限越界（0 / 100 天）被接受 ⇒ {@code complianceSettingsExposeFeedbackDeadline} 红。
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ComplaintHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void complaintLifecycleHonorsStateAndRequiredNote() throws Exception {
    String admin = login("admin", "admin");

    HttpResponse<String> created =
        send(
            admin,
            "POST",
            "/api/v1/complaints",
            "{\"kind\":\"COMPLAINT\",\"subject\":\"引用错位投诉\",\"content\":\"回答引用的分段与原文不符\",\"contact\":\"a@b.c\"}");
    assertThat(created.statusCode()).as("提交应 200，实际：%s", created.body()).isEqualTo(200);
    assertThat(created.body())
        .contains("\"ticketNo\":\"CMP-")
        .contains("\"status\":\"SUBMITTED\"")
        .contains("dueAt");
    String id = stringField(created.body(), "id");
    assertThat(id).as("返回应带工单 id").isNotBlank();

    HttpResponse<String> processing =
        send(
            admin,
            "PATCH",
            "/api/v1/complaints/" + id,
            "{\"status\":\"PROCESSING\",\"handlerNote\":\"核查中\"}");
    assertThat(processing.statusCode()).as("受理应 200，实际：%s", processing.body()).isEqualTo(200);
    assertThat(processing.body()).contains("\"status\":\"PROCESSING\"");

    // ★ 终态必填处理说明 —— 少了说明必须 400（留痕举证的失守点）。
    HttpResponse<String> noNote =
        send(
            admin,
            "PATCH",
            "/api/v1/complaints/" + id,
            "{\"status\":\"RESOLVED\",\"handlerNote\":\"\"}");
    assertThat(noNote.statusCode()).as("办结缺处理说明必须 400，实际：%s", noNote.body()).isEqualTo(400);

    HttpResponse<String> resolved =
        send(
            admin,
            "PATCH",
            "/api/v1/complaints/" + id,
            "{\"status\":\"RESOLVED\",\"handlerNote\":\"已答复并记录\"}");
    assertThat(resolved.statusCode()).as("办结应 200，实际：%s", resolved.body()).isEqualTo(200);
    assertThat(resolved.body()).contains("\"status\":\"RESOLVED\"").contains("已答复并记录");

    // ★ 状态只往前走 —— 终态回退必须 400。
    HttpResponse<String> rollback =
        send(
            admin,
            "PATCH",
            "/api/v1/complaints/" + id,
            "{\"status\":\"PROCESSING\",\"handlerNote\":\"回退\"}");
    assertThat(rollback.statusCode()).as("终态回退必须 400，实际：%s", rollback.body()).isEqualTo(400);
  }

  @Test
  void complaintVisibilityIsScopedAndUnauthorizedIs404() throws Exception {
    String admin = login("admin", "admin");

    // 建一个只有基础权限的用户（USER 角色，没有 complaint:handle）。
    String roleId = roleIdOf(send(admin, "GET", "/api/v1/roles", null).body(), "USER");
    String probe = "cp-user" + UUID.randomUUID().toString().substring(0, 8);
    HttpResponse<String> userCreated =
        send(
            admin,
            "POST",
            "/api/v1/users",
            "{\"username\":\""
                + probe
                + "\",\"displayName\":\"投诉测试用户\",\"roleId\":\""
                + roleId
                + "\",\"password\":\"CpUser#2026x\"}");
    assertThat(userCreated.statusCode()).as("建用户应 200，实际：%s", userCreated.body()).isEqualTo(200);
    String plain = login(probe, "CpUser#2026x");

    HttpResponse<String> adminTicket =
        send(admin, "POST", "/api/v1/complaints", "{\"subject\":\"管理员的投诉\",\"content\":\"内容 A\"}");
    String adminTicketId = stringField(adminTicket.body(), "id");

    HttpResponse<String> mine =
        send(plain, "POST", "/api/v1/complaints", "{\"subject\":\"普通用户的投诉\",\"content\":\"内容 B\"}");
    assertThat(mine.statusCode()).as("普通用户提交应 200（登录即可），实际：%s", mine.body()).isEqualTo(200);
    String myTicketId = stringField(mine.body(), "id");

    // ★ 裁剪：普通用户只看得到自己提交的。
    HttpResponse<String> plainList = send(plain, "GET", "/api/v1/complaints", null);
    assertThat(plainList.statusCode()).isEqualTo(200);
    assertThat(plainList.body())
        .as("普通用户的列表应只含自己的工单：%s", plainList.body())
        .contains("普通用户的投诉")
        .doesNotContain("管理员的投诉");

    // ★ 越权读取 404 —— 不暴露单据存在性。
    HttpResponse<String> cross = send(plain, "GET", "/api/v1/complaints/" + adminTicketId, null);
    assertThat(cross.statusCode()).as("越权读取必须 404，实际：%s", cross.body()).isEqualTo(404);

    // ★ 无 complaint:handle 不能办理。
    HttpResponse<String> crossPatch =
        send(
            plain,
            "PATCH",
            "/api/v1/complaints/" + adminTicketId,
            "{\"status\":\"PROCESSING\",\"handlerNote\":\"x\"}");
    assertThat(crossPatch.statusCode()).as("无办理权限必须 403，实际：%s", crossPatch.body()).isEqualTo(403);

    // 管理员看得到全部。
    HttpResponse<String> adminList = send(admin, "GET", "/api/v1/complaints", null);
    assertThat(adminList.body()).as("管理员应看到两方的工单").contains("管理员的投诉").contains("普通用户的投诉");
    assertThat(send(admin, "GET", "/api/v1/complaints/" + myTicketId, null).statusCode())
        .isEqualTo(200);
  }

  @Test
  void complianceSettingsExposeFeedbackDeadline() throws Exception {
    String admin = login("admin", "admin");

    HttpResponse<String> current = send(admin, "GET", "/api/v1/compliance/settings", null);
    assertThat(current.statusCode()).as("读设置应 200，实际：%s", current.body()).isEqualTo(200);
    assertThat(current.body())
        .as("缺省反馈时限 15 天（V83 种入值）：%s", current.body())
        .contains("\"complaintFeedbackDays\":15");

    HttpResponse<String> updated =
        send(admin, "PUT", "/api/v1/compliance/settings", "{\"complaintFeedbackDays\":7}");
    assertThat(updated.statusCode()).as("改时限应 200，实际：%s", updated.body()).isEqualTo(200);
    assertThat(updated.body()).contains("\"complaintFeedbackDays\":7");

    HttpResponse<String> tooSmall =
        send(admin, "PUT", "/api/v1/compliance/settings", "{\"complaintFeedbackDays\":0}");
    assertThat(tooSmall.statusCode()).as("0 天必须 400，实际：%s", tooSmall.body()).isEqualTo(400);

    HttpResponse<String> tooBig =
        send(admin, "PUT", "/api/v1/compliance/settings", "{\"complaintFeedbackDays\":100}");
    assertThat(tooBig.statusCode()).as("100 天必须 400，实际：%s", tooBig.body()).isEqualTo(400);
  }

  private HttpResponse<String> send(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
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

  private String login(String username, String password) throws Exception {
    HttpResponse<String> response =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    assertThat(response.statusCode())
        .as("登录 %s 应 200，实际：%s", username, response.body())
        .isEqualTo(200);
    return stringField(response.body(), "accessToken");
  }

  /** 从角色列表 JSON 里取指定 code 的角色 id（RoleView 的 id 是首字段 ⇒ 就近向前找）。 */
  private static String roleIdOf(String json, String code) {
    int codeAt = json.indexOf("\"code\":\"" + code + "\"");
    assertThat(codeAt).as("角色 %s 应在列表里：%s", code, json).isNotNegative();
    int idAt = json.lastIndexOf("\"id\":\"", codeAt);
    int start = json.indexOf('"', json.indexOf(':', idAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return json.substring(start, end);
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
