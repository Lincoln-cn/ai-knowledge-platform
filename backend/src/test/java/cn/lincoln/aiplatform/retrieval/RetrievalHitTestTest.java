package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import cn.lincoln.aiplatform.support.PostgresImage;
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
 * 命中测试（第 10 周 · 0.3.2）—— 真 PG + 真桩模型，走 <strong>HTTP</strong>（与界面同一条路）。
 *
 * <h3>它钉的是本周的判据 ④ 与「三模式都出结果」</h3>
 *
 * <p>★ 最重要的一条是 {@link #两路都命中的分段必须排在只被一路命中的前面()}： 它是
 * RRF「<strong>在两种检索里都排得靠前的，总体排最前</strong>」这句话的可观测形式 （{@code 03 §2.2}）。构造是刻意设计的：
 *
 * <pre>
 *   分段 A：向量相似度 0.90（不是向量路第一）+ 全文命中 ── 两路都命中
 *   分段 B：向量相似度 0.99（向量路第一）      + 全文不命中
 *   分段 C：向量相似度 0.30（低于阈值 0.6）    + 全文不命中
 * </pre>
 *
 * <p>⇒ 纯向量模式 B 在前；混合模式 <strong>A 反超 B</strong>。如果哪天有人把 RRF 换成 「加权求和」或「取两路并集后按相似度排」，这条会红 ——
 * 那正是要它红的地方。
 *
 * <h3>相似度是怎么造出来的（不是猜的）</h3>
 *
 * <p>桩模型的向量是<strong>确定性</strong>的（{@code tools/openai_stub.py} 用文本的 sha256 做种子），
 * 所以可以先算出「问题的向量」，再构造一个与它余弦相似度恰好为 {@code s} 的向量： {@code v = s·û + √(1-s²)·ô}（{@code ô} 是与 {@code û}
 * 正交的单位向量）。断言用容差比较。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class RetrievalHitTestTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String STUB_API_BASE = "http://localhost:11434/v1";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  /** 查询与夹具用词：分段 A 含它、B/C 不含 —— 「专有名词/精确措辞」那一路的证据。 */
  private static final String QUESTION = "合同违约金";

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired EmbeddingPort embeddingPort;

  @Autowired VectorIndexPort vectorIndex;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void requireStub() {
    // 桩不在时整类跳过（与 EmbeddingPipelineTest 同口径）：向量路跑不了就没有真证据，
    // 与其给出「跳过的绿」，不如明确跳过 —— 而 CI 与本地都会把桩起起来。
    assumeThat(reachable()).as("OpenAI 桩服务不可达（%s）—— 本类需要它来产生确定性向量", STUB_API_BASE).isTrue();
  }

  // ────────────────────────── 判据 ④：RRF 的可观测特征 ──────────────────────────

  @Test
  void rrfPutsTwoPathHitsFirst() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");

    // 纯向量：B（0.99）在前，A（0.90）次之
    String vectorBody = hitTest(token, "VECTOR", 0.6, 5, f);
    assertThat(segmentIdsOf(vectorBody))
        .as("向量模式应按相似度排（回包：%s）", vectorBody)
        .containsExactly(f.segB(), f.segA());

    // 混合：★ A 同时被向量（0.90）与全文命中 ⇒ RRF 让它反超向量路第一的 B
    String hybridBody = hitTest(token, "HYBRID", 0.6, 5, f);
    assertThat(segmentIdsOf(hybridBody))
        .as("RRF 的可观测特征：两路都命中 ⇒ 总体排最前（即「在两种检索里都排得靠前的，总体排最前」）（回包：%s）", hybridBody)
        .containsExactly(f.segA(), f.segB());
  }

  // ────────────────────────── 三模式都出结果（演示物） ──────────────────────────

  @Test
  void allThreeModesReturnHitsAndFullTextIsPrecise() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");

    assertThat(segmentIdsOf(hitTest(token, "VECTOR", 0.6, 5, f))).as("向量模式").isNotEmpty();
    assertThat(segmentIdsOf(hitTest(token, "HYBRID", 0.6, 5, f))).as("混合模式").isNotEmpty();
    // 全文模式：只有 A 含「合同违约金」⇒ 应该只有它（B、C 是别的措辞）
    assertThat(segmentIdsOf(hitTest(token, "FULLTEXT", 0.6, 5, f)))
        .as("全文模式只该命中含该词的 A")
        .containsExactly(f.segA());
  }

  @Test
  void fullTextIgnoresSimilarityThreshold() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");

    // ★ 这是刻意的口径（见 RetrievalService 类注释）：阈值的量纲是「相似度」，只作用于向量路。
    //   拿 0–1 的阈值去卡无界的 ts_rank，全文模式会永远为空 —— 而它恰恰是专有名词类问题的出路。
    assertThat(segmentIdsOf(hitTest(token, "FULLTEXT", 1.0, 5, f)))
        .as("阈值 1.0 下全文模式仍应出结果")
        .containsExactly(f.segA());
    assertThat(segmentIdsOf(hitTest(token, "VECTOR", 1.0, 5, f)))
        .as("同样阈值下向量模式应当一条都不剩（0.99 < 1.0）")
        .isEmpty();
  }

  // ────────────────────────── 阈值先于 TopN ──────────────────────────

  @Test
  void thresholdAppliesBeforeTopN() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");

    // C 的相似度 0.30 低于阈值 0.6 ⇒ 被过滤掉
    List<String> hits = segmentIdsOf(hitTest(token, "VECTOR", 0.6, 5, f));
    assertThat(hits).as("低于阈值的 C 不该出现在结果里").doesNotContain(f.segC());
    assertThat(hits).as("候选 3 条、过阈值 2 条 ⇒ 结果少于 topN（说明真的先筛了）").hasSizeLessThan(5);

    // 阈值抬到 0.95 ⇒ 只剩 B
    assertThat(segmentIdsOf(hitTest(token, "VECTOR", 0.95, 5, f))).containsExactly(f.segB());
  }

  // ────────────────────────── 三级启用过滤（两种模式同谓词） ──────────────────────────

  @Test
  void disabledSegmentExcludedFromAllPaths() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");
    assertThat(segmentIdsOf(hitTest(token, "VECTOR", 0.6, 5, f))).contains(f.segA());

    // 停用 A（它同时是向量路与全文路的候选 ⇒ 能同时验两条路的过滤）
    jdbc.update("update kb_segment set enabled = false where id = ?::uuid", f.segA());

    assertThat(segmentIdsOf(hitTest(token, "VECTOR", 0.6, 5, f)))
        .as("★ 向量路也要按同一套判据过滤（两路同谓词，13 五道闸门之二）")
        .doesNotContain(f.segA());
    assertThat(segmentIdsOf(hitTest(token, "FULLTEXT", 0.6, 5, f)))
        .as("全文路同样过滤")
        .doesNotContain(f.segA());
    assertThat(segmentIdsOf(hitTest(token, "HYBRID", 0.6, 5, f)))
        .as("混合模式同样过滤")
        .doesNotContain(f.segA());
  }

  @Test
  void disabledKnowledgeBaseReturnsNothing() throws Exception {
    Fixture f = seed();
    String token = token("admin", "admin");
    jdbc.update("update kb_knowledge_base set enabled = false where id = ?::uuid", f.kbId());

    assertThat(segmentIdsOf(hitTest(token, "HYBRID", 0.6, 5, f))).isEmpty();
  }

  @Test
  void invisibleKbLeaksNoExistenceSignal() throws Exception {
    seed();
    String token = token("admin", "admin");
    // ★ 不返 404/403：那等于确认「这个 id 存在」。可见性判定在 visibleKbIds 里一次完成。
    String body =
        "{\"question\":\"%s\",\"mode\":\"HYBRID\",\"threshold\":0.6,\"topN\":5,\"kbIds\":[\"%s\"]}"
            .formatted(QUESTION, UUID.randomUUID());
    assertThat(segmentIdsOf(send("POST", "/api/v1/retrieval/test", body, token).body())).isEmpty();
  }

  // ────────────────────────── 跨向量模型（★ 真栈闸门抓出来的） ──────────────────────────

  /**
   * ★ 不限定知识库时，会一次跨到<strong>多个向量模型</strong> ⇒ 仍必须能检索（不许 400）。
   *
   * <p>这条是**真栈闸门教我们的**：`check_flow_walkthrough.py` 点命中测试页的「测试」时拿到 {@code 400 本次检索涉及 5
   * 个不同的向量模型}（开发库里确实有 5 个）—— 而那是演示路径。 早先的写法"多模型就明确拒绝"方向是对的（不同模型的向量在不同空间里），但**能力不全**：
   * 正确做法是**按模型分组、各查各的维度表、再用 RRF 融合**（{@code 10 §4.1}：同一个算法复用三次）。
   */
  @Test
  void crossModelSearchWorksWithoutKbFilter() throws Exception {
    Fixture f = seed();
    Fixture g = seed(); // createKb 每次都新建一个向量模型 ⇒ 两个夹具天然跨两个模型
    String token = token("admin", "admin");

    // ★ 限定在这两个夹具库上：它们**本来就是两个不同的向量模型**，足以验证"按模型分组"；
    //   而不限定范围时别的库也会参与、会被 topN 截断（那是另一件事，别混进来）。
    String body =
        ("{\"question\":\"%s\",\"mode\":\"VECTOR\",\"threshold\":0.6,\"topN\":10,"
                + "\"kbIds\":[\"%s\",\"%s\"]}")
            .formatted(QUESTION, f.kbId(), g.kbId());
    String response = send("POST", "/api/v1/retrieval/test", body, token).body();

    assertThat(response).as("跨模型不该 400（回包：%s）", response).doesNotContain("\"code\":400");
    assertThat(segmentIdsOf(response))
        .as("两个库里的夹具分段都该能被检出来（各自走自己模型的那张维度表）")
        .contains(f.segA(), f.segB(), g.segA(), g.segB());
  }

  // ────────────────────────── 参数校验（口径与 mock 一致） ──────────────────────────

  @Test
  void validationMatchesMockContract() throws Exception {
    String token = token("admin", "admin");

    assertThat(hitTestRaw(token, "{\"question\":\"  \"}"))
        .as("空问题")
        .contains("请输入测试问题")
        .doesNotContain("服务内部错误");
    assertThat(hitTestRaw(token, "{\"question\":\"合同违约金\",\"threshold\":1.5}"))
        .as("阈值越界")
        .contains("相似度阈值应在 0～1 之间");
    assertThat(hitTestRaw(token, "{\"question\":\"合同违约金\",\"topN\":0}"))
        .as("TopN 下界")
        .contains("TopN 至少为 1");
    assertThat(hitTestRaw(token, "{\"question\":\"合同违约金\",\"topN\":99}"))
        .as("TopN 上界（与 mock 的 PUT 校验一致：1–20）")
        .contains("TopN 应在 1～20 之间");
    assertThat(hitTestRaw(token, "{\"question\":\"合同违约金\",\"mode\":\"SEMANTIC\"}"))
        .as("模式拼错要给可读原因，而不是 Jackson 的通用报错")
        .contains("检索模式只支持 VECTOR / FULLTEXT / HYBRID");
  }

  // ────────────────────────── 检索参数（GET / PUT） ──────────────────────────

  @Test
  void retrievalParamsDefaultsAndPartialUpdate() throws Exception {
    String token = token("admin", "admin");
    String kbId = createKb(token);

    String defaults =
        send("GET", "/api/v1/knowledge-bases/" + kbId + "/retrieval", null, token).body();
    assertThat(defaults)
        .as("新库回默认值而不是 404（mock 的注释写明了这条）")
        .contains("\"mode\":\"HYBRID\"")
        .contains("\"threshold\":0.6")
        .contains("\"topN\":5")
        .contains("\"noRefStrategy\":\"REFUSE\"");

    // Partial：只改 topN，其余必须保持原值
    String updated =
        send("PUT", "/api/v1/knowledge-bases/" + kbId + "/retrieval", "{\"topN\":9}", token).body();
    assertThat(updated).as("只改 topN").contains("\"topN\":9").contains("\"mode\":\"HYBRID\"");
    assertThat(send("GET", "/api/v1/knowledge-bases/" + kbId + "/retrieval", null, token).body())
        .as("★ Partial 更新不能把没传的字段重置成默认")
        .contains("\"topN\":9")
        .contains("\"threshold\":0.6");

    // 非法值给可读原因
    assertThat(
            send(
                    "PUT",
                    "/api/v1/knowledge-bases/" + kbId + "/retrieval",
                    "{\"mode\":\"SEMANTIC\"}",
                    token)
                .body())
        .contains("检索模式只支持 VECTOR / FULLTEXT / HYBRID");
    assertThat(
            send(
                    "PUT",
                    "/api/v1/knowledge-bases/" + kbId + "/retrieval",
                    "{\"threshold\":2}",
                    token)
                .body())
        .contains("相似度阈值应在 0～1 之间");
    assertThat(
            send("PUT", "/api/v1/knowledge-bases/" + kbId + "/retrieval", "{\"topN\":0}", token)
                .body())
        .contains("TopN 应在 1～20 之间");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 一套可用于检索的夹具：库 + 文档 + 三个分段（A/B/C）+ 三条向量。
   *
   * <p>向量相似度：A=0.90、B=0.99、C=0.30（都相对「问题的向量」而言）。
   */
  private record Fixture(
      String kbId, String modelId, String docId, String segA, String segB, String segC) {}

  private Fixture seed() throws Exception {
    String token = token("admin", "admin");
    String kbId = createKb(token);
    String modelId =
        jdbc.queryForObject(
            "select embedding_model_id::text from kb_knowledge_base where id = ?::uuid",
            String.class,
            kbId);
    String docId = insertDocument(kbId);

    // 问题的向量（桩是确定性的 ⇒ 这里算出来的与检索时算出来的**同一份**）
    float[] question = embeddingPort.embed(UUID.fromString(modelId), List.of(QUESTION)).getFirst();

    // A 含查询词（全文命中）+ 相似度 0.90；B/C 是别的措辞
    String segA = insertSegment(kbId, docId, 0, "违约金", "制度｜合同违约金按日计收，具体见第二章");
    String segB = insertSegment(kbId, docId, 1, "住宿", "制度｜住宿标准一线城市每人每天不超过伍佰元");
    String segC = insertSegment(kbId, docId, 2, "交通", "制度｜交通费用实报实销需提供合规票据");

    vectorIndex.upsert(entry(segA, kbId, docId, modelId, withCosine(question, 0.90, 3)));
    vectorIndex.upsert(entry(segB, kbId, docId, modelId, withCosine(question, 0.99, 5)));
    vectorIndex.upsert(entry(segC, kbId, docId, modelId, withCosine(question, 0.30, 7)));

    return new Fixture(kbId, modelId, docId, segA, segB, segC);
  }

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

  /**
   * 造一个与 {@code base} 余弦相似度约等于 {@code target} 的向量： {@code v = target·û + √(1-target²)·ô}（{@code ô}
   * 取一个与 {@code û} 正交的单位向量）。
   */
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
    // 取一个与 u 不正交的基向量，投影后得到正交方向
    double[] e = new double[n];
    int k = basisIndex % n;
    e[k] = 1.0;
    double dot = u[k];
    double[] o = new double[n];
    double oNorm = 0;
    for (int i = 0; i < n; i++) {
      o[i] = e[i] - dot * u[i];
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

  private String insertDocument(String kbId) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        // ★ 责任部门已改成 org_id（UUID 外键，V27）。用 ROOT 的**确定性** id（V25 种子写死）：
        //   org_id 是外键 ⇒ 不能塞随机 UUID。见 Issue #48。
        "insert into kb_document (id, kb_id, name, source, doc_type, org_id, classification,"
            + " status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?, 'local', '制度',"
            + " '00000000-0000-4000-8000-000000000001'::uuid, 'INTERNAL',"
            + " 'PENDING_EMBEDDING', 0, 0, true, now(), now())",
        id,
        kbId,
        "检索夹具文档-" + shortId());
    return id;
  }

  private String insertSegment(
      String kbId, String docId, int ordinal, String title, String searchText) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, ordinal, title, title_path, content,"
            + " search_text, content_hash, char_count, is_table, is_code, oversized_risk, enabled,"
            + " embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, false, false, false, true,"
            // 密级从文档继承（V22 的列是 not null）：夹具也用同一条规则，别自己另造一份
            + " 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid))",
        id,
        docId,
        kbId,
        ordinal,
        title,
        "第一章",
        searchText,
        searchText,
        // content_hash 是 sha256 十六进制 64 字符（SEG-I7）；这里只要求非空且长度合规
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        searchText.length(),
        docId);
    return id;
  }

  // ────────────────────────── HTTP 工具（与既有集成测试同口径） ──────────────────────────

  private String hitTest(String token, String mode, double threshold, int topN, Fixture f)
      throws Exception {
    String body =
        ("{\"question\":\"%s\",\"mode\":\"%s\",\"threshold\":%s,\"topN\":%d,"
                + "\"noRefStrategy\":\"REFUSE\",\"kbIds\":[\"%s\"]}")
            .formatted(QUESTION, mode, threshold, topN, f.kbId());
    return send("POST", "/api/v1/retrieval/test", body, token).body();
  }

  private String hitTestRaw(String token, String body) throws Exception {
    return send("POST", "/api/v1/retrieval/test", body, token).body();
  }

  /** 从回包里按出现顺序取 segmentId（断言次序，而不只是「有没有」）。 */
  private static List<String> segmentIdsOf(String responseBody) {
    Matcher m = Pattern.compile("\"segmentId\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(responseBody);
    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

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
        {"name":"检索闸门向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"%s","dimension":768}\
        """
            .formatted(shortId(), STUB_API_BASE);
    Matcher m = ID.matcher(send("POST", "/api/v1/models", model, token).body());
    assertThat(m.find()).as("建模型失败").isTrue();
    String modelId = m.group(1);

    String kb =
        """
        {"name":"检索闸门库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(shortId(), modelId);
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
