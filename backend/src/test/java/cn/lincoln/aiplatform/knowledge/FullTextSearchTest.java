package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.knowledge.infrastructure.FullTextIndex;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
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
 * 全文检索那一路（{@code chinese_zh}）的真库测试。
 *
 * <h3>★★ 这个类里最值钱的一条：它能抓住「把查询改回整串」这种退化</h3>
 *
 * <p>实测过一件很容易踩的事（{@code V19} 的注释里记了）：
 *
 * <pre>
 *   to_tsquery('chinese_zh', '住宿标准是多少')      →  '住宿' &lt;-&gt; '标准' &lt;-&gt; '是'   ← 短语查询
 *   to_tsquery('chinese_zh', '''住宿'' | ''标准''') →  '住宿' | '标准'            ← 我们用的
 * </pre>
 *
 * <p>第一行要求几个词在文档里<strong>相邻</strong> ⇒ 中文问答里几乎永远为空，而且不报错。 所以 {@link #查询词不必相邻_命中词序不同的分段()}
 * 用一个「词序不同」的分段钉住 OR 语义： 谁要是把查询改回整串（或改成 AND），这条会红。
 *
 * <h3>另一条：启动自检</h3>
 *
 * <p>{@link FullTextIndex} 是设计册要求的「启动必须自检」（{@code 09 §10.2 #3} 第 3 条）。
 * 它缺东西时的症状是静默的（全文检索一条都搜不到，而混合模式看不出异常）， 所以这里对它做一次断言 —— 与架构测试同等待遇。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class FullTextSearchTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final String STUB_API_BASE = "http://localhost:11434/v1";

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  @Autowired FullTextIndex fullTextIndex;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void requireStub() {
    assumeThat(reachable()).as("OpenAI 桩服务不可达（%s）—— 建库要绑一个向量模型", STUB_API_BASE).isTrue();
  }

  @Test
  void startupProbeReportsAvailableAndSegmentsWords() {
    FullTextIndex.Probe probe = fullTextIndex.probe();

    assertThat(probe.available())
        .as("全文索引自检失败：%s —— 缺了它的症状是「全文检索一条都搜不到」，而向量路照常", probe.detail())
        .isTrue();
    // ★ 不是「包含合同违约金」这种弱断言：要证明它真的分成了两个词
    assertThat(probe.detail()).as("分词结果应含两个词元").contains("合同").contains("违约金");
  }

  @Test
  void termsNeedNotBeAdjacent() {
    String kbId = createKb();
    // ★ 词序与查询词相反（「违约金…合同」），中间还夹着别的字 —— 短语查询必然 miss
    String reordered = insertSegment(kbId, "违约金按合同约定计算，具体见第二章");

    List<SegmentSearchPort.Scored> hits =
        SecurityContexts.asClearance(
            ClassificationLevel.CONFIDENTIAL,
            () ->
                segmentSearch.fullText(
                    new SegmentSearchPort.TextQuery("合同违约金", List.of(UUID.fromString(kbId)), 10)));

    assertThat(hits)
        .as("词序不同也能命中 ⇒ 用的是 OR 词元查询，不是 <-> 短语查询（后者会让中文检索几乎永远为空）")
        .extracting(SegmentSearchPort.Scored::segmentId)
        .contains(UUID.fromString(reordered));
  }

  @Test
  void coverageScoreIsMatchedTermRatio() {
    String kbId = createKb();
    String both = insertSegment(kbId, "合同违约金按日计收");
    String partial = insertSegment(kbId, "违约金相关说明");
    String none = insertSegment(kbId, "住宿标准一线城市");

    List<SegmentSearchPort.Scored> hits =
        SecurityContexts.asClearance(
            ClassificationLevel.CONFIDENTIAL,
            () ->
                segmentSearch.fullText(
                    new SegmentSearchPort.TextQuery("合同违约金", List.of(UUID.fromString(kbId)), 10)));

    double bothScore =
        hits.stream()
            .filter(h -> h.segmentId().equals(UUID.fromString(both)))
            .map(SegmentSearchPort.Scored::score)
            .findFirst()
            .orElseThrow();
    double partialScore =
        hits.stream()
            .filter(h -> h.segmentId().equals(UUID.fromString(partial)))
            .map(SegmentSearchPort.Scored::score)
            .findFirst()
            .orElseThrow();

    assertThat(bothScore).as("两个词都命中 ⇒ 1.0").isEqualTo(1.0);
    assertThat(partialScore).as("命中一个 ⇒ 0.5（界面上那个分数与阈值滑块就靠这个量纲说得通）").isEqualTo(0.5);
    assertThat(hits)
        .as("一个词都没中的分段不该出现")
        .extracting(SegmentSearchPort.Scored::segmentId)
        .doesNotContain(UUID.fromString(none));
  }

  @Test
  void disabledSegmentExcluded() {
    String kbId = createKb();
    String disabled = insertSegment(kbId, "合同违约金按日计收");
    jdbc.update("update kb_segment set enabled = false where id = ?::uuid", disabled);

    assertThat(
            SecurityContexts.asClearance(
                ClassificationLevel.CONFIDENTIAL,
                () ->
                    segmentSearch.fullText(
                        new SegmentSearchPort.TextQuery(
                            "合同违约金", List.of(UUID.fromString(kbId)), 10))))
        .extracting(SegmentSearchPort.Scored::segmentId)
        .doesNotContain(UUID.fromString(disabled));
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private String insertSegment(String kbId, String searchText) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    String docId =
        DocumentFixture.insertPending(jdbc, kbId, "全文夹具-" + shortId(), "INTERNAL").toString();

    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, 0, '夹具', '第一章', ?, ?, ?, ?,"
            + " false, false,"
            // 密级从文档继承（V22 的列是 not null）：夹具也用同一条规则，别自己另造一份
            + " false, true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid))",
        id,
        docId,
        kbId,
        kbId,
        searchText,
        searchText,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        searchText.length(),
        docId);
    return id;
  }

  private String createKb() {
    String token = token("admin", "admin");
    String model =
        """
        {"name":"全文闸门向量-%s","kind":"EMBEDDING","supplier":"LOCAL_INFERENCE",
         "baseModel":"bge-m3","apiBase":"%s","dimension":768}\
        """
            .formatted(shortId(), STUB_API_BASE);
    Matcher m = ID.matcher(send("POST", "/api/v1/models", model, token).body());
    assertThat(m.find()).as("建模型失败").isTrue();
    String modelId = m.group(1);

    String kb =
        """
        {"name":"全文闸门库-%s","category":"制度规范","importType":"LOCAL",
         "embeddingModelId":"%s","maxClassification":"SECRET"}\
        """
            .formatted(shortId(), modelId);
    Matcher kbMatcher = ID.matcher(send("POST", "/api/v1/knowledge-bases", kb, token).body());
    assertThat(kbMatcher.find()).as("建库失败").isTrue();
    return kbMatcher.group(1);
  }

  private String token(String username, String password) {
    String body = ("{\"username\":\"%s\",\"password\":\"%s\"}").formatted(username, password);
    Matcher m =
        Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"")
            .matcher(send("POST", "/api/v1/auth/login", body, null).body());
    assertThat(m.find()).as("登录失败").isTrue();
    return m.group(1);
  }

  private HttpResponse<String> send(String method, String path, String body, String token) {
    try {
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
    } catch (Exception e) {
      throw new IllegalStateException("HTTP 调用失败：" + method + " " + path, e);
    }
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
