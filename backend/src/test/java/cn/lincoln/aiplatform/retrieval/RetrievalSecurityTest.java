package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
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
 * 密级 pre-filter（第 11 周 · 0.3.3）—— 真 PG + 真桩 + **走 HTTP**（与界面同一条路）。
 *
 * <h3>三条断言分别对应当周的判据</h3>
 *
 * <ol>
 *   <li>★ <strong>越权检索 0 命中</strong>（判据 ①）：{@link #lowClearanceNeverSeesHigherClassification()} ——
 *       对**三种模式**都断言（一条路漏了就红）。
 *   <li>★★ <strong>金丝雀：任一路丢掉密级条件都必须变红</strong>（判据 ②）：{@link #eachPathFiltersItsOwnOutput()} ——
 *       断言的是**两条路各自的原始输出**，理由见那个方法的注释（端到端那条链上还有 {@code visibleAmong} 兜着， 单路 SQL 的问题在那里看不出来）。
 *   <li>★ <strong>过滤之后不为空</strong>（判据 ④）：{@link #filteredScanStillReturnsWhatIsVisible()} ——
 *       库里"看不见的"比"看得见的"多且更像，pre-filter + {@code hnsw.iterative_scan}
 *       仍必须把能看的那几条捞出来（后过滤写法在这里会**返回空**，那正是这一条要防的）。
 * </ol>
 *
 * <h3>★ 判据 ② 的反证：两条都实测过（"它能红"的凭据）</h3>
 *
 * <p>把某一路的密级条件<strong>连同它的绑定值一起</strong>删掉再跑。★ 只删条件不删参数会变成一次 SQL 参数错位， 那种红不算数 ——
 * 第一次就是这么错的，红在了"看得见的内容不见了"而不是"越权出现了"。
 *
 * <table>
 *   <tr><th>改动</th><th>读数</th></tr>
 *   <tr><td>全文路去掉密级条件</td><td>红在"全文路的原始输出里出现了越权分段"：原始输出 6 条 = 2 可见 + <strong>4 越权</strong></td></tr>
 *   <tr><td>向量路去掉密级条件</td><td>红在"向量路的原始输出里出现了越权分段"：原始输出 6 条 = 2 可见 + <strong>4 越权</strong></td></tr>
 *   <tr><td>改回（当前状态）</td><td>两条路各只剩 2 条可见，本类 3/3 绿</td></tr>
 * </table>
 *
 * <h3>两处夹具上的刻意安排（不然测的就不是密级那一条）</h3>
 *
 * <ul>
 *   <li><strong>库的可见性设成 PUBLIC</strong>：本类验的是**密级**谓词。若库是 admin 私有的， 低 clearance 账号连库都看不见 ⇒
 *       返空是"数据范围"起的作用，密级谓词根本没被验到。
 *   <li><strong>文档密级按账号的 clearance 现造</strong>：可见文档 = {@code values()[clearance]}、 越权文档 = 再高一档。写死
 *       INTERNAL/SECRET 的话，"公开"档账号会连 INTERNAL 都看不见 —— 那时返空是**对**的，而断言会把对的行为判成错。
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class RetrievalSecurityTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String STUB_API_BASE = "http://localhost:11434/v1";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  private static final String QUESTION = "合同违约金";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired EmbeddingPort embeddingPort;

  @Autowired VectorIndexPort vectorIndex;

  /** ★ 直接问两条路要原始输出（金丝雀判据②就落在它身上，见 {@link #eachPathFiltersItsOwnOutput()}）。 */
  @Autowired SegmentSearchPort segmentSearch;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void requireStub() {
    assumeThat(reachable()).as("OpenAI 桩服务不可达（%s）—— 本类要用它产生确定性向量", STUB_API_BASE).isTrue();
  }

  // ────────────────────────── 判据 ① / ②：越权 0 命中 ──────────────────────────

  @Test
  void lowClearanceNeverSeesHigherClassification() throws Exception {
    for (String account : List.of("admin", "chenjun", "liwei", "zhangyun")) {
      String token = token(account, account.equals("admin") ? "admin" : "123456");
      int clearance = clearanceRank(token);
      Fixture f = seed(clearance);

      for (String mode : List.of("VECTOR", "FULLTEXT", "HYBRID")) {
        List<String> hitDocs = docsOf(segmentIdsOf(hitTest(token, mode, f.kbId())));

        if (f.hiddenDocId() != null) {
          assertThat(hitDocs)
              .as(
                  "%s（clearance=%s）在 %s 模式下**一条越权命中都不许有**（越权文档=%s）",
                  account, ClassificationLevel.values()[clearance], mode, f.hiddenDocId())
              .doesNotContain(f.hiddenDocId());
        }
        assertThat(hitDocs)
            .as(
                "%s（clearance=%s）在 %s 模式下应当看得见 ≤ clearance 的内容（否则是过滤过头）",
                account, ClassificationLevel.values()[clearance], mode)
            .contains(f.visibleDocId());
      }

      // 清掉这一轮的夹具，避免下一轮账号把上一轮的文档也捞进来（每个账号一套）
      jdbc.update("delete from kb_segment where kb_id = ?::uuid", f.kbId());
      jdbc.update("delete from kb_document where kb_id = ?::uuid", f.kbId());
    }
  }

  // ────────────────────────── 判据 ②：金丝雀 —— 每一路的**原始输出**就得干净 ──────────────────────────

  /**
   * ★★ 金丝雀：断言<strong>两条检索路各自的原始输出</strong>（不是端到端结果）里没有越权分段。
   *
   * <p>★ <strong>为什么必须是原始输出</strong>：端到端那条链上还有 {@code visibleAmong} 作第二道网， 所以"某一路的 SQL
   * 漏了密级条件"在端到端断言里<strong>看不出来</strong> —— 这是实测出来的（第一次写这条金丝雀时就栽在这）：把全文路的谓词删掉，端到端仍然全绿， 因为 {@code
   * visibleAmong} 把越权候选又筛掉了。而"查完再筛"正是 {@code 13 §三} 明确禁止的写法： 向量索引的 top-N
   * 会先被越权内容占满，剩下给可见内容的条数就少了（高选择性下甚至为空）。 所以判据必须落在<strong>每一条路自己</strong>身上。
   *
   * <p>★ <strong>夹具为什么能抓住它</strong>：越权分段被刻意造成"更像问句"（相似度 0.99 对 0.70）、 文本也同样含问句词 ⇒
   * 漏谓词时它不是"混在中间"，而是<strong>排在最前面</strong>。 同时断言"可见的必须还在"，否则"返空"也能骗过"不含越权"。
   */
  @Test
  void eachPathFiltersItsOwnOutput() throws Exception {
    String token = lowestClearanceToken();
    int clearance = clearanceRank(token);
    ClassificationLevel level = ClassificationLevel.values()[clearance];
    Fixture f = seed(clearance);
    assertThat(f.hiddenSegmentIds()).as("本判据需要一档「够不着」的越权内容，否则它什么都没验").isNotEmpty();

    List<UUID> textHits =
        SecurityContexts.asClearance(
                level,
                () ->
                    segmentSearch.fullText(
                        new SegmentSearchPort.TextQuery(
                            QUESTION, List.of(UUID.fromString(f.kbId())), 10)))
            .stream()
            .map(SegmentSearchPort.Scored::segmentId)
            .toList();
    assertThat(textHits)
        .as("全文路的原始输出里出现了越权分段 ⇒ 它的 SQL 少了密级条件（13 §三：pre-filter，不是查完再筛）")
        .doesNotContainAnyElementsOf(f.hiddenSegmentIds())
        .containsAll(f.visibleSegmentIds().stream().map(UUID::fromString).toList());

    List<UUID> vectorHits =
        SecurityContexts.asClearance(
                level,
                () ->
                    segmentSearch.vectorSearch(
                        new SegmentSearchPort.VectorQuery(
                            f.question().length,
                            f.question(),
                            List.of(UUID.fromString(f.kbId())),
                            10)))
            .stream()
            .map(SegmentSearchPort.Scored::segmentId)
            .toList();
    assertThat(vectorHits)
        .as("向量路的原始输出里出现了越权分段 ⇒ 它的 SQL 少了密级条件；" + "夹具里越权那 4 条相似度更高（0.99），所以漏了它会**排在最前面**")
        .doesNotContainAnyElementsOf(f.hiddenSegmentIds())
        .containsAll(f.visibleSegmentIds().stream().map(UUID::fromString).toList());
  }

  // ────────────────────────── 判据 ④：过滤后不为空 ──────────────────────────

  @Test
  void filteredScanStillReturnsWhatIsVisible() throws Exception {
    String token = lowestClearanceToken();
    int clearance = clearanceRank(token);
    Fixture f = seed(clearance);

    List<String> hits = segmentIdsOf(hitTest(token, "VECTOR", f.kbId()));

    assertThat(hits)
        .as("pre-filter + hnsw.iterative_scan 必须把能看的那几条捞出来；" + "后过滤写法在这里会返回**空**（越权那 4 条按相似度排在最前）")
        .isNotEmpty()
        .containsAll(f.visibleSegmentIds());
    if (f.hiddenDocId() != null) {
      assertThat(docsOf(hits)).as("非空的前提是「一条越权都没有」").doesNotContain(f.hiddenDocId());
    }
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(
      String kbId,
      String visibleDocId,
      String hiddenDocId,
      List<String> visibleSegmentIds,
      List<UUID> hiddenSegmentIds,
      float[] question) {}

  /**
   * 造一库两文档（按账号的 clearance 定密级）：
   *
   * <ul>
   *   <li>可见文档：密级 = {@code values()[clearance]}，2 段，相似度 <strong>0.70</strong>；
   *   <li>越权文档：密级 = 再高一档，4 段，相似度 <strong>0.99</strong> ⇒ 它"更像问句"， 所以一旦谓词漏掉，它会顶到最前面（金丝雀）。
   * </ul>
   *
   * <p>clearance 已经是最高档（CONFIDENTIAL）时没有"更高一档"可造 ⇒ hiddenDocId 为 null， 那一轮只验"看得见"。
   */
  private Fixture seed(int clearance) throws Exception {
    String admin = token("admin", "admin");
    String kbId = createKb(admin);
    // ★ 让所有账号都能看见这个库 —— 本类验的是密级谓词（理由见类注释）
    jdbc.update("update kb_knowledge_base set visibility = 'PUBLIC' where id = ?::uuid", kbId);

    String modelId =
        jdbc.queryForObject(
            "select embedding_model_id::text from kb_knowledge_base where id = ?::uuid",
            String.class,
            kbId);
    float[] question = embeddingPort.embed(UUID.fromString(modelId), List.of(QUESTION)).getFirst();

    String visibleDoc =
        insertDocument(kbId, "可见文档-" + clearance, ClassificationLevel.values()[clearance].name());
    List<String> visibleSegments = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String segId = insertSegment(kbId, visibleDoc, i, "制度｜合同违约金按日计收，第" + i + "条");
      vectorIndex.upsert(
          entry(segId, kbId, visibleDoc, modelId, withCosine(question, 0.70, i + 3)));
      visibleSegments.add(segId);
    }

    String hiddenDoc = null;
    List<UUID> hiddenSegments = new ArrayList<>();
    if (clearance < ClassificationLevel.CONFIDENTIAL.ordinal()) {
      hiddenDoc =
          insertDocument(
              kbId, "越权文档-" + clearance, ClassificationLevel.values()[clearance + 1].name());
      for (int i = 0; i < 4; i++) {
        String segId = insertSegment(kbId, hiddenDoc, i, "制度｜合同违约金按日计收，越权第" + i + "条");
        vectorIndex.upsert(
            entry(segId, kbId, hiddenDoc, modelId, withCosine(question, 0.99, i + 11)));
        hiddenSegments.add(UUID.fromString(segId));
      }
    }
    return new Fixture(kbId, visibleDoc, hiddenDoc, visibleSegments, hiddenSegments, question);
  }

  /** 找 clearance 最低的演示账号的令牌。★ 断言它低于 SECRET，否则本类测不出"越权"这件事。 */
  private String lowestClearanceToken() throws Exception {
    String best = null;
    int bestRank = Integer.MAX_VALUE;
    for (String account : List.of("chenjun", "liwei", "zhangyun")) {
      String token = token(account, "123456");
      int rank = clearanceRank(token);
      if (rank < bestRank) {
        bestRank = rank;
        best = token;
      }
    }
    assertThat(bestRank)
        .as("演示账号里应当至少有一个 clearance < SECRET（否则本类的越权断言没有意义）")
        .isLessThan(ClassificationLevel.SECRET.ordinal());
    return best;
  }

  /** 从令牌里读 clearance（★ 不写死"谁看得见什么"：角色改过之后测试不用改）。 */
  private static int clearanceRank(String token) {
    String payload =
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("\"clearance\"\\s*:\\s*\"([A-Z]+)\"").matcher(payload);
    assertThat(m.find()).as("令牌里没有 clearance 声明：%s", payload).isTrue();
    return ClassificationLevel.valueOf(m.group(1)).ordinal();
  }

  // ────────────────────────── 夹具工具 ──────────────────────────

  private VectorIndexPort.Entry entry(
      String segmentId, String kbId, String docId, String modelId, float[] vector) {
    return new VectorIndexPort.Entry(
        UUID.fromString(segmentId),
        UUID.fromString(kbId),
        UUID.fromString(docId),
        UUID.fromString(modelId),
        "夹具分段",
        vector);
  }

  /** 与 {@code RetrievalHitTestTest.withCosine} 同一构造：造一个与 base 余弦相似度约为 target 的向量。 */
  private static float[] withCosine(float[] base, double target, int basisIndex) {
    int n = base.length;
    double norm = 0;
    for (float value : base) {
      norm += (double) value * value;
    }
    norm = Math.sqrt(norm);
    double[] u = new double[n];
    for (int i = 0; i < n; i++) {
      u[i] = base[i] / norm;
    }
    double[] e = new double[n];
    int k = basisIndex % n;
    e[k] = 1.0;
    double[] o = new double[n];
    double oNorm = 0;
    for (int i = 0; i < n; i++) {
      o[i] = e[i] - u[k] * u[i];
      oNorm += o[i] * o[i];
    }
    oNorm = Math.sqrt(oNorm);
    float[] vector = new float[n];
    double w = Math.sqrt(Math.max(0, 1 - target * target));
    for (int i = 0; i < n; i++) {
      vector[i] = (float) (target * u[i] + w * (o[i] / oNorm));
    }
    return vector;
  }

  private String insertDocument(String kbId, String name, String classification) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    //   ★ 本类要造的正是"产品路径拒绝的形状"：文档密级高于库上限（越权夹具）。
    return DocumentFixture.insertPending(jdbc, kbId, name, classification).toString();
  }

  private String insertSegment(String kbId, String docId, int ordinal, String searchText) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, ?, '夹具', '第一章', ?, ?, ?, ?,"
            + " false, false,"
            // ★ 密级从文档继承（V22 的列是 not null，且 13 §2.1 ② 要求强制继承）——
            //   本类的夹具**必须**走这条继承路径：写死一个密级就会把被测的谓词换成另一条
            + " false, true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid))",
        id,
        docId,
        kbId,
        kbId,
        ordinal,
        searchText,
        searchText,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        searchText.length(),
        docId);
    return id;
  }

  /** 命中的分段属于哪个文档（库里查，不靠回包字段）。 */
  private List<String> docsOf(List<String> segmentIds) {
    if (segmentIds.isEmpty()) {
      return List.of();
    }
    // id 来自回包且已被正则限定为 UUID 串 ⇒ 拼进 IN 列表没有注入面
    String sql =
        "select distinct document_id::text from kb_segment where id in ("
            + String.join(",", segmentIds.stream().map(id -> "'" + id + "'").toList())
            + ")";
    return jdbc.queryForList(sql, String.class);
  }

  // ────────────────────────── HTTP ──────────────────────────

  private String hitTest(String token, String mode, String kbId) throws Exception {
    double threshold = "VECTOR".equals(mode) ? 0.0 : 0.6;
    String body =
        ("{\"question\":\"%s\",\"mode\":\"%s\",\"threshold\":%s,\"topN\":10,\"kbIds\":[\"%s\"]}")
            .formatted(QUESTION, mode, threshold, kbId);
    return send("POST", "/api/v1/retrieval/test", body, token).body();
  }

  private static List<String> segmentIdsOf(String responseBody) {
    Matcher m = Pattern.compile("\"segmentId\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(responseBody);
    List<String> ids = new ArrayList<>();
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

  private String token(String username, String password) throws Exception {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    HttpResponse<String> res = send("POST", "/api/v1/auth/login", body, null);
    Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
    assertThat(m.find()).as("登录失败（%s）：%s", username, res.body()).isTrue();
    return m.group(1);
  }

  private String createKb(String token) throws Exception {
    String model =
        """
        {"name":"密级闸门向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"%s","dimension":768}\
        """
            .formatted(shortId(), STUB_API_BASE);
    Matcher m = ID.matcher(send("POST", "/api/v1/models", model, token).body());
    assertThat(m.find()).as("建模型失败").isTrue();
    String kb =
        """
        {"name":"密级闸门库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"CONFIDENTIAL"}\
        """
            .formatted(shortId(), m.group(1));
    Matcher kbMatcher = ID.matcher(send("POST", "/api/v1/knowledge-bases", kb, token).body());
    assertThat(kbMatcher.find()).as("建库失败").isTrue();
    return kbMatcher.group(1);
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
