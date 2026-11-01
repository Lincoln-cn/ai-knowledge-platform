package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code #165}：<strong>未实现的子路径必须落 404，不许被 {@code /{id}} 吃掉变成 400</strong>。
 *
 * <h3>它要钉的到底是什么</h3>
 *
 * <p>契约有 {@code POST /assistants/import}（与 {@code /import/check}），后端**后来补上了**（{@code #195} 的导入子项），
 * 所以它不再是"契约有、后端没有"的代表；现在的代表是 {@code #198} 的 {@code /assistants/{id}/workflow} 一族 ——
 * 那一族<strong>已经落到只剩 2 条</strong>留在 {@code ContractDriftTest.KNOWN_MISSING} 里： {@code POST
 * /workflow/debug}、{@code GET /workflow/records}。
 *
 * <p>★ 是"已经落到只剩 2 条"，<strong>不是"本来就只有 2 条"</strong>：这一族原先登记的是 9 条，节点目录、
 * 草稿、校验、发布、版本列表/详情、版本回滚这些陆续落地后才收缩到这个数（每一次摘除都在 {@code ContractDriftTest} 的欠账清单里留了日期与批次）。 把它写成"2
 * 条"而不写这个来源，会让后来的人看不出这一族还在收缩。
 *
 * <p>★ <strong>400 在说假话</strong>：它的含义是"你的请求有问题"，而事实是"这个端点还没实现"。 排查的人会去查自己的参数，而不是查"这功能做了没有"。
 *
 * <p>★ 选 {@code /workflow} 而不是 {@code /import}：{@code POST /assistants/import} 已随 {@code #195}
 * 落地（它现在有真实 handler），{@code /workflow} 仍是"契约有、后端没有"的代表，判据不变。
 *
 * <p>★ 而且它让<strong>未实现清单的读数不一致</strong>：其它未实现端点都是 404 （{@code /analytics/*}、{@code
 * /prompts/*}、{@code /feedbacks/*}），只有这一条 400 ⇒ 任何"按状态码分类未实现端点"的扫查都会漏掉它。
 *
 * <h3>★ 为什么这条断言必须存在（而不是"看一眼走查闸门就够了"）</h3>
 *
 * <p>修法是给路径变量加 UUID 形状约束。而<strong>"加了约束"与"什么都没改"在读数上完全一样</strong> （走查闸门只记数、不区分 400 与 404）。⇒
 * 只有一条<strong>显式断言 404 而不是 400</strong> 的用例 才能把这件事钉住 —— 去掉约束它必须红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AssistantUnknownSubPathTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void unimplementedSubPathIs404NotBadRequest() throws Exception {
    String token = login();

    HttpResponse<String> res =
        get("/api/v1/assistants/00000000-0000-4000-8000-0000000000aa/workflow", token);

    assertThat(res.statusCode())
        .as(
            "★★ `/assistants/{id}/workflow` 是**契约里有、后端没有**的端点（#198）⇒ 必须是 404（未实现），"
                + "不许是 400 —— 400 会让排查的人去查自己的参数，而不是查这功能做了没有（#165）")
        .isEqualTo(404);
  }

  @Test
  void aMalformedIdIsStill404NotSilentlyTreatedAsAnId() throws Exception {
    // ★ 对照：这条防的是"把约束加得太宽" —— 比如为了让它 404 而把 `/{id}` 改成 `String`，
    //   那会让 `assistants/anything` 都进到服务层去查、可能变成 500。
    String token = login();

    HttpResponse<String> res = get("/api/v1/assistants/not-a-uuid", token);

    assertThat(res.statusCode()).as("★ 对照：非 UUID 的 id 段也必须落 404（未实现/不存在），不许变成 500").isEqualTo(404);
  }

  // ────────────────────────── 工具 ──────────────────────────

  private String login() throws Exception {
    HttpResponse<String> res =
        post("/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}", null);
    assertThat(res.statusCode()).as("前置：登录必须成功（否则下面的断言在测别的东西）").isEqualTo(200);
    String body = res.body();
    int at = body.indexOf("\"accessToken\":\"");
    assertThat(at).as("前置：响应里要有 accessToken").isGreaterThan(-1);
    int from = at + "\"accessToken\":\"".length();
    return body.substring(from, body.indexOf('"', from));
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> post(String path, String json, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
