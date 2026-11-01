package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * 文档版本<b>当前真相</b>：同一个文件名上传两次 = <b>两条互不相关的记录</b>（{@code #86} 判据 ③）。
 *
 * <h3>它防的是哪种读错</h3>
 *
 * <p>{@code kb_document} 上已经有了 {@code revision} 与 {@code supersedes_id} 两列（{@code V55}），
 * 这很容易让人以为"平台支持文档版本了"。事实上两列今天<b>恒为 1 / NULL</b>，没有任何代码读写它们 ——
 * 同名再次上传只会多一条独立记录，旧的那条仍然在库里、仍然能被检索到（{@code 12 §十三}）。
 *
 * <p>★ 这个读错的后果很具体：以为有版本，就不会去查"召回的是哪一版"，于是 <b>旧版与新版的分段一起进候选集，两条引用看起来都很权威</b>。
 *
 * <h3>它为什么能红</h3>
 *
 * <table>
 *   <tr><th>改法</th><th>读数</th></tr>
 *   <tr><td>开始写版本链（同名上传时把旧的标 {@code superseded}、新记录 {@code supersedes_id} 指向旧的）</td>
 *       <td>★ 本类两条断言都红 —— 那一刻必须同步改 {@code 02 §5.1.2}、{@code 12 §十三} 与 Issue {@code #86} 的裁定</td></tr>
 *   <tr><td>把 {@code revision} 改成每次上传自增</td><td>★ 红</td></tr>
 * </table>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentRevisionSeamTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void reuploadingTheSameNameCreatesTwoUnrelatedRowsNotAVersionChain() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "版本留缝库", "INTERNAL");
    String orgId = orgIdByCode("PURCHASING");

    for (int i = 0; i < 2; i++) {
      HttpResponse<String> res =
          send(
              "POST",
              "/api/v1/knowledge-bases/" + kbId + "/documents",
              registerBody("同一份制度.md", orgId, "INTERNAL"),
              admin);
      assertThat(res.statusCode()).as("第 %d 次登记要成功：%s", i + 1, res.body()).isEqualTo(200);
    }

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select revision, supersedes_id from kb_document where kb_id = ?::uuid and name = ?",
            kbId,
            "同一份制度.md");

    assertThat(rows).as("同名两次上传 ⇒ 两条记录（不是一条被覆盖）").hasSize(2);
    assertThat(rows)
        .as(
            """
            ★ 两条记录的 revision 都必须还是 1：这一列今天是「留缝」，不是「已经会自增的版本号」。
            若这条红了，说明平台开始写版本了 —— 请同时更新 02 §5.1.2、12 §十三 与本 Issue #86 的裁定。\
            """)
        .allSatisfy(row -> assertThat(row.get("revision")).isEqualTo(1));
    assertThat(rows)
        .as("★ supersedes_id 必须还是 NULL：两条记录之间**没有**「谁取代谁」的关系 —— 这正是缺口本身")
        .allSatisfy(row -> assertThat(row.get("supersedes_id")).isNull());
  }

  private String registerBody(String name, String orgId, String classification) {
    return """
    {"names":["%s"],"docType":"制度","orgId":"%s","classification":"%s"}\
    """
        .formatted(name, orgId, classification);
  }

  private String orgIdByCode(String code) {
    return jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, code);
  }

  private String createKb(String token, String name, String maxClassification) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"版本留缝测试用库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"%s"}\
        """
            .formatted(name, UUID.randomUUID(), maxClassification);
    HttpResponse<String> res = send("POST", "/api/v1/knowledge-bases", body, token);
    String id = field(res.body(), "id");
    if (id == null) {
      throw new IllegalStateException("建库失败：" + res.body());
    }
    return id;
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        send(
            "POST",
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    String token = field(res.body(), "accessToken");
    if (token == null) {
      throw new IllegalStateException("登录失败：" + res.body());
    }
    return token;
  }

  private static String field(String json, String key) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }

  private HttpResponse<String> send(String method, String path, String body, String token)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
