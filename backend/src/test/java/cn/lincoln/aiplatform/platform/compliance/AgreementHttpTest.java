package cn.lincoln.aiplatform.platform.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * 服务协议同意留痕（{@code #83} 第九条 / 第十条）的集成测试。
 *
 * <p>★ 判据三条，各自写明怎么红：① 未同意过 ⇒ {@code GET .../consent} 的 {@code data} 为 null（改掉 {@code
 * AgreementService.latestOf} 的空回落 ⇒ 断言红）；② 同意落库且带版本号（把 insert 改成不落版本 ⇒ 断言红）； ③ 同版本重复同意幂等（删掉 {@code
 * DuplicateKeyException} 幂等分支 ⇒ 主键冲突 500 红）。
 *
 * <p>★ JSON 提取用手撕（与 {@code ComplaintHttpTest} 同法）：测试代码不许用 Jackson 2 的 {@code JsonNode} （{@code
 * JacksonTwoUsageTest} 盯这条，例外只在它的白名单里）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AgreementHttpTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Test
  void consentIsRecordedOncePerVersionAndQueryable() throws Exception {
    String token = login("admin", "admin");

    // ① 从未同意过 ⇒ data 为 null（真栈独立库 ⇒ 干净状态）。
    String before = send(token, "GET", "/api/v1/compliance/agreement/consent", null);
    assertThat(before).as("未同意过时 data 应为 null").contains("\"data\":null");

    // ② 同意落库：版本号原样、时刻有值（改掉落版本/时刻的 insert ⇒ 这里红）。
    String created =
        send(
            token,
            "POST",
            "/api/v1/compliance/agreement/consent",
            "{\"version\":\"v1-2026-11-06\"}");
    assertThat(stringField(created, "version"))
        .as("留痕必须记下用户同意的协议版本号（否则「用户见过哪一版」无法举证）")
        .isEqualTo("v1-2026-11-06");
    assertThat(stringField(created, "agreedAt")).as("同意时刻必须留痕").isNotEmpty();

    // ③ 同版本重复同意幂等：返回同一条（同 id），不报 500、不产生第二行。
    //   （删掉 DuplicateKeyException 幂等分支 ⇒ 主键冲突 500 ⇒ 这里红。）
    String again =
        send(
            token,
            "POST",
            "/api/v1/compliance/agreement/consent",
            "{\"version\":\"v1-2026-11-06\"}");
    assertThat(stringField(again, "id"))
        .as("同版本重复提交必须幂等（返回首次留痕）")
        .isEqualTo(stringField(created, "id"));
    Integer rows =
        jdbc.queryForObject(
            "select count(*) from platform_agreement_consent where agreement_version ="
                + " 'v1-2026-11-06'",
            Integer.class);
    assertThat(rows).as("同版本只留一行").isEqualTo(1);

    // ④ 查询返回最近一次留痕（「关于」/首登弹窗按版本比对用）。
    String after = send(token, "GET", "/api/v1/compliance/agreement/consent", null);
    assertThat(stringField(after, "version")).isEqualTo("v1-2026-11-06");

    // ⑤ 版本号校验：空版本 400（防空同意把「已同意」变成废值）。
    HttpResponse<String> empty =
        sendRaw(token, "POST", "/api/v1/compliance/agreement/consent", "{\"version\":\"\"}");
    assertThat(empty.statusCode()).as("空版本号必须 400").isEqualTo(400);
  }

  private String login(String username, String password) throws Exception {
    String body =
        send(
            null,
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    return stringField(body, "accessToken");
  }

  private String send(String token, String method, String path, String body) throws Exception {
    return sendRaw(token, method, path, body).body();
  }

  private HttpResponse<String> sendRaw(String token, String method, String path, String body)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 从响应 JSON 里取字符串字段值（手撕，不引 Jackson）。 */
  private static String stringField(String json, String field) {
    int keyAt = json.indexOf("\"" + field + "\"");
    if (keyAt < 0) {
      throw new IllegalStateException("响应缺少字段 " + field + "：" + json);
    }
    int start = json.indexOf('"', json.indexOf(':', keyAt) + 1) + 1;
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }
}
