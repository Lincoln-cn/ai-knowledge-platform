package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.support.PostgresImage;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

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

  // ────────────────────────── 库级进度 / 重试 / 重新向量化 ──────────────────────────

  /**
   * ★ 库级进度必须与库里的状态一致，且全部完成后 {@code running=false}（前端据此停止轮询）。
   *
   * <p>★ 「等用户点开始向量化」的 {@code PENDING_EMBEDDING} <strong>不算在跑</strong> ——
   * 算进去的话前端会一直轮询一个不会变化的数字（{@code 03 §3.4}：全部完成即停）。
   */
  @Test
  void 库级进度与库里的状态一致() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");

    JsonNode before = progress(admin, kbId);
    assertThat(before.get("total").asInt()).isEqualTo(1);
    assertThat(before.get("done").asInt()).isZero();
    assertThat(before.get("stages").toString()).contains("PENDING_EMBEDDING");
    assertThat(before.get("running").asBoolean()).as("等用户点开始向量化不算「在跑」").isFalse();

    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminalStatus(docId, 60)).isEqualTo("SUCCEEDED");

    JsonNode after = progress(admin, kbId);
    assertThat(after.get("done").asInt()).as("已完成数来自库里的状态").isEqualTo(1);
    assertThat(after.get("stages").toString()).contains("SUCCEEDED");
    assertThat(after.get("running").asBoolean()).as("全部完成 ⇒ 前端该停轮询").isFalse();
    assertThat(after.get("failed").asInt()).isZero();
  }

  /** ★ 重新向量化：分段要退回待向量化并真的重算（否则「一个都不做」却显示在跑）。 */
  @Test
  void 重新向量化会把分段退回待向量化并重算() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");

    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminalStatus(docId, 60)).isEqualTo("SUCCEEDED");
    int segments = segmentCount(docId);

    HttpResponse<String> res = post("/api/v1/documents/" + docId + "/reembed", null, admin);
    assertThat(res.statusCode()).as("重新向量化：" + res.body()).isEqualTo(200);

    assertThat(waitForTerminalStatus(docId, 60)).isEqualTo("SUCCEEDED");
    assertThat(processed(docId)).as("重算后进度必须再次走满").isEqualTo(segments);
    assertThat(vectorCount(docId)).as("向量条数不该翻倍（幂等 upsert）").isEqualTo(segments);
  }

  /**
   * ★ 失败文档的重试按<strong>阶段</strong>分流（{@code 03 §3.5}）： 分段失败需要原始文件 ⇒
   * 如实拒绝并说清下一步，而不是假装重试（那会让用户反复点、状态不变）。
   */
  @Test
  void 分段失败的文档重试如实拒绝() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    // 空文档 ⇒ 「0 字符 ≠ 成功」（0.2.1 的 DOC-I2）⇒ SEGMENT_FAILED
    String badDoc = uploadDocx(admin, kbId, "empty.docx");
    assertThat(waitForTerminalStatus(badDoc, 30)).isEqualTo("SEGMENT_FAILED");

    HttpResponse<String> res = post("/api/v1/documents/" + badDoc + "/retry", null, admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).as("要说清「为什么不给重试」以及「下一步做什么」").contains("原始文件").contains("重新上传");
  }

  /** 已完成的文档重试 ⇒ 明确拒绝（不是静默重跑一遍）。 */
  @Test
  void 已完成的文档重试被拒() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");
    post("/api/v1/documents/" + docId + "/embedding", null, admin);
    assertThat(waitForTerminalStatus(docId, 60)).isEqualTo("SUCCEEDED");

    HttpResponse<String> res = post("/api/v1/documents/" + docId + "/retry", null, admin);
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("无需重试");
  }

  // ────────────────────────── 主链路 ──────────────────────────

  /** ★ 上传真实 DOCX → 分块 → 开始向量化 → 库里看到「全部完成 + 每段都有向量」。 */
  @Test
  void 开始向量化后库里能看到完成的进度与向量() throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    String docId = uploadDocx(admin, kbId, "structured.docx");

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
    String docId = uploadDocx(admin, kbId, "structured.docx");
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
    String docId = uploadDocx(admin, kbId, "structured.docx");

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
  private String uploadDocx(String token, String kbId, String fixture) throws Exception {
    byte[] bytes;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/" + fixture)) {
      assertThat(in).as("夹具不在 classpath").isNotNull();
      bytes = in.readAllBytes();
    }
    String boundary = "----pipe0boundary";
    var buf = new java.io.ByteArrayOutputStream();
    for (String[] field :
        new String[][] {
          {"docType", "制度"},
          // ★ 责任部门已改成 org_id（UUID 外键，V27）—— 仍在传 department 会让建档 400，而本类的
          //   7 条用例**全部**挂在同一个前置上（CI 实测 `Tests run: 7, Failures: 7`）。
          //   这里用 ROOT 的**确定性** id（V25 种子写死）：org_id 是外键，不能塞随机值。
          {"orgId", "00000000-0000-4000-8000-000000000001"},
          {"classification", "INTERNAL"}
        }) {
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
              .formatted(boundary, field[0], field[1])
              .getBytes(StandardCharsets.UTF_8));
    }
    buf.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .formatted(boundary, fixture)
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
    String docId = m.group(1);
    // ★ 第 9 周起解析是**异步**的（扫描件的 OCR 实测每页约 3.6 秒 —— 判据 ⑤ 要求不阻塞其它文档）
    //   ⇒ "上传完就能读分段"不再成立。测试要和界面一样**等**解析落地。
    waitParsed(docId, 30);
    return docId;
  }

  /** 等解析落地：{@code PENDING_EMBEDDING}（成功）或 {@code SEGMENT_FAILED}（失败）。 */
  private void waitParsed(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String current =
          jdbc.queryForObject(
              "select status from kb_document where id = ?::uuid", String.class, docId);
      if ("PENDING_EMBEDDING".equals(current) || "SEGMENT_FAILED".equals(current)) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
    return send("POST", path, body, bearer);
  }

  /** 读库级进度（契约 {@code getIngestionProgress}）并把 {@code data} 取出来。 */
  private JsonNode progress(String token, String kbId) throws Exception {
    HttpResponse<String> res =
        send("GET", "/api/v1/knowledge-bases/" + kbId + "/ingestion-progress", null, token);
    assertThat(res.statusCode()).as("读进度失败：" + res.body()).isEqualTo(200);
    return new ObjectMapper().readTree(res.body()).get("data");
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
