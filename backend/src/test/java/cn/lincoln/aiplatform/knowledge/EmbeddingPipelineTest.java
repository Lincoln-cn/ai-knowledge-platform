package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
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
 * 向量化链路端到端（第 8 周 · 0.3.0 · 演示判据）。
 *
 * <p>演示口径是「上传 → 六态走完；进度 0/340 → 340/340；★ 刷新页面不重置」。本测试盯的正是这三件事：
 *
 * <ol>
 *   <li><strong>真的走完了</strong>：{@code PENDING_EMBEDDING → EMBEDDING → SUCCEEDED}，且 {@code
 *       processedSegments == segmentCount}
 *   <li>★ <strong>进度是库里的数字</strong>（"刷新不重置"的唯一实现方式）：断言全部从<strong>数据库</strong>读， 不读接口返回的瞬时值
 *   <li>★ <strong>向量与分段正确对应</strong>：把某一段的 {@code search_text} 单独向量化， 必须等于库里该段那一行向量 ——
 *       这条能同时抓住"写错行"和"顺序错配"（错配不报错， 只会让检索返回别的内容的邻居）
 * </ol>
 *
 * <p>需要桩服务（{@code python backend/tools/openai_stub.py}），不在就跳过，不假绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class EmbeddingPipelineTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  /** 桩服务的 OpenAI 兼容入口（base 末尾带 /v1：网关会拼 /embeddings）。 */
  private static final String STUB_API_BASE =
      System.getenv().getOrDefault("AI_STUB_ROOT", "http://localhost:11434") + "/v1";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired EmbeddingPort embeddingPort;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void requireStub() {
    assumeThat(reachable()).as("桩服务不在 " + STUB_API_BASE + " ⇒ 跳过").isTrue();
  }

  // ────────────────────────── 主链路 ──────────────────────────

  /** ★ 上传真实 DOCX → 分块 → 开始向量化 → 库里看到「全部完成 + 每段都有向量」。 */
  @Test
  void 开始向量化后库里能看到完成的进度与向量() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId);

    int segmentCount = segmentCount(docId);
    assertThat(segmentCount).as("夹具前提：文档必须切出多段").isGreaterThan(1);

    // ── 触发（契约 startEmbedding：用户显式动作） ──
    HttpResponse<String> started = post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(started.statusCode()).as("开始向量化：" + started.body()).isEqualTo(200);

    // ── 等它跑完（后台分批跑，前端也是这样轮询的） ──
    String status = waitForTerminalStatus(docId, 60);

    assertThat(status).as("六态必须走到终点（失败原因：" + failReason(docId) + "）").isEqualTo("SUCCEEDED");
    assertThat(processed(docId)).as("★ 进度必须是库里的事实：全部段都进完向量").isEqualTo(segmentCount);
    assertThat(vectorCount(docId)).as("每段一行向量（id = segmentId）").isEqualTo(segmentCount);
    assertThat(segmentEmbeddingStatuses(docId))
        .as("每段的状态都要是 EMBEDDED（它是「哪些还要重试」的唯一依据）")
        .containsOnly("EMBEDDED");
  }

  /** ★ 向量与分段必须正确对应：某段 {@code search_text} 单独向量化的结果 == 库里那一行的向量。 */
  @Test
  void 写入的向量必须精确对应那一段文本() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId);
    String modelId = modelIdOf(kbId);

    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminalStatus(docId, 60))
        .as("失败原因：" + failReason(docId))
        .isEqualTo("SUCCEEDED");

    // 取第二段：单独向量化它的 search_text（含结构化前缀 ⇒ 与入库时用的输入完全一致）
    UUID segmentId = UUID.fromString(segmentIds(docId).get(1));
    String searchText = searchTextOf(segmentId);
    float[] expected = embeddingPort.embed(UUID.fromString(modelId), List.of(searchText)).get(0);

    assertThat(storedVector(segmentId))
        .as("库里的向量必须等于「这段文本」的向量 —— 写错行或顺序错配都会在这里露出来")
        .isEqualTo(expected);
  }

  /** ★ 已在跑或已完成时再点一次 ⇒ 可读拒绝（状态机守卫），不是静默重跑。 */
  @Test
  void 重复点击开始向量化被可读拒绝() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId);

    assertThat(post("/api/v1/documents/" + docId + "/embedding", null, admin).statusCode())
        .isEqualTo(200);
    assertThat(waitForTerminalStatus(docId, 60))
        .as("失败原因：" + failReason(docId))
        .isEqualTo("SUCCEEDED");

    HttpResponse<String> again = post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(again.statusCode()).as("已完成的文档不能再开始向量化").isEqualTo(400);
    assertThat(again.body()).as("拒绝原因要能照着改（说明当前状态）").contains("SUCCEEDED");
  }

  // ────────────────────────── 夹具与工具 ──────────────────────────

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
    assertThat(m.find()).as("登录失败：" + res.body()).isTrue();
    return m.group(1);
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"向量化闸门向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"%s","dimension":768}\
        """
            .formatted(shortId(), STUB_API_BASE);
    HttpResponse<String> modelRes = send("POST", "/api/v1/models", model, token);
    Matcher m = ID.matcher(modelRes.body());
    assertThat(m.find()).as("建模型失败：" + modelRes.body()).isTrue();
    String modelId = m.group(1);

    String kb =
        """
        {"name":"向量化闸门库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(shortId(), modelId);
    HttpResponse<String> kbRes = send("POST", "/api/v1/knowledge-bases", kb, token);
    Matcher kbMatcher = ID.matcher(kbRes.body());
    assertThat(kbMatcher.find()).as("建库失败：" + kbRes.body()).isTrue();
    return kbMatcher.group(1);
  }

  /** 上传真实 DOCX（multipart）—— 建档即解析 + 入库即分块。 */
  private String uploadDocx(String token, String kbId) throws Exception {
    byte[] bytes;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/structured.docx")) {
      assertThat(in).as("夹具不在 classpath").isNotNull();
      bytes = in.readAllBytes();
    }
    String boundary = "----pipe0boundary";
    var buf = new java.io.ByteArrayOutputStream();
    for (String[] field :
        new String[][] {{"docType", "制度"}, {"department", "采购部"}, {"classification", "INTERNAL"}}) {
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
              .formatted(boundary, field[0], field[1])
              .getBytes(StandardCharsets.UTF_8));
    }
    buf.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"structured.docx\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .formatted(boundary)
            .getBytes(StandardCharsets.UTF_8));
    buf.write(bytes);
    buf.write("\r\n".getBytes(StandardCharsets.UTF_8));
    buf.write(("--%s--\r\n").formatted(boundary).getBytes(StandardCharsets.UTF_8));

    HttpResponse<String> res =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/knowledge-bases/"
                            + kbId
                            + "/documents"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    Matcher m = ID.matcher(res.body());
    assertThat(m.find()).as("上传失败：" + res.body()).isTrue();
    return m.group(1);
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(30));
      if (body == null) {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      }
      if (bearer != null) {
        builder.header("Authorization", "Bearer " + bearer);
      }
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("请求失败：" + method + " " + path, e);
    }
  }

  // ────────────────────────── 直接读库（"进度是事实"的检验口） ──────────────────────────

  private String waitForTerminalStatus(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String status = status(docId);
      if ("SUCCEEDED".equals(status) || "EMBEDDING_FAILED".equals(status)) {
        return status;
      }
      Thread.sleep(500);
    }
    return status(docId);
  }

  /** 失败时把可读原因带进断言消息 —— 否则测试只告诉你「没到终点」，不告诉你为什么。 */
  private String failReason(String docId) {
    return jdbc.queryForObject(
        "select coalesce(fail_reason, '(无)') from kb_document where id = ?::uuid",
        String.class,
        docId);
  }

  private String status(String docId) {
    return jdbc.queryForObject(
        "select status from kb_document where id = ?::uuid", String.class, docId);
  }

  private int processed(String docId) {
    return jdbc.queryForObject(
        "select processed_segments from kb_document where id = ?::uuid", Integer.class, docId);
  }

  private int segmentCount(String docId) {
    return jdbc.queryForObject(
        "select segment_count from kb_document where id = ?::uuid", Integer.class, docId);
  }

  private long vectorCount(String docId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from vec_embedding_768 where metadata::jsonb ->> 'document_id' = ?",
            Long.class,
            docId);
    return count == null ? 0 : count;
  }

  private List<String> segmentEmbeddingStatuses(String docId) {
    return jdbc.queryForList(
        "select embedding_status from kb_segment where document_id = ?::uuid", String.class, docId);
  }

  private List<String> segmentIds(String docId) {
    return jdbc.queryForList(
        "select id::text from kb_segment where document_id = ?::uuid order by ordinal",
        String.class,
        docId);
  }

  private String searchTextOf(UUID segmentId) {
    return jdbc.queryForObject(
        "select search_text from kb_segment where id = ?", String.class, segmentId);
  }

  private float[] storedVector(UUID segmentId) {
    String literal =
        jdbc.queryForObject(
            "select embedding::text from vec_embedding_768 where id = ?", String.class, segmentId);
    String body = literal.trim().replaceAll("^\\[|\\]$", "");
    String[] parts = body.split(",");
    float[] vector = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      vector[i] = Float.parseFloat(parts[i].trim());
    }
    return vector;
  }

  private String modelIdOf(String kbId) {
    return jdbc.queryForObject(
        "select embedding_model_id::text from kb_knowledge_base where id = ?::uuid",
        String.class,
        kbId);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static boolean reachable() {
    try {
      HttpResponse<String> res =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(2))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(STUB_API_BASE + "/models"))
                      .timeout(Duration.ofSeconds(3))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      return res.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }
}
