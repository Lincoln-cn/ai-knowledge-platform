package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
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
 * ★★ {@code #25}：<strong>文档列表与计数必须按 clearance 过滤</strong> —— 密级不只拦检索，还要拦住"看得见有几份"。
 *
 * <h3>修之前的形状（本 Issue 逐字记的那一条）</h3>
 *
 * <p>检索侧早就按 clearance 过滤了（{@code RetrievalSecurityTest} + {@code check_classification_e2e.py} 双闸门），
 * 而<strong>列表与计数侧没有</strong>：低 clearance 的用户打开一个混合密级的库，看到的是**全部**文档 —— 计数、分页数、进度条分母也是全量。{@code 13
 * §5.1} 规则 2 把"计数"明确列为<strong>存在性侧信道</strong>： 用户不但能数出来，还看得到标题、文种与密级本身（等于告诉对方"这里有一份机密文件，叫某某某"）。
 *
 * <h3>★★ 两条判据（本类各一条）</h3>
 *
 * <ol>
 *   <li>{@link #listShowsOnlyWhatTheCallerMaySee()} —— 低 clearance 账号请求 {@code GET
 *       /knowledge-bases/{kbId}/documents} ⇒ 列表里<strong>不含越权文档</strong>， 且 {@code total}
 *       等于他能看见的条数（不是全量）；
 *   <li>{@link #progressDenominatorMatchesTheList()} —— ★ 判据 3：{@code ingestion-progress} 的分母
 *       与列表<strong>同口径</strong>（否则进度条会显示"还有 1 份没向量化"，而列表里看不见它）。
 * </ol>
 *
 * <h3>★ 夹具上的两处刻意安排（不然测的就不是密级那一条）</h3>
 *
 * <ul>
 *   <li><strong>库的可见性设成 PUBLIC</strong>：本类验的是**密级谓词**。若库是 admin 私有的，低 clearance 账号 连库都看不见（404）⇒
 *       那验的是"数据范围"，密级谓词根本没被碰到；
 *   <li><strong>文档密级按账号的 clearance 现造</strong>：可见文档 = {@code values()[clearance]}、 越权文档 = 再高一档。写死
 *       INTERNAL/SECRET 的话，"公开"档账号会连 INTERNAL 都看不见 —— 那时返空是**对**的，而断言会把对的行为判成错（口径照抄 {@code
 *       RetrievalSecurityTest}）。
 * </ul>
 *
 * <p>★ 本类<strong>不需要模型桩</strong>：建档/建库都不调模型（只写库行），列表与计数也纯读库。 用 {@code POST /api/v1/models} 建一个
 * EMBEDDING 行只是为了让库有 {@code embedding_model_id}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class DocumentListClearanceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  /** {@code V25} 种子写死的 ROOT 部门（{@code kb_document.org_id} 是外键，见 Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  /** ★ 演示账号（{@code V4} 种子）；它们的 clearance 各不相同 —— 本类直接问令牌要。 */
  private static final List<String> ACCOUNTS = List.of("chenjun", "liwei", "zhangyun");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  /** 本类造的库（清理用）。 */
  private final List<String> createdKbs = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String kbId : createdKbs) {
      jdbc.update(
          "delete from kb_segment where document_id in (select id from kb_document where kb_id ="
              + " ?::uuid)",
          kbId);
      jdbc.update("delete from kb_document where kb_id = ?::uuid", kbId);
      jdbc.update("delete from kb_knowledge_base where id = ?::uuid", kbId);
    }
    createdKbs.clear();
    jdbc.update("delete from ai_model where name like '列表密级夹具-%'");
  }

  // ────────────────────────── 判据 ①：列表 ──────────────────────────

  /**
   * ★★ 判据①：列表里不含越权文档，且 {@code total} 等于可见条数。
   *
   * <p>★★ 金丝雀（红在哪一行）：把 {@code DocumentRepositoryAdapter#filter} 里的 {@code classificationRank <=
   * ceiling} 那一个条件<strong>连绑定值一起删掉</strong> ⇒ 本用例当场红在 "列表里不许含越权文档"那一条上（越权文档会被列出来，且 total 多 1）。 ★
   * 强调"连绑定值一起删"：只删条件、留着 {@code ceilingRank()}，会红在参数错位上，证不了这条账。
   */
  @Test
  void listShowsOnlyWhatTheCallerMaySee() throws Exception {
    for (String account : ACCOUNTS) {
      String token = token(account, account.equals("admin") ? "admin" : "123456");
      int clearance = clearanceRank(token);
      Fixture f = seed(clearance);

      String body =
          send("GET", "/api/v1/knowledge-bases/" + f.kbId() + "/documents", null, token).body();

      assertThat(body)
          .as(
              "%s（clearance=%s）的文档列表里**不许**出现越权文档 %s —— 密级谓词漏掉的症状是"
                  + "「不但数得出条数，还看得到标题与密级本身」（13 §5.1 规则 2 的存在性侧信道）",
              account, ClassificationLevel.values()[clearance], f.hiddenDocId())
          .doesNotContain(f.hiddenDocId());
      assertThat(body)
          .as("%s 应当看得见 ≤ 自己 clearance 的那一份（否则是过滤过头）", account)
          .contains(f.visibleDocId());

      // ★ total 必须与页内同口径（08 §4.4.3 纪律 1）：越权那一条不许被数进去
      assertThat(totalOf(body))
          .as("★ total 必须是「他能看见的条数」（本库只造了 2 份：1 可见 + 1 越权）—— " + "算成全量就是「数得出有多少份看不到的内容」")
          .isEqualTo(1);
    }
  }

  // ────────────────────────── 判据 ③：进度分母同口径 ──────────────────────────

  /**
   * ★★ 判据③（{@code #25} 正文判据 3）：{@code ingestion-progress} 的分母与列表<strong>同口径</strong>。
   *
   * <p>★ 不同口径的症状：进度条显示"还有 1 份没向量化"，而列表里一份都看不见它 —— 用户会以为列表坏了， 而真相是两处谓词不一样（{@code total} 与 {@code
   * list} 各拼一遍谓词必然漂移）。
   */
  @Test
  void progressDenominatorMatchesTheList() throws Exception {
    String account = "chenjun";
    String token = token(account, "123456");
    int clearance = clearanceRank(token);
    Fixture f = seed(clearance);

    String list =
        send("GET", "/api/v1/knowledge-bases/" + f.kbId() + "/documents", null, token).body();
    String progress =
        send("GET", "/api/v1/knowledge-bases/" + f.kbId() + "/ingestion-progress", null, token)
            .body();

    assertThat(intField(progress, "total"))
        .as(
            "★ 进度条分母必须等于列表能看见的条数（同一条谓词）—— 分母大了就是「数得出看不到的内容」，"
                + "而且进度条永远到不了头。列表 total=%s，进度 total=%s",
            totalOf(list), intField(progress, "total"))
        .isEqualTo(totalOf(list));
  }

  /**
   * 上传策略（{@code #197}）：库可见 ⇒ 200 且返回平台级上限。
   *
   * <p>★ 这是上传对话框的第一步；它 404 时用户看到的是“上传入口坏了”，而不是“策略没配”。
   */
  @Test
  void uploadPolicyIsServedForVisibleKb() throws Exception {
    String token = token("admin", "admin");
    Fixture f = seed(ClassificationLevel.INTERNAL.ordinal());

    String body =
        send("GET", "/api/v1/knowledge-bases/" + f.kbId() + "/upload-policy", null, token).body();

    assertThat(body).as("上传策略必须返回 maxFileSizeMB").contains("\"maxFileSizeMB\":100");
    assertThat(body).as("上传策略必须返回 maxFileCount").contains("\"maxFileCount\":20");
    assertThat(body).as("上传策略必须给出可接受的扩展名").contains(".pdf");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(String kbId, String visibleDocId, String hiddenDocId) {}

  /**
   * 造一个 <strong>PUBLIC 可见</strong>的库 + 两份文档：密级 = {@code values()[clearance]} 与再高一档。
   *
   * <p>★ clearance 已是最高档（{@code CONFIDENTIAL}）时没有"更高一档"可造 ⇒ {@code hiddenDocId} 为 {@code null}，
   * 那一轮只验"看得见"（与 {@code RetrievalSecurityTest} 同一条）。
   */
  private Fixture seed(int clearance) throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    createdKbs.add(kbId);
    // ★ 让所有账号都能看见这个库 —— 本类验的是密级谓词（理由见类注释）
    jdbc.update("update kb_knowledge_base set visibility = 'PUBLIC' where id = ?::uuid", kbId);

    String visible =
        insertDocument(kbId, "可见文档-" + clearance, ClassificationLevel.values()[clearance].name());
    String hidden =
        clearance < ClassificationLevel.CONFIDENTIAL.ordinal()
            ? insertDocument(
                kbId, "越权文档-" + clearance, ClassificationLevel.values()[clearance + 1].name())
            : null;
    return new Fixture(kbId, visible, hidden);
  }

  private String insertDocument(String kbId, String name, String classification) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    return DocumentFixture.insertPending(jdbc, kbId, name, classification).toString();
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"列表密级夹具-向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"http://localhost:11434/v1","dimension":768}\
        """
            .formatted(UUID.randomUUID().toString().substring(0, 8));
    Matcher m = ID.matcher(send("POST", "/api/v1/models", model, token).body());
    assertThat(m.find()).as("建模型失败：本类需要一个 EMBEDDING 行给库当 embedding_model_id").isTrue();
    String kb =
        """
        {"name":"列表密级夹具-库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"CONFIDENTIAL"}\
        """
            .formatted(UUID.randomUUID().toString().substring(0, 8), m.group(1));
    Matcher kbMatcher = ID.matcher(send("POST", "/api/v1/knowledge-bases", kb, token).body());
    assertThat(kbMatcher.find()).as("建库失败").isTrue();
    return kbMatcher.group(1);
  }

  // ────────────────────────── 读数工具 ──────────────────────────

  /** 从令牌里读 clearance（★ 不写死"谁看得见什么"：角色改过之后测试不用改）。 */
  private static int clearanceRank(String token) {
    String payload =
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("\"clearance\"\\s*:\\s*\"([A-Z]+)\"").matcher(payload);
    assertThat(m.find()).as("令牌里没有 clearance 声明：%s", payload).isTrue();
    return ClassificationLevel.valueOf(m.group(1)).ordinal();
  }

  /** 分页回执里的 {@code total}（{@code PageResult} 的形状）。 */
  private static int totalOf(String body) {
    return intField(body, "total");
  }

  private static int intField(String json, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
    assertThat(m.find()).as("回执里没有 %s 字段：%s", field, json).isTrue();
    return Integer.parseInt(m.group(1));
  }

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    assertThat(res.statusCode()).as("登录失败（%s）：%s", username, res.body()).isEqualTo(200);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
    assertThat(m.find()).as("登录回执里没有 accessToken：%s", res.body()).isTrue();
    return m.group(1);
  }

  private HttpResponse<String> send(String method, String path, String body, String token)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json");
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
