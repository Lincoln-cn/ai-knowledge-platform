package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * 删除前的引用检查（{@code #300}）：有引用 ⇒ 409 + 明细；无引用 ⇒ 照旧 200。
 *
 * <h3>★ 为什么单独一个类</h3>
 *
 * <p>{@link ModelApiTest} 用的是"注册 → 删除"的 API 链路，而引用方（应用 / 知识库 / 文档 / 平台默认键）都住在别的模块。
 * 这里直接在库里种引用、只走删除与引用面两个端点 —— 这样才能把五类引用<strong>逐类</strong>摆到同一个断言里， 而不是"有一条引用就够了"。
 *
 * <h3>★ 三条刻意挑的断言</h3>
 *
 * <ol>
 *   <li><strong>409 的 {@code data} 里必须同时出现四类</strong>（应用名 / 库名 / 文档数 / 平台默认键） —— 只断 409 状态码，
 *       等于允许后端返回"还有引用"四个字，用户仍然不知道该改绑什么
 *   <li><strong>强删模型不做静默级联</strong>：删完之后应用那一行的 {@code model_id} 必须<strong>还是</strong>那个 id ——
 *       一是证明我们没有偷偷改绑（那是另一个动作），二是钉住"force 不是 cascade"
 *   <li><strong>强删服务商时，若它下面的模型自己被引用 ⇒ 仍然 409</strong>：这条比 issue 判据 5 更严， 理由是"删服务商"不该把别人的应用弄坏
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class ModelReferenceGuardTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private String adminToken;

  @BeforeAll
  void login() throws Exception {
    HttpResponse<String> res =
        send("POST", "/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}", null);
    adminToken = stringField(res.body(), "accessToken");
    assertThat(adminToken).as("管理员登录失败：" + res.body()).isNotBlank();
  }

  @BeforeEach
  void clean() {
    // 只清本类会碰的表：引用方是纯 uuid 列（没有外键），所以先清它们、再清模型与服务商
    jdbc.update("delete from kb_document");
    jdbc.update("delete from kb_knowledge_base");
    jdbc.update("delete from as_assistant");
    jdbc.update("update cv_chat_settings set default_model_id = null");
    jdbc.update("delete from ai_model");
    jdbc.update("delete from ai_model_provider");
  }

  // ────────────────────────── 模型侧 ──────────────────────────

  /** 判据 1：有引用时 409，且响应体四类引用各有名字或计数。 */
  @Test
  void deleteModel_withReferences_returns409WithAllFourKinds() throws Exception {
    UUID chat = insertModel("被引用的对话模型", "CHAT", null);
    UUID embed = insertModel("被引用的向量模型", "EMBEDDING", null);
    insertAssistant("客服助手", chat);
    UUID kb = insertKb("产品知识库", embed);
    insertDoc(kb, "说明书.pdf");
    insertDoc(kb, "FAQ.docx");
    jdbc.update("update cv_chat_settings set default_model_id = ?", chat);

    // 对话模型：应用 1 + 平台默认键 1
    HttpResponse<String> chatRefs = get("/api/v1/models/" + chat + "/references", adminToken);
    assertThat(chatRefs.statusCode()).isEqualTo(200);
    assertThat(chatRefs.body())
        .as("引用面要能说出是哪个应用")
        .contains("客服助手")
        .as("平台默认键必须显式列出（cv_chat_settings.default_model_id）")
        .contains("cv_chat_settings.default_model_id");

    HttpResponse<String> chatDelete = delete("/api/v1/models/" + chat, adminToken);
    assertThat(chatDelete.statusCode()).as("有引用时删除必须是 409（不是 400、更不能 200）").isEqualTo(409);
    assertThat(chatDelete.body()).contains("\"code\":409");
    assertThat(chatDelete.body())
        .as("message 要能让人不展开 JSON 也知道坏了什么")
        .contains("客服助手")
        .contains("cv_chat_settings.default_model_id");
    assertThat(chatDelete.body()).as("hint 要给出出路（改绑，或明知后果走「强制删除」）").contains("强制删除");

    // 向量模型：知识库 1 + 文档 2
    HttpResponse<String> embedDelete = delete("/api/v1/models/" + embed, adminToken);
    assertThat(embedDelete.statusCode()).isEqualTo(409);
    assertThat(embedDelete.body())
        .as("知识库名字必须有")
        .contains("产品知识库")
        .as("文档数必须报出来 —— issue 第 3 类")
        .contains("2 篇");

    // 两行都还在：守卫的意义就是"没删成"
    assertThat(count("select count(*) from ai_model where id = ?", chat)).isEqualTo(1);
    assertThat(count("select count(*) from ai_model where id = ?", embed)).isEqualTo(1);
  }

  /** 判据 3：没有引用时删除照旧成功（守卫不能把正常清理也堵死）。 */
  @Test
  void deleteModel_withoutReferences_succeeds() throws Exception {
    UUID id = insertModel("没人用的模型", "CHAT", null);

    HttpResponse<String> res = delete("/api/v1/models/" + id, adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(count("select count(*) from ai_model where id = ?", id)).isZero();
  }

  /**
   * ★ 反静默级联：{@code force=true} 允许删掉被引用的模型，但<strong>绝不替用户改绑</strong>。
   *
   * <p>断言的是"应用那一行还在、还是同一个 model_id" —— 这样读代码的人不会把 force 误读成 cascade。
   */
  @Test
  void forceDeleteModel_doesNotSilentlyRewireReferences() throws Exception {
    UUID model = insertModel("要强删的模型", "CHAT", null);
    insertAssistant("受影响的助手", model);

    HttpResponse<String> res = delete("/api/v1/models/" + model + "?force=true", adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(count("select count(*) from ai_model where id = ?", model)).isZero();

    Object stillPoints =
        jdbc.queryForObject("select model_id from as_assistant where name = '受影响的助手'", UUID.class);
    assertThat(stillPoints).as("force 不许静默改绑/清空引用 —— 应用这一行必须还是原样指向那个 id").isEqualTo(model);
  }

  // ────────────────────────── 服务商侧 ──────────────────────────

  /** 判据 5（阻止这条腿）：服务商下面还有模型 ⇒ 409，且明细里能看见模型名。 */
  @Test
  void deleteProvider_withModels_returns409ListingModelNames() throws Exception {
    UUID provider = insertProvider("被测服务商");
    insertModel("挂在它下面的模型A", "CHAT", provider);
    insertModel("挂在它下面的模型B", "CHAT", provider);

    HttpResponse<String> res = delete("/api/v1/model-providers/" + provider, adminToken);
    assertThat(res.statusCode()).as("已有守卫的状态码从 400 改成 409（资源状态不允许，不是请求错）").isEqualTo(409);
    assertThat(res.body())
        .as("明细要能看见模型名，而不是只说「还有 2 个模型」")
        .contains("挂在它下面的模型A")
        .contains("挂在它下面的模型B");
    assertThat(count("select count(*) from ai_model_provider where id = ?", provider))
        .as("被拦住 ⇒ 服务商必须还在")
        .isEqualTo(1);
  }

  /**
   * ★★ 比判据 5 更严的一条：{@code force=true} 也救不了"模型自己被应用引用"的情况。
   *
   * <p>理由：删服务商会连带删模型，而这些模型正被应用用着 —— 用户点的是"删服务商"， 不该收到"你的应用坏了"这种后果。要删就先改绑应用。
   */
  @Test
  void forceDeleteProvider_still409WhenItsModelsAreReferenced() throws Exception {
    UUID provider = insertProvider("有下游的服务商");
    UUID model = insertModel("被应用引用的模型", "CHAT", provider);
    insertAssistant("不许被弄坏的助手", model);

    HttpResponse<String> res =
        delete("/api/v1/model-providers/" + provider + "?force=true", adminToken);
    assertThat(res.statusCode()).isEqualTo(409);
    assertThat(res.body()).as("必须点名是哪个应用 —— 否则用户不知道该改绑谁").contains("不许被弄坏的助手");
    assertThat(count("select count(*) from ai_model_provider where id = ?", provider)).isEqualTo(1);
    assertThat(count("select count(*) from ai_model where id = ?", model)).isEqualTo(1);
  }

  /** 判据 5（一并删这条腿）：模型没有任何下游引用时，force 一并删掉，且不留悬空 {@code provider_id}。 */
  @Test
  void forceDeleteProvider_removesItsModelsAndLeavesNoDanglingProviderId() throws Exception {
    UUID provider = insertProvider("可清理的服务商");
    UUID a = insertModel("无主模型A", "CHAT", provider);
    UUID b = insertModel("无主模型B", "EMBEDDING", provider);

    HttpResponse<String> res =
        delete("/api/v1/model-providers/" + provider + "?force=true", adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(count("select count(*) from ai_model_provider where id = ?", provider)).isZero();
    assertThat(count("select count(*) from ai_model where id in (?, ?)", a, b)).isZero();
    assertThat(count("select count(*) from ai_model where provider_id = ?", provider))
        .as("关键：库里不能有任何一行的 provider_id 还指向已删服务商（悬空指针就是 #300 的事故形态）")
        .isZero();
  }

  // ────────────────────────── 种子 ──────────────────────────

  /** 种一条模型（不加 FK 依赖：{@code providerId} 为空就是旧式直连）。 */
  private UUID insertModel(String name, String kind, UUID providerId) {
    UUID id =
        jdbc.queryForObject(
            "insert into ai_model (id, name, kind, supplier, base_model, status, enabled, thinking,"
                + " tools, builtin, provider_id, dimension, context_window, created_at, updated_at)"
                + " values (gen_random_uuid(), ?, ?, 'LOCAL_INFERENCE', 'stub-plain', 'NOT_LOADED',"
                + " false, 'NONE', 'UNSUPPORTED', false, ?, ?, ?, now(), now()) returning id",
            UUID.class,
            name,
            kind,
            providerId,
            "EMBEDDING".equals(kind) ? 768 : null,
            "CHAT".equals(kind) ? 8192 : null);
    return id;
  }

  private UUID insertProvider(String name) {
    return jdbc.queryForObject(
        "insert into ai_model_provider (id, name, kind, api_base, auth_style, egress, enabled,"
            + " created_at, updated_at) values (gen_random_uuid(), ?, 'OPENAI',"
            + " 'http://127.0.0.1:1/v1', 'BEARER', 'LOCAL', true, now(), now()) returning id",
        UUID.class,
        name);
  }

  /** 种一个简单应用（{@code chk_as_model_by_type} 要求 SIMPLE 必须有 model_id）。 */
  private void insertAssistant(String name, UUID modelId) {
    jdbc.update(
        "insert into as_assistant (id, name, type, model_id, owner_id, created_at, updated_at)"
            + " values (gen_random_uuid(), ?, 'SIMPLE', ?, gen_random_uuid(), now(), now())",
        name,
        modelId);
  }

  /** 种一个知识库（{@code embedding_model_id} 是 not null，且<strong>没有外键</strong> —— 这正是悬空的入口）。 */
  private UUID insertKb(String name, UUID embeddingModelId) {
    return jdbc.queryForObject(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, created_at, updated_at) values (gen_random_uuid(), ?,"
            + " '通用', 'LOCAL', ?, 'PUBLIC', gen_random_uuid(), now(), now()) returning id",
        UUID.class,
        name,
        embeddingModelId);
  }

  /** 种一篇文档（{@code scope_kind = 'KB'} 时 {@code kb_id} 必须等于 {@code scope_id}）。 */
  private void insertDoc(UUID kbId, String name) {
    jdbc.update(
        "insert into kb_document (id, kb_id, scope_kind, scope_id, name, doc_type, classification,"
            + " status, created_at, updated_at) values (gen_random_uuid(), ?, 'KB', ?, ?, 'TXT',"
            + " 'PUBLIC', 'SUCCEEDED', now(), now())",
        kbId,
        kbId,
        name);
  }

  // ────────────────────────── HTTP ──────────────────────────

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send("GET", path, null, bearer);
  }

  private HttpResponse<String> delete(String path, String bearer) throws Exception {
    return send("DELETE", path, null, bearer);
  }

  private long count(String sql, Object... args) {
    Long found = jdbc.queryForObject(sql, Long.class, args);
    return found == null ? 0L : found;
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30));
    if ("GET".equals(method)) {
      builder.GET();
    } else if (body == null || body.isEmpty()) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** 从包络里取字符串字段（只够读 {@code accessToken}）。 */
  private static String stringField(String json, String key) {
    if (json == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }
}
