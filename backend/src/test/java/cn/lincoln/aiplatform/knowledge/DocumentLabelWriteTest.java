package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.SegmentLabelMismatchMetric;
import cn.lincoln.aiplatform.support.PostgresImage;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 标签的<strong>写侧</strong>（{@code #26}）：建档时能填、分段要继承。
 *
 * <p>在此之前标签只有读侧：两列在 {@code V57} 里落了、检索谓词也按交集过滤了，但没有任何路径把值写进去 ⇒ 谓词恒真、这一维"看起来有、实际不用"。本类钉住两件事：
 *
 * <ol>
 *   <li>建档请求里的 {@code labels} 真的落进 {@code kb_document.labels}（不是解析后被丢掉）；
 *   <li>分段落库时<strong>继承</strong>文档标签 —— 谓词读的是 {@code kb_segment}，那一列空着等于没接。
 * </ol>
 *
 * <p>★ 第二条不能只验文档列：检索判定读分段列（冗余列的整个理由就是让谓词在分段表上生效）， 只验文档列会让"继承那行被删掉"照样绿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentLabelWriteTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  /** 标签一致性读数（`#26` 判据 ④）：Gauge 在抓取时求值，这里直接读它的值。 */
  @Autowired MeterRegistry meterRegistry;

  /** 直接调"解析成功 ⇒ 立刻分块"那条生产路径，避免手写 SQL 造分段绕过要验的那一行。 */
  @Autowired DocumentService documentService;

  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void labelsReachTheDocumentRow() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "标签写侧库", "SECRET");

    String docId = register(admin, kbId, "报销制度.md", List.of("财务资料"));

    assertThat(documentLabels(docId)).containsExactly("财务资料");
  }

  @Test
  void segmentsInheritTheDocumentLabels() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "标签继承库", "SECRET");
    String docId = register(admin, kbId, "差旅报销规定.md", List.of("财务资料", "人事资料"));

    documentService.finishParse(
        UUID.fromString(docId),
        ParseResult.ok("# 第一章 报销\n\n差旅费报销须经部门负责人审批，审批时限\n\n## 1.1 时限\n\n为五个工作日。"),
        adminId());

    List<List<String>> segmentLabels = segmentLabels(docId);
    assertThat(segmentLabels).as("文档至少分出两段，否则这条断言是空转的").isNotEmpty();
    assertThat(segmentLabels)
        .as("★ 每个分段都继承文档标签：谓词读 kb_segment.labels，这一列空着等于标签没接")
        .allSatisfy(labels -> assertThat(labels).contains("财务资料").contains("人事资料"));
  }

  @Test
  void documentsWithoutLabelsLeaveBothColumnsEmpty() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "无标签库", "SECRET");
    String docId = register(admin, kbId, "普通制度.md", List.of());

    documentService.finishParse(
        UUID.fromString(docId), ParseResult.ok("# 第一章 总则\n\n本制度自发布之日起施行。"), adminId());

    assertThat(documentLabels(docId)).as("不填标签 = 没有标签，不是等所有标签").isEmpty();
    List<List<String>> segmentLabels = segmentLabels(docId);
    assertThat(segmentLabels).isNotEmpty();
    assertThat(segmentLabels)
        .as("★ 对照组：没填标签时分段也不许凭空长出标签（写反的症状是所有人只看得到没标签的文档）")
        .allMatch(labels -> labels.isEmpty());
  }

  @Test
  void theConsistencyGaugeReadsZeroAfterIngestionAndCatchesARealDrift() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "一致性读数库", "SECRET");
    String docId = register(admin, kbId, "标签漂移.md", List.of("财务资料"));
    documentService.finishParse(
        UUID.fromString(docId), ParseResult.ok("# 第一章 总则\n\n本制度自发布之日起施行。"), adminId());

    assertThat(mismatchGauge()).as("★ 正常入库之后读数必须是 0：这个数不是 0 就说明写入点漏了某条路径").isZero();

    // 手工制造一次漂移（模拟"将来某个只改文档列、忘了改分段列"的写入点）
    jdbc.update(
        "update kb_segment set labels = ?::text[] where document_id = ?::uuid", "{人事资料}", docId);

    assertThat(mismatchGauge())
        .as("★ 漂移必须被读出来：读不出来就等于这条巡检不存在（Gauge 的 SQL 真的在看两张表）")
        .isEqualTo(segmentCount(docId));

    // ★ 必须**复原**这次人为漂移：Gauge 是全局读数、测试类共享同一个库（Testcontainers 单例 +
    //   单一 Spring 上下文），留着一条漂移会让后面任何"读数应为 0"的用例红在别人造的脏数据上
    //   —— 这就是"测试之间通过数据库耦合"的典型形状。
    jdbc.update(
        "update kb_segment set labels = ?::text[] where document_id = ?::uuid", "{财务资料}", docId);
    assertThat(mismatchGauge()).as("★ 复原之后读数必须回到 0 —— 这同时是「读数真的跟着数据变」的反向证据").isZero();
  }

  private double mismatchGauge() {
    return meterRegistry.get(SegmentLabelMismatchMetric.NAME).gauge().value();
  }

  private long segmentCount(String docId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from kb_segment where document_id = ?::uuid", Long.class, docId);
    return count == null ? 0 : count;
  }

  @Test
  void changeLabelsNormalizesAndRewritesEverySegmentInOneGo() throws Exception {
    String admin = accessToken("admin", "admin");
    String kbId = createKb(admin, "改标签库", "SECRET");
    String docId = register(admin, kbId, "标签可改.md", List.of("财务资料"));
    documentService.finishParse(
        UUID.fromString(docId), ParseResult.ok("# 第一章 总则\n\n本制度自发布之日起施行。"), adminId());

    // 故意给脏输入：首尾空白、空串、重复 —— 归一必须在服务端一处做完
    patch(
        "/api/v1/documents/" + docId + "/labels",
        "{\"labels\":[\" 人事资料 \",\"\",\"人事资料\",\"审计\"]}",
        admin);

    assertThat(documentLabels(docId))
        .as("★ 归一：去首尾空白、丢空串、去重，但保持首次出现顺序")
        .containsExactly("人事资料", "审计");
    assertThat(segmentLabels(docId)).as("文档至少分出两段，否则这条断言是空转的").isNotEmpty();
    assertThat(segmentLabels(docId))
        .as("★ 每个分段都必须跟着改：谓词读 kb_segment.labels（V57 冗余列），只改文档列等于没改")
        .allSatisfy(labels -> assertThat(labels).containsExactly("人事资料", "审计"));
    assertThat(mismatchGauge()).as("★ 改完之后漂移读数必须仍是 0 —— 这条与上一条互为对照：漏同步会在这里涨起来").isZero();
  }

  // ────────────────────────── 辅助 ──────────────────────────

  private List<String> documentLabels(String docId) {
    String text =
        jdbc.queryForObject(
            "select labels::text from kb_document where id = ?::uuid", String.class, docId);
    return splitArray(text);
  }

  private List<List<String>> segmentLabels(String docId) {
    return jdbc
        .queryForList(
            "select labels::text from kb_segment where document_id = ?::uuid", String.class, docId)
        .stream()
        .map(this::splitArray)
        .toList();
  }

  /** PostgreSQL 的 {@code text[]} 文本形状是 {@code {a,b}}，空是 {@code {}}。 */
  private List<String> splitArray(String text) {
    if (text == null || text.isBlank() || text.equals("{}")) {
      return List.of();
    }
    return List.of(text.substring(1, text.length() - 1).split(","));
  }

  private UUID adminId() {
    return jdbc.queryForObject("select id from iam_user where username = 'admin'", UUID.class);
  }

  private String register(String admin, String kbId, String name, List<String> labels)
      throws Exception {
    StringBuilder body =
        new StringBuilder("{\"names\":[\"")
            .append(name)
            .append("\"],\"docType\":\"制度\",\"orgId\":\"")
            .append(orgIdByCode("ROOT"))
            .append("\",\"classification\":\"INTERNAL\"");
    if (!labels.isEmpty()) {
      body.append(",\"labels\":[")
          .append(String.join(",", labels.stream().map(label -> "\"" + label + "\"").toList()))
          .append(']');
    }
    body.append('}');
    String response =
        post("/api/v1/knowledge-bases/" + kbId + "/documents", body.toString(), admin);
    return jsonField(response, "\"id\"");
  }

  private String createKb(String admin, String name, String ceiling) throws Exception {
    // ★ 建库不校验模型是否存在（DocumentRegistrationTest 同口径），这里给随机 UUID：
    //   本类验标签写侧，不该被"种子里有没有向量模型"这种环境事实左右
    String modelId = UUID.randomUUID().toString();
    String body =
        "{\"name\":\""
            + name
            + "\",\"category\":\"制度规范\",\"description\":\"标签写侧用例\",\"icon\":\"📘\""
            + ",\"importType\":\"LOCAL\",\"embeddingModelId\":\""
            + modelId
            + "\",\"maxClassification\":\""
            + ceiling
            + "\"}";
    return jsonField(post("/api/v1/knowledge-bases", body, admin), "\"id\"");
  }

  private String orgIdByCode(String code) {
    return jdbc.queryForObject("select id::text from iam_org where code = ?", String.class, code);
  }

  private String accessToken(String username, String password) throws Exception {
    String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("登录失败：%s", response.body()).isEqualTo(200);
    return jsonField(response.body(), "\"accessToken\"");
  }

  private String patch(String path, String body, String token) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("PATCH %s ⇒ %s", path, response.body()).isEqualTo(200);
    return response.body();
  }

  private String post(String path, String body, String token) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("POST %s ⇒ %s", path, response.body()).isEqualTo(200);
    return response.body();
  }

  /** 取第一个 {@code "key":"value"} 的 value —— 这一批用例只关心 id，不引 Jackson 版本差异。 */
  private String jsonField(String body, String quotedKey) {
    int start = body.indexOf(quotedKey);
    assertThat(start).as("响应里没有 %s：%s", quotedKey, body).isGreaterThan(-1);
    int valueStart = body.indexOf('"', start + quotedKey.length() + 1);
    if (valueStart < 0) {
      // "key": "value" 中间带空格
      valueStart = body.indexOf('"', body.indexOf(':', start) + 1);
    }
    int valueEnd = body.indexOf('"', valueStart + 1);
    return body.substring(valueStart + 1, valueEnd);
  }
}
