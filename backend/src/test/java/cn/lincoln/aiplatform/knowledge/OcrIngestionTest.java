package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
 * OCR 入库链的后端端到端（第 9 周 · 0.3.1）—— 钉住判据 ①③⑤ 与演示物。
 *
 * <h3>★ 这一组盯的是「进程内真实跑一遍」才看得见的东西</h3>
 *
 * <ol>
 *   <li><b>⑤ 异步</b>：上传接口**立刻**返回（文档落在「待解析/分段中」，不是等解析完才返回）
 *   <li><b>逐页进度</b>：扫描件跑的过程中，库里能读到<b>中间值</b> —— 这是"已识别 3/10 页"的地基。 ★ 断言方式不是"最终等于 3"，而是**在轮询中真的看到过 0
 *       &lt; done &lt; total**： 只有那样才能证明进度是**逐步**回写的，而不是结束时一次性写的
 *   <li><b>最终事实</b>：{@code ocr_page_count} = 真的走了 OCR 的页数（分块器据此选规则集，③）
 *   <li><b>文本型不受影响</b>：非扫描件 {@code ocr_total_pages = 0}（界面不显示进度行），且照样能入库
 * </ol>
 *
 * <p>★ 为什么必须跑真栈：OCR 是**系统级依赖**（装了 tesseract 才跑），而且进度是"异步 + 库里的中间值"这件事 —— mock
 * 一个字节都不掺和。本机没装引擎时整类跳过（{@link EnabledIf}），不假绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@EnabledIf("ocrAvailable")
class OcrIngestionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired JdbcTemplate jdbc;
  @Autowired OcrEngine ocrEngine;
  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  /** 本机/CI 有没有可用的 OCR 引擎（没有就整类跳过，不假绿）。 */
  static boolean ocrAvailable() {
    return new OcrEngine(
            new cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties(
                true, null, null, "chi_sim+eng", 300, 120, true))
        .available();
  }

  // ────────────────────────── 1 · 扫描件：异步 + 逐页进度 + 最终事实 ──────────────────────────

  /**
   * ★★ 判据 ⑤ + 演示物：上传扫描件 ⇒ 接口立刻返回 ⇒ 进度**逐步**前进 ⇒ 入库成功。
   *
   * <p>这是本周边界最清楚的一条：它同时证明了"异步"（不阻塞）与"按页回写"（看得见中间值）。
   */
  @Test
  void 扫描件上传是异步的且进度逐步前进() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "扫描件库");

    long submitMs = System.currentTimeMillis();
    String docId = upload(admin, kbId, "scanned.pdf");
    long elapsedMs = System.currentTimeMillis() - submitMs;

    // ① 上传接口必须**立刻**返回：真解析要 10 秒量级，等它就不是异步了
    assertThat(elapsedMs).as("上传接口应立即返回（解析在后台），实测耗时 %d ms", elapsedMs).isLessThan(5000);

    // ② 轮询过程中必须看到**中间进度**：0 < done < total
    List<int[]> snapshots = new ArrayList<>();
    String status = null;
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      Object[] row =
          jdbc.queryForObject(
              "select status, ocr_total_pages, ocr_done_pages, ocr_page_count from kb_document"
                  + " where id = ?::uuid",
              (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)},
              docId);
      status = (String) row[0];
      snapshots.add(new int[] {(Integer) row[1], (Integer) row[2]});
      if ("PENDING_EMBEDDING".equals(status) || "SEGMENT_FAILED".equals(status)) {
        break;
      }
      Thread.sleep(150);
    }

    assertThat(status).as("扫描件最终必须落到「待向量化」而不是失败或卡住").isEqualTo("PENDING_EMBEDDING");

    int total = value(docId, "ocr_total_pages");
    int done = value(docId, "ocr_done_pages");
    int ocrPages = value(docId, "ocr_page_count");
    assertThat(total).as("分母 = 需要 OCR 的页数（3 页扫描件）").isEqualTo(3);
    assertThat(ocrPages).as("最终事实：3 页都走了 OCR").isEqualTo(3);
    assertThat(done).isEqualTo(3);

    boolean sawIntermediate =
        snapshots.stream().anyMatch(s -> s[1] > 0 && s[1] < Math.max(1, s[0]));
    assertThat(sawIntermediate)
        .as("★ 必须看到过「0 < 已完成 < 总数」的中间快照 —— 否则进度可能是结束时一次性写的（假进度）。实测快照：%s", snapshots)
        .isTrue();

    // ③ 入库结果：分段落库 + 正文是**识别出来的中文**（不是空、不是乱码）
    assertThat(value(docId, "segment_count")).as("扫描件必须能分段入库").isGreaterThan(0);
    String parsed = text(docId, "parsed_content");
    assertThat(parsed).as("解析正文不能为空 —— 走了 OCR 就该有内容").isNotBlank();
    assertThat(parsed).as("正文必须是识别出来的中文（夹具原文含「某某集团内部管理制度」）").contains("管理制度");
  }

  /** ★ 判据 ③ 的依据：{@code ocr_page_count > 0} ⇒ 这份文档是 OCR 产物，分块器走 OCR 规则集。 */
  @Test
  void 混合型PDF只把需要OCR的页算进分母() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "混合型库");
    String docId = upload(admin, kbId, "mixed.pdf");
    waitParsed(docId, 120);

    int total = value(docId, "ocr_total_pages");
    int ocrPages = value(docId, "ocr_page_count");
    assertThat(total).as("混合型 PDF：正文 1 页 + 扫描 2 页 ⇒ 分母只算需要 OCR 的那 2 页").isEqualTo(2);
    assertThat(ocrPages).as("分母与最终事实必须一致（否则进度条到不了 100%）").isEqualTo(total);
    assertThat(value(docId, "segment_count")).isGreaterThan(0);
  }

  // ────────────────────────── 2 · 文本型不受影响 ──────────────────────────

  /**
   * ★ 演示物的后半句：「文本型文档不受影响（仍秒级）」。
   *
   * <p>两件事要同时成立：① 不涉及 OCR ⇒ {@code ocr_total_pages = 0}（界面不显示进度行）； ② 照样能正常入库（异步改造没有把文本型那条路拖慢或弄坏）。
   */
  @Test
  void 文本型PDF不涉及OCR且照常入库() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "文本型库");
    String docId = upload(admin, kbId, "structured.pdf");
    waitParsed(docId, 60);

    assertThat(status(docId)).isEqualTo("PENDING_EMBEDDING");
    assertThat(value(docId, "ocr_total_pages")).as("纯文本 PDF ⇒ 分母 0 ⇒ 界面不显示「已识别 N/M 页」").isZero();
    assertThat(value(docId, "ocr_page_count")).isZero();
    assertThat(value(docId, "segment_count")).isGreaterThan(0);
  }

  /** 非 PDF（TXT）同样不涉及 OCR —— 快扫只对 PDF 做，且不该因此多花时间。 */
  @Test
  void 非PDF文档不涉及OCR() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "文本库");
    String docId = uploadText(admin, kbId, "制度.txt", "# 第一章 总则\n\n本制度适用于公司各部门。\n");
    waitParsed(docId, 60);

    assertThat(status(docId)).isEqualTo("PENDING_EMBEDDING");
    assertThat(value(docId, "ocr_total_pages")).isZero();
    assertThat(value(docId, "segment_count")).isGreaterThan(0);
  }

  // ────────────────────────── 夹具与助手 ──────────────────────────

  /**
   * 种子里那个部门的 id（{@code V25} 的 {@code code} 是稳定标识，所以按 code 取）。
   *
   * <p>★ 建档的 {@code orgId} 要的是部门 <strong>id</strong>：{@code T300} / issue #37 起责任部门是组织树外键， 不再接受自由文本
   * {@code department}。
   */
  private String orgIdByCode(String code) {
    return jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, code);
  }

  private int value(String docId, String column) {
    Integer v =
        jdbc.queryForObject(
            "select " + column + " from kb_document where id = ?::uuid", Integer.class, docId);
    return v == null ? -1 : v;
  }

  private String text(String docId, String column) {
    return jdbc.queryForObject(
        "select coalesce(" + column + ", '') from kb_document where id = ?::uuid",
        String.class,
        docId);
  }

  private String status(String docId) {
    return text(docId, "status");
  }

  private void waitParsed(String docId, int seconds) throws InterruptedException {
    for (int i = 0; i < seconds * 2; i++) {
      String current = status(docId);
      if ("PENDING_EMBEDDING".equals(current) || "SEGMENT_FAILED".equals(current)) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private String createKb(String token, String name) throws Exception {
    String body =
        """
        {"name":"%s","category":"制度规范","description":"OCR 集成测试库","icon":"📘",\
        "importType":"LOCAL","embeddingModelId":"%s","maxClassification":"INTERNAL"}\
        """
            .formatted(name, UUID.randomUUID());
    HttpResponse<String> res = post("/api/v1/knowledge-bases", body, token);
    assertThat(res.statusCode()).as("建库失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private String upload(String token, String kbId, String fixture) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/fixtures/" + fixture)) {
      assertThat(in).as("夹具不在 classpath：" + fixture).isNotNull();
      return uploadBytes(token, kbId, fixture, in.readAllBytes(), "application/pdf");
    }
  }

  private String uploadText(String token, String kbId, String filename, String content)
      throws Exception {
    return uploadBytes(
        token, kbId, filename, content.getBytes(StandardCharsets.UTF_8), "text/plain");
  }

  private String uploadBytes(
      String token, String kbId, String filename, byte[] bytes, String contentType)
      throws Exception {
    String boundary = "----OcrTest" + UUID.randomUUID();
    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
    for (String[] field :
        new String[][] {
          {"docType", "制度"}, {"orgId", orgIdByCode("PURCHASING")}, {"classification", "INTERNAL"}
        }) {
      buf.write(
          ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
              .formatted(boundary, field[0], field[1])
              .getBytes(StandardCharsets.UTF_8));
    }
    buf.write(
        ("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"%s\"\r\n"
                + "Content-Type: %s\r\n\r\n")
            .formatted(boundary, filename, contentType)
            .getBytes(StandardCharsets.UTF_8));
    buf.write(bytes);
    buf.write(("\r\n--%s--\r\n").formatted(boundary).getBytes(StandardCharsets.UTF_8));

    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/v1/knowledge-bases/" + kbId + "/documents"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
            .build();
    HttpResponse<String> res =
        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertThat(res.statusCode()).as("上传失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "id");
  }

  private HttpResponse<String> post(String path, String body, String token) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    // ★ 登录那条不能带空的 Bearer：带了会被当成"令牌无效"直接 401（实测）
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private String accessToken(String username, String password) throws Exception {
    HttpResponse<String> res =
        post(
            "/api/v1/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
            null);
    assertThat(res.statusCode()).as("登录失败：%s", res.body()).isEqualTo(200);
    return jsonField(res.body(), "accessToken");
  }

  /** 从包络里取一个字符串字段（真源是契约，这里只是测试的取值工具）。 */
  private static String jsonField(String body, String field) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
    assertThat(m.find()).as("响应里没有字段 %s：%s", field, body).isTrue();
    return m.group(1);
  }
}
