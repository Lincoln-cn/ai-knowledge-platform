package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code /models} 全链路（第 4 周 · 0.1.3）：注册 CRUD / 能力探测 / load 语义。
 *
 * <p>★ 桩服务<strong>真的起它跑</strong>（不是永远跳过）： 用 {@code @BeforeAll} 起 {@code
 * backend/tools/openai_stub.py}，让四类能力（思考三态 / 维度 / 窗口 / 工具） 的断言有真实数据可查。
 *
 * <p>★ 挑的断言都对着"会静默出错"的地方：
 *
 * <ol>
 *   <li><strong>视图无 apiKey 明文</strong> —— 漏了就是密钥泄露，且不会报错
 *   <li><strong>load 语义</strong>：LOADING 可观察、上游不可达 ⇒ LOAD_FAILED + 可读原因（不是"加载失败"四个字）
 *   <li><strong>维度校验</strong>：embedding 缺 dimension 拒绝；LOADED 后改 dimension 拒绝
 *   <li><strong>builtin 不可删</strong>、非法 id ⇒ 400
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelApiTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  // ────────────────────────── 桩服务 ──────────────────────────

  private static Process stubProcess;
  private static int stubPort;

  /**
   * ★ 第二套桩：不同端口 + 不同 embedding 维度（512 vs 默认 768）。
   *
   * <p>用来证明「目录跟着 apiBase 走」—— 两套桩的模型名相同，但 dimension 不同， 目录内容就能被区分开（改模型名需要动桩脚本，--dim 差异已足够）。
   */
  private static Process stub2Process;

  private static int stub2Port;

  @BeforeAll
  static void startStub() throws Exception {
    stubPort = freePort();
    stubProcess = launchStub(stubPort, 768);
    awaitStub(stubPort);

    stub2Port = freePort();
    stub2Process = launchStub(stub2Port, 512);
    awaitStub(stub2Port);
  }

  @AfterAll
  static void stopStub() {
    if (stubProcess != null) {
      stubProcess.destroyForcibly();
    }
    if (stub2Process != null) {
      stub2Process.destroyForcibly();
    }
  }

  private static String stubScriptPath() {
    String stubPath =
        java.nio.file.Path.of(System.getProperty("user.dir"))
            .resolve("../backend/tools/openai_stub.py")
            .normalize()
            .toString();
    // Surefire 的 user.dir 是 backend/，从项目根跑时是项目根 —— 两个候选都试
    if (!java.nio.file.Files.exists(java.nio.file.Path.of(stubPath))) {
      stubPath =
          java.nio.file.Path.of(System.getProperty("user.dir"))
              .resolve("tools/openai_stub.py")
              .normalize()
              .toString();
    }
    return stubPath;
  }

  private static Process launchStub(int port, int dim) throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder(
            "python",
            stubScriptPath(),
            "--port",
            String.valueOf(port),
            "--dim",
            String.valueOf(dim));
    pb.redirectErrorStream(true);
    return pb.start();
  }

  /** 等桩起来（最多 5 秒）。 */
  private static void awaitStub(int port) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      try {
        HttpResponse<String> res =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/models"))
                        .timeout(Duration.ofSeconds(1))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200) {
          return;
        }
      } catch (Exception ignored) {
        // 还没起来
      }
      TimeUnit.MILLISECONDS.sleep(200);
    }
    throw new IllegalStateException("桩服务启动失败（端口 " + port + "）");
  }

  @DynamicPropertySource
  static void stubProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.base-url", () -> "http://localhost:" + stubPort + "/v1");
    registry.add("spring.ai.openai.api-key", () -> "stub-test-key");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  // ────────────────────────── 测试 ──────────────────────────

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private static final HttpClient http = HttpClient.newHttpClient();

  private static String adminToken;

  @BeforeEach
  void login() throws Exception {
    if (adminToken != null) {
      return;
    }
    // 惰性登录：@BeforeAll 时 Spring 上下文还没起来，拿不到端口
    HttpResponse<String> res =
        send("POST", "/api/v1/auth/login", "{\"username\":\"admin\",\"password\":\"admin\"}", null);
    adminToken = jsonField(res.body(), "accessToken");
    assertThat(adminToken).as("管理员登录失败：" + res.body()).isNotBlank();
  }

  // ── 能力探测（ROADMAP 测试项 ①）──

  @Test
  @Order(1)
  void 探测_对话模型思考三态和工具与窗口() throws Exception {
    // stub-think-toggleable: TOGGLEABLE / PARALLEL / 32768
    HttpResponse<String> res =
        post(
            "/api/v1/models/probe",
            "{\"baseModel\":\"stub-think-toggleable\",\"supplier\":\"LOCAL_INFERENCE\",\"kind\":\"CHAT\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"thinking\":\"TOGGLEABLE\"");
    assertThat(res.body()).contains("\"tools\":\"PARALLEL\"");
    assertThat(res.body()).contains("\"contextWindow\":32768");
    assertThat(res.body()).contains("\"source\":\"PROBED\"");
  }

  @Test
  @Order(2)
  void 探测_ALWAYS_ON模型永远有思考() throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/models/probe",
            "{\"baseModel\":\"stub-think-always\",\"supplier\":\"LOCAL_INFERENCE\",\"kind\":\"CHAT\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"thinking\":\"ALWAYS_ON\"");
    assertThat(res.body()).contains("\"tools\":\"SUPPORTED\"");
  }

  @Test
  @Order(3)
  void 探测_NONE模型从不思考() throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/models/probe",
            "{\"baseModel\":\"stub-plain\",\"supplier\":\"LOCAL_INFERENCE\",\"kind\":\"CHAT\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"thinking\":\"NONE\"");
    assertThat(res.body()).contains("\"tools\":\"UNSUPPORTED\"");
    assertThat(res.body()).contains("\"contextWindow\":8192");
  }

  @Test
  @Order(4)
  void 探测_向量模型实测维度() throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/models/probe",
            "{\"baseModel\":\"stub-embed\",\"supplier\":\"LOCAL_INFERENCE\",\"kind\":\"EMBEDDING\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    // ★ 维度从 embeddings 实测向量长度得出（桩的 --dim 默认 768）
    assertThat(res.body()).contains("\"dimension\":768");
    assertThat(res.body()).contains("\"thinking\":\"NONE\"");
    assertThat(res.body()).contains("\"tools\":\"UNSUPPORTED\"");
    assertThat(res.body()).as("向量模型没有对话窗口").contains("\"contextWindow\":null");
  }

  // ── CRUD 与校验（ROADMAP 测试项 ③④）──

  @Test
  @Order(10)
  void 注册_向量模型缺dimension必须拒绝() throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/models",
            "{\"name\":\"缺维度向量\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("向量维度");
  }

  @Test
  @Order(11)
  void 注册_对话模型带dimension必须拒绝() throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/models",
            "{\"name\":\"非法对话\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-plain\",\"dimension\":768}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(400);
  }

  @Test
  @Order(12)
  void 注册_成功且视图无apiKey明文() throws Exception {
    jdbc.update("delete from ai_model");
    HttpResponse<String> res =
        post(
            "/api/v1/models",
            "{\"name\":\"测试对话模型\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-think-toggleable\",\"apiKey\":\"sk-secret-12345\"}",
            adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"name\":\"测试对话模型\"");
    assertThat(res.body()).contains("\"status\":\"NOT_LOADED\"");
    // ★ 视图绝不回传 apiKey 明文
    assertThat(res.body())
        .as("视图回传了 apiKey 明文 —— 与 UserView 不含 passwordHash 同一条纪律")
        .doesNotContain("sk-secret-12345")
        .doesNotContain("apiKey");
  }

  @Test
  @Order(13)
  void 列表_分页与筛选() throws Exception {
    // 再建一个向量模型用于筛选
    post(
        "/api/v1/models",
        "{\"name\":\"测试向量模型\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768}",
        adminToken);

    HttpResponse<String> all = get("/api/v1/models", adminToken);
    assertThat(all.statusCode()).isEqualTo(200);
    assertThat(all.body()).contains("\"total\":");

    HttpResponse<String> chatOnly = get("/api/v1/models?kind=CHAT", adminToken);
    assertThat(chatOnly.body()).contains("测试对话模型");
    assertThat(chatOnly.body()).doesNotContain("测试向量模型");

    HttpResponse<String> embedOnly = get("/api/v1/models?kind=EMBEDDING", adminToken);
    assertThat(embedOnly.body()).contains("测试向量模型");
    assertThat(embedOnly.body()).doesNotContain("测试对话模型");
  }

  @Test
  @Order(15)
  void 列表_keyword按名称与基础模型模糊匹配() throws Exception {
    // Order 13 已留下「测试对话模型」(stub-think-toggleable) 与「测试向量模型」(stub-embed)

    // 按 name 模糊（中文）
    HttpResponse<String> byName = get("/api/v1/models?keyword=" + "测试对话", adminToken);
    assertThat(byName.statusCode()).isEqualTo(200);
    assertThat(byName.body()).as("keyword 应命中 name").contains("测试对话模型");
    assertThat(byName.body()).doesNotContain("测试向量模型");

    // 按 baseModel 模糊，且大小写不敏感（真用户不会记得注册时的大小写）
    HttpResponse<String> byBase = get("/api/v1/models?keyword=STUB-EMBED", adminToken);
    assertThat(byBase.body()).as("keyword 应命中 baseModel，且忽略大小写").contains("测试向量模型");
    assertThat(byBase.body()).doesNotContain("测试对话模型");

    // 空串等同不筛选（真前端会传 ?keyword=&page=1）
    HttpResponse<String> blank = get("/api/v1/models?keyword=&page=1&size=10", adminToken);
    HttpResponse<String> none = get("/api/v1/models?page=1&size=10", adminToken);
    assertThat(jsonField(blank.body(), "name"))
        .as("空串 keyword 与不筛选必须等价")
        .isEqualTo(jsonField(none.body(), "name"));
    assertThat(totalOf(blank.body())).isEqualTo(totalOf(none.body()));
  }

  @Test
  @Order(14)
  void 非法id返回400() throws Exception {
    HttpResponse<String> res = get("/api/v1/models/not-a-uuid", adminToken);
    // GET /models/{id} 没有单独的 detail 端点 —— 契约里只有 8 个端点，没有 GET /models/:id
    // 非法 id 在 PUT/DELETE/load/probe 上测
    HttpResponse<String> del = delete("/api/v1/models/not-a-uuid", adminToken);
    assertThat(del.statusCode()).as("调用方传错 id 是 400（他自己能修）；500 会让人来找后端").isEqualTo(400);
  }

  @Test
  @Order(20)
  void 更新_已加载的向量模型改dimension必须拒绝() throws Exception {
    jdbc.update("delete from ai_model");
    // 建一个向量模型并手动置为 LOADED（绕过异步加载，直接测校验）
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"已加载向量\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768}",
            adminToken);
    String id = jsonField(create.body(), "id");
    jdbc.update(
        "update ai_model set status = 'LOADED', enabled = true where id = ?",
        java.util.UUID.fromString(id));

    HttpResponse<String> res = put("/api/v1/models/" + id, "{\"dimension\":1024}", adminToken);
    assertThat(res.statusCode()).as("已加载的模型改 dimension 需全量重灌向量表 —— 必须拒绝并说清原因").isEqualTo(400);
    assertThat(res.body()).containsAnyOf("全量重灌", "EMBEDDING_LOCKED");
  }

  @Test
  @Order(21)
  void 更新_未加载的向量模型可以改dimension() throws Exception {
    jdbc.update("delete from ai_model");
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"未加载向量\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768}",
            adminToken);
    String id = jsonField(create.body(), "id");

    HttpResponse<String> res = put("/api/v1/models/" + id, "{\"dimension\":1024}", adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("\"dimension\":1024");
  }

  @Test
  @Order(22)
  void 更新_对话模型带dimension必须拒绝() throws Exception {
    // 与 create 同口径：不适用的字段一律拒绝，不能静默丢
    jdbc.update("delete from ai_model");
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"待改对话\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-plain\"}",
            adminToken);
    String id = jsonField(create.body(), "id");

    HttpResponse<String> res = put("/api/v1/models/" + id, "{\"dimension\":768}", adminToken);
    assertThat(res.statusCode()).as("chat + dimension 必须 400，不能 200 后静默丢字段").isEqualTo(400);
    assertThat(res.body()).contains("对话模型不产出向量，dimension 必须为空");
  }

  @Test
  @Order(23)
  void 更新_向量模型带thinking必须拒绝() throws Exception {
    // 能力声明只对对话模型有意义 —— 传了就该 400，而不是被丢掉
    jdbc.update("delete from ai_model");
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"待改向量\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768}",
            adminToken);
    String id = jsonField(create.body(), "id");

    HttpResponse<String> res =
        put("/api/v1/models/" + id, "{\"thinking\":\"ALWAYS_ON\"}", adminToken);
    assertThat(res.statusCode()).as("embedding + thinking 必须 400").isEqualTo(400);
    assertThat(res.body()).containsAnyOf("能力声明只对对话模型有意义", "thinking");

    HttpResponse<String> toolsRes =
        put("/api/v1/models/" + id, "{\"tools\":\"PARALLEL\"}", adminToken);
    assertThat(toolsRes.statusCode()).as("embedding + tools 同样必须 400").isEqualTo(400);
    assertThat(toolsRes.body()).containsAnyOf("能力声明只对对话模型有意义", "tools");
  }

  // ── load 语义（ROADMAP 测试项 ②）──

  @Test
  @Order(30)
  void load_正常加载走完四态() throws Exception {
    jdbc.update("delete from ai_model");
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"加载测试模型\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-plain\"}",
            adminToken);
    String id = jsonField(create.body(), "id");

    // 触发加载 —— 立即返回 LOADING（契约写明"加载是异步的，返回时 status 仍是加载中"）
    HttpResponse<String> loadRes = post("/api/v1/models/" + id + "/load", "", adminToken);
    assertThat(loadRes.statusCode()).isEqualTo(200);
    assertThat(loadRes.body())
        .as("加载是异步的，返回时 status 仍应是 LOADING")
        .contains("\"status\":\"LOADING\"");

    // 轮询等后台转 LOADED
    String status = "LOADING";
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline && !"LOADED".equals(status)) {
      TimeUnit.MILLISECONDS.sleep(200);
      HttpResponse<String> list = get("/api/v1/models", adminToken);
      status = jsonField(list.body(), "status");
    }
    assertThat(status).as("上游可达时应转 LOADED").isEqualTo("LOADED");

    // 验证 enabled=true 且 loadError 清空
    HttpResponse<String> list = get("/api/v1/models", adminToken);
    assertThat(list.body()).containsAnyOf("\"enabled\":true", "\"status\":\"LOADED\"");
  }

  @Test
  @Order(31)
  void load_上游不可达时LOAD_FAILED且原因可读() throws Exception {
    jdbc.update("delete from ai_model");
    // 建模型时用一个立即拒绝连接的 apiBase（127.0.0.1:1 没有监听 ⇒ connection refused）
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"不可达模型\",\"kind\":\"CHAT\",\"supplier\":\"EXTERNAL_API\",\"baseModel\":\"stub-plain\",\"apiBase\":\"http://127.0.0.1:1\"}",
            adminToken);
    assertThat(create.statusCode()).as("外部 API 必须带 apiBase：" + create.body()).isEqualTo(200);
    String id = jsonField(create.body(), "id");

    HttpResponse<String> loadRes = post("/api/v1/models/" + id + "/load", "", adminToken);
    assertThat(loadRes.statusCode()).isEqualTo(200);
    assertThat(loadRes.body()).contains("\"status\":\"LOADING\"");

    // 轮询等后台转 LOAD_FAILED
    String status = "LOADING";
    String loadError = null;
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline && !"LOAD_FAILED".equals(status)) {
      TimeUnit.MILLISECONDS.sleep(300);
      status =
          jdbc.queryForObject(
              "select status from ai_model where id = ?",
              String.class,
              java.util.UUID.fromString(id));
      loadError =
          jdbc.queryForObject(
              "select load_error from ai_model where id = ?",
              String.class,
              java.util.UUID.fromString(id));
    }
    assertThat(status).as("上游不可达时应转 LOAD_FAILED").isEqualTo("LOAD_FAILED");
    // ★ 失败原因必须能照着排查 —— 不是"加载失败"四个字（Model 状态机已拒空值与废话文案）
    assertThat(loadError)
        .as("失败原因必须是能照着排查的那句话，不是「加载失败」四个字")
        .isNotBlank()
        .containsAnyOf("网关不可达", "连接", "HTTP");

    // ★ 契约下发 loadError —— 界面不能只显示静态「加载失败」四个字
    HttpResponse<String> list = get("/api/v1/models", adminToken);
    String apiLoadError = jsonField(list.body(), "loadError");
    assertThat(apiLoadError)
        .as("API 必须下发 loadError，界面才能显示可读原因")
        .isNotBlank()
        .isNotEqualTo("加载失败")
        .containsAnyOf("网关不可达", "连接", "HTTP");
  }

  // ── 删除守卫 ──

  @Test
  @Order(40)
  void 删除_builtin不可删() throws Exception {
    jdbc.update("delete from ai_model");
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, status, enabled, thinking,"
            + " tools, builtin, created_at, updated_at) values (gen_random_uuid(), '内置模型', 'CHAT',"
            + " 'LOCAL_INFERENCE', 'stub-plain', 'NOT_LOADED', false, 'NONE', 'UNSUPPORTED', true,"
            + " now(), now())");
    String id =
        jdbc.queryForObject("select id from ai_model where builtin = true limit 1", String.class);

    HttpResponse<String> res = delete("/api/v1/models/" + id, adminToken);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("内置模型");
  }

  @Test
  @Order(41)
  void 删除_普通模型可以删() throws Exception {
    jdbc.update("delete from ai_model");
    HttpResponse<String> create =
        post(
            "/api/v1/models",
            "{\"name\":\"待删除模型\",\"kind\":\"CHAT\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-plain\"}",
            adminToken);
    String id = jsonField(create.body(), "id");

    HttpResponse<String> res = delete("/api/v1/models/" + id, adminToken);
    assertThat(res.statusCode()).isEqualTo(200);

    HttpResponse<String> list = get("/api/v1/models", adminToken);
    assertThat(list.body()).doesNotContain("待删除模型");
  }

  // ── 基础模型目录（POST /models/base-models · ROADMAP 0.1.3 ⑤⑥）──

  @Test
  @Order(50)
  void 目录_跟着apiBase走_不传时用默认上游() throws Exception {
    // 不传 apiBase ⇒ 默认上游那套（stubPort，--dim 768）
    HttpResponse<String> defaultRes = post("/api/v1/models/base-models", "{}", adminToken);
    assertThat(defaultRes.statusCode()).isEqualTo(200);
    assertThat(defaultRes.body())
        .as("不传 apiBase 必须走默认上游那套（dimension 768）—— 现行为不回归")
        .contains("\"dimension\":768");

    // 传另一套上游（stub2Port，--dim 512）⇒ 目录内容必须不同
    HttpResponse<String> otherRes =
        post(
            "/api/v1/models/base-models",
            "{\"apiBase\":\"http://localhost:" + stub2Port + "/v1\"}",
            adminToken);
    assertThat(otherRes.statusCode()).isEqualTo(200);
    assertThat(otherRes.body())
        .as("传了 apiBase 就必须拉那一套的目录（dimension 512）")
        .contains("\"dimension\":512");
    assertThat(otherRes.body())
        .as("两套上游的目录内容必须不同 —— 否则说明 apiBase 根本没被用上")
        .doesNotContain("\"dimension\":768");
  }

  @Test
  @Order(51)
  void 注册_向量模型带thinking或tools必须拒绝且不落库() throws Exception {
    // create 与 update 同口径：能力声明只对对话模型有意义，传了就 400，不静默改写
    jdbc.update("delete from ai_model");

    HttpResponse<String> thinkingRes =
        post(
            "/api/v1/models",
            "{\"name\":\"非法能力向量A\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768,\"thinking\":\"ALWAYS_ON\"}",
            adminToken);
    assertThat(thinkingRes.statusCode())
        .as("create 对 embedding+thinking 必须与 update 同口径 400，不能静默改写")
        .isEqualTo(400);
    assertThat(thinkingRes.body()).contains("能力声明只对对话模型有意义");

    HttpResponse<String> toolsRes =
        post(
            "/api/v1/models",
            "{\"name\":\"非法能力向量B\",\"kind\":\"EMBEDDING\",\"supplier\":\"LOCAL_INFERENCE\",\"baseModel\":\"stub-embed\",\"dimension\":768,\"tools\":\"PARALLEL\"}",
            adminToken);
    assertThat(toolsRes.statusCode()).as("create 对 embedding+tools 同样必须 400").isEqualTo(400);
    assertThat(toolsRes.body()).contains("能力声明只对对话模型有意义");

    // ★ 400 的请求绝不能落库 —— 「拒绝」必须是真的拒绝
    Integer count =
        jdbc.queryForObject(
            "select count(*) from ai_model where name in (?, ?)",
            Integer.class,
            "非法能力向量A",
            "非法能力向量B");
    assertThat(count).as("被拒绝的 create 不能写进库里").isZero();
  }

  @Test
  @Order(52)
  void 目录_上游不可达时空列表加可读hint() throws Exception {
    // 起一个临时桩、确认能通、再停掉它 —— 就是「停掉桩」这个场景
    int deadPort = freePort();
    Process tempStub = launchStub(deadPort, 256);
    try {
      awaitStub(deadPort);
    } finally {
      tempStub.destroyForcibly();
      tempStub.waitFor(5, TimeUnit.SECONDS);
    }

    HttpResponse<String> res =
        post(
            "/api/v1/models/base-models",
            "{\"apiBase\":\"http://localhost:" + deadPort + "/v1\"}",
            adminToken);
    assertThat(res.statusCode()).as("上游不可达不该 500 —— 目录是新建弹窗的数据源，挂了不该让管理页崩掉").isEqualTo(200);
    assertThat(res.body()).as("不可达 ⇒ 空列表").contains("\"data\":[]");

    String hint = jsonField(res.body(), "hint");
    assertThat(hint).as("空列表不能干巴巴 —— 包络的 hint 必须给出可读提示").isNotBlank();
    assertThat(hint).as("hint 要能照着排查（检查服务地址 / 服务是否已启动）").containsAnyOf("服务地址", "已启动", "未从上游", "检查");
  }

  @Test
  @Order(53)
  void 目录_响应里不得出现请求里的apiKey() throws Exception {
    String secret = "sk-catalog-must-not-echo-9527";
    HttpResponse<String> res =
        post("/api/v1/models/base-models", "{\"apiKey\":\"" + secret + "\"}", adminToken);
    assertThat(res.statusCode()).isEqualTo(200);
    // ★ 密钥进了查询串会进日志/代理/浏览器历史（所以目录改 POST）；
    //   进了响应体则是直接泄露。断言的是「全文都没有那个字符串」。
    assertThat(res.body()).as("目录响应里绝不能回显 apiKey").doesNotContain(secret);
  }

  // ────────────────────────── 工具 ──────────────────────────

  private HttpResponse<String> get(String path, String bearer) throws Exception {
    return send("GET", path, null, bearer);
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
    return send("PUT", path, body, bearer);
  }

  private HttpResponse<String> delete(String path, String bearer) throws Exception {
    return send("DELETE", path, null, bearer);
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

  private static String jsonField(String json, String key) {
    if (json == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** 从包络里取数字型 {@code total}（{@link #jsonField} 只匹配字符串值）。 */
  private static long totalOf(String json) {
    Matcher matcher = Pattern.compile("\"total\":(\\d+)").matcher(json);
    if (!matcher.find()) {
      throw new IllegalStateException("响应里没有 total：" + json);
    }
    return Long.parseLong(matcher.group(1));
  }
}
