package cn.lincoln.aiplatform.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 业务示例数据（{@code db/seed/V900__sample_business_data.sql}）的<strong>真 HTTP</strong>读数。
 *
 * <h3>★ 这条测试要证的三件事</h3>
 *
 * <ol>
 *   <li><strong>开关真的接上了</strong>：只加 {@code app.seed.sample-data=true}，Flyway 就会把 {@code
 *       classpath:db/seed} 一起扫（{@link SampleDataFlywayConfiguration}）。反过来说， 默认配置下本类查到的东西一条都不存在 ——
 *       全量测试不受影响这件事由"别的测试仍然绿"来证。
 *   <li><strong>脚本本身是合法的</strong>：所有库层 CHECK（V21/V22 的 {@code classification_rank} 生成列、 V27 的
 *       {@code org_id}、V29 的 {@code chk_as_model_by_type}、V34 的三列域标记）
 *       都能过。脚本写错时会在这里直接炸出约束名，而不是等到试用时才发现。
 *   <li><strong>示例数据是"能用"的那一半，不是"跑完"的那一半</strong>：文档停在 {@code PENDING_EMBEDDING}/0 段已处理、模型是 {@code
 *       NOT_LOADED}。这是刻意的 —— 新用户第一次 打开系统要走的路正好是「配模型 → 加载 → 向量化」，示例数据替他跑完就看不到那段状态机了。
 * </ol>
 *
 * <h3>★ 反证（为什么这不算"自证"）</h3>
 *
 * <p>① 把 {@link SampleDataFlywayConfiguration} 的 {@code @ConditionalOnProperty} 去掉（改成永远生效）， {@code
 * KnowledgeBaseManagementTest}「第一页 size=2」那类按条数断言的用例会红 —— 也就是"默认关" 这件事是有读数的，不是一句注释；② 把 V900 里任何一条
 * {@code insert} 的密级写成不存在的值， 本类会先在库层 CHECK 上报错。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // ★ 本类唯一要紧的那一行：打开示例数据，让 Flyway 多扫 classpath:db/seed
      "app.seed.sample-data=true"
    })
class SampleBusinessDataSeedTest {

  private static final String KB_SYSTEM = "5ee20000-0000-4000-8000-000000000001";
  private static final String DOC_SYSTEM = "5ee30000-0000-4000-8000-000000000001";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  /** 全类共用一个令牌：登录是慢操作，且本类不改任何身份状态。 */
  private String token;

  @Test
  void seededKnowledgeBasesComeBackWithTheirRealSegmentCounts() throws Exception {
    JsonNode list = data(get("/api/v1/knowledge-bases?size=50"));

    JsonNode system = byName(list.get("list"), "制度规范示例库");
    assertThat(system).as("示例库必须能被列表读出来（不是只躺在库里）").isNotNull();
    assertThat(system.get("documentCount").asInt()).as("文档数应当从真实行算回来").isEqualTo(1);
    assertThat(system.get("segmentCount").asInt()).as("分段数同理，不许手写常量").isEqualTo(3);

    JsonNode standards = byName(list.get("list"), "行业标准示例库");
    assertThat(standards).as("三档密级上限的第三个库也要在").isNotNull();
    assertThat(standards.get("maxClassification").asText())
        .as("公开库这一格是拿来对比可见性的，密级上限必须是 PUBLIC")
        .isEqualTo("PUBLIC");
  }

  @Test
  void seededDocumentIsParsedButStillWaitingForItsVectorization() throws Exception {
    JsonNode docs = data(get("/api/v1/knowledge-bases/" + KB_SYSTEM + "/documents?page=1&size=50"));
    JsonNode doc = byName(docs.get("list"), "差旅与费用报销管理办法.md");

    assertThat(doc).as("示例文档必须在，否则库的 documentCount 就是假的").isNotNull();
    assertThat(doc.get("status").asText())
        .as("★ 停在待向量化：向量化是用户的显式动作，示例数据不替他跑完")
        .isEqualTo("PENDING_EMBEDDING");
    assertThat(doc.get("segmentCount").asInt()).isEqualTo(3);
    assertThat(doc.get("processedSegments").asInt()).as("一段都还没向量化").isZero();

    JsonNode segments = data(get("/api/v1/segments?documentId=" + DOC_SYSTEM + "&page=1&size=50"));
    JsonNode rows = segments.get("list");
    assertThat(rows).as("三段都要在").hasSize(3);
    for (JsonNode seg : rows) {
      assertThat(seg.get("embedding").asText())
          .as("每段的向量化状态都要是待向量化 —— 否则'已就绪'这件事在界面上是假的")
          .isEqualTo("PENDING_EMBEDDING");
      assertThat(seg.get("content").asText()).as("分段正文不能为空").isNotBlank();
    }
    assertThat(rows.get(0).get("content").asText()).as("正文要真的是那段制度文字").contains("八十元");
  }

  @Test
  void seededAssistantCarriesItsPromptBindings() throws Exception {
    JsonNode assistants = data(get("/api/v1/assistants?size=50"));
    JsonNode withPrompts = byName(assistants.get("list"), "产品说明问答示例");

    assertThat(withPrompts).as("示例应用必须能被列表读出来").isNotNull();
    assertThat(withPrompts.get("promptIds"))
        .as("★ 这条示例应用绑了两条提示词：`#215` 之后它们真的会进 system prompt，界面上也看得见")
        .hasSize(2);
    assertThat(withPrompts.get("kbIds")).as("应用只绑一个库 —— 示例数据刻意让'检索边界'可观察").hasSize(1);
  }

  @Test
  void seededModelsArePresentButNotLoaded() throws Exception {
    JsonNode models = data(get("/api/v1/models?size=50"));

    JsonNode chat = byName(models.get("list"), "示例对话模型（待配置）");
    JsonNode embedding = byName(models.get("list"), "示例向量模型 bge-m3（待配置）");

    assertThat(chat).as("对话模型占位要在").isNotNull();
    assertThat(embedding).as("向量模型占位要在").isNotNull();
    assertThat(chat.get("status").asText()).as("★ 不替用户决定接哪个推理服务：示例模型一律未加载").isEqualTo("NOT_LOADED");
    assertThat(embedding.get("dimension").asInt()).as("向量模型的维度是建库的必填项").isEqualTo(1024);
  }

  private JsonNode byName(JsonNode rows, String name) {
    if (rows == null || !rows.isArray()) {
      return null;
    }
    for (JsonNode row : rows) {
      if (name.equals(row.path("name").asText())) {
        return row;
      }
    }
    return null;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as("响应体：%s", response.body()).isEqualTo(200);
    JsonNode body = json.readTree(response.body());
    assertThat(body.path("ok").asBoolean()).as("包络：%s", response.body()).isTrue();
    return body.get("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token())
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String token() throws Exception {
    if (token != null) {
      return token;
    }
    String body = "{\"username\":\"admin\",\"password\":\"admin\"}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("登录：%s", response.body()).isEqualTo(200);
    token = json.readTree(response.body()).path("data").path("accessToken").asText();
    assertThat(token).as("没拿到令牌，后面的断言全都没意义").isNotBlank();
    return token;
  }
}
