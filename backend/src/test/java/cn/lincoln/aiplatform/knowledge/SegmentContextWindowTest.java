package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.ChunkSource;
import cn.lincoln.aiplatform.knowledge.domain.PlannedSegment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentPlan;
import cn.lincoln.aiplatform.knowledge.domain.Segmenter;
import cn.lincoln.aiplatform.knowledge.infrastructure.SegmentationProperties;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 上下文滑动窗口（{@code #65} · {@code 12 §3.3.1}）—— <b>召回</b>这一半的真库测试。
 *
 * <h3>★ 这条用例要证明的几何关系</h3>
 *
 * <pre>
 *   第三章 … 差旅费报销须经部门负责人审批，审批时限      ← 答案的前半句在这一节的末尾
 *   ## 3.1 时限
 *   为五个工作日，超过时限由财务部督办。                  ← 答案的后半句在下一节的开头
 * </pre>
 *
 * <p>两节之间<strong>没有任何共享文本</strong>：拿"部门负责人审批"去检索，命中上一节是必然的，
 * 而<strong>答案真正所在的那一段（下一节）在窗口关闭时根本不会被召回</strong> —— 症状是"库里明明有，就是答不出来"， 且没有任何埋点会告诉你这里丢了一段。
 *
 * <p>★★ 关窗口与开窗口用的是<b>同一份 markdown、同一个分块器、同一个查询</b>，唯一的差别是 {@code Segmenter} 的窗口参数 ——
 * 所以读数差异只能归因于窗口本身。这也是判据 5 的金丝雀：把窗口关掉，下面的断言必须变红。
 *
 * <h3>★ 为什么断言的是"命中集合"而不是"分数"</h3>
 *
 * <p>分数随词表/分词/表结构变化，命中与否不随；本项目对读数的要求是"能核对"（{@code #231} 那次的教训： 把 400 读成"命中 0 条"比没有读数更糟）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class SegmentContextWindowTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired private SegmentationProperties properties;

  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  private static final String DOC_TYPE = "制度";
  private static final String ORG_CODE = "PURCHASING";
  private static final String TITLE = "差旅报销规定";

  /** ★ 答案被章节边界劈开的那份文档（与 {@code 12 §3.3.1} 的图逐字一致）。 */
  private static final String SPLIT_BY_HEADING =
      """
      # 第三章 差旅费

      差旅费报销须经部门负责人审批，审批时限

      ## 3.1 时限

      为五个工作日，超过时限由财务部督办。
      """;

  /** 提问只用了<strong>前半句</strong>的措辞 —— 后半句所在的段落在窗口关闭时不在候选里。 */
  private static final String QUESTION = "部门负责人审批";

  private static final int LIMIT = 20;
  private static final int WINDOW = 80;

  @Autowired JdbcTemplate jdbc;
  @Autowired SegmentSearchPort segmentSearch;

  private final Segmenter segmenter = new Segmenter();

  private UUID me;
  private UUID kb;

  @BeforeEach
  void seed() {
    me = UUID.randomUUID();
    kb = insertKb(me);
  }

  // ────────────────────────── 判据 1：关窗口召回不到，开窗口召回得到 ──────────────────────────

  @Test
  void answerBearingSegmentIsOnlyReachableWithTheWindowOn() {
    // 同一份文档、同一组分段参数，唯一差别是窗口开/关
    SegmentPlan off = plan(0);
    SegmentPlan on = plan(WINDOW);

    assertThat(off.segments()).as("夹具前提：它必须被切成两段（否则这条用例什么也没证明）").hasSize(2);
    assertThat(on.segments()).hasSize(2);

    UUID offSecond = insertPlanned(off.segments().get(1));
    UUID onSecond = insertPlanned(on.segments().get(1));

    List<UUID> hits = hits();

    assertThat(hits).as("★ 开窗口：答案所在的那一段被召回 —— 它的索引文本里并入了上一节末尾的「审批时限」").contains(onSecond);
    assertThat(hits)
        .as("★★ 关窗口：同一段召回不到 —— 这正是 #65 的现象（拿前半句的措辞提问，后半句那段在候选之外）")
        .doesNotContain(offSecond);
  }

  // ────────────────────────── 窗口不污染正文（引用要能溯源） ──────────────────────────

  @Test
  void windowGoesIntoSearchTextOnlyNeverIntoContent() {
    SegmentPlan on = plan(WINDOW);
    PlannedSegment answer = on.segments().get(1);

    assertThat(answer.content()).as("★ 正文里不能出现邻段的话（引用抽屉展示的就是这份正文）").doesNotContain("审批时限");
    assertThat(answer.searchText()).as("★ 索引文本里有（这才是它能被召回的原因）").contains("审批时限");
    assertThat(answer.searchText()).as("本段自己的内容仍在").contains("为五个工作日");

    // 落库之后，"引用能不能溯源"看的是 content 那一列 —— 与上面同一条纪律的数据库侧证据
    UUID segmentId = insertPlanned(answer);
    assertThat(
            jdbc.queryForObject(
                "select content from kb_segment where id = ?", String.class, segmentId))
        .doesNotContain("审批时限");
  }

  // ────────────────────────── 判据 3：窗口有界、有读数 ──────────────────────────

  @Test
  void windowIsBoundedAndCounted() {
    SegmentPlan on = plan(WINDOW);
    assertThat(on.contextWindowChars()).as("★ 并入的字符数必须可读（否者'这周答案变长了'没人能解释）").isPositive();
    assertThat(plan(0).contextWindowChars()).as("关窗口 ⇒ 0").isZero();

    // ★ 有界：预算收紧时窗口让路，任何一段都不得超过 maxChars（超长会被 SEG-I1 拒整篇）
    for (int maxChars : new int[] {200, 300, 500}) {
      SegmentPlan bounded =
          segmenter.plan(
              SPLIT_BY_HEADING,
              500,
              0,
              maxChars,
              DOC_TYPE,
              ORG_CODE,
              TITLE,
              ChunkSource.TEXT,
              WINDOW);
      assertThat(bounded.segments())
          .as("★ 窗口必须在预算里让路：maxChars=" + maxChars + " 时仍不许超长")
          .allSatisfy(
              segment -> assertThat(segment.searchText().length()).isLessThanOrEqualTo(maxChars));
      assertThat(bounded.contextWindowChars())
          .as("★ 并入量是真实装进去的字符数，不是配置值")
          .isLessThanOrEqualTo(WINDOW * bounded.segments().size());
    }
  }

  // ────────────────────────── 判据 3：可配（值来自 application.yml） ──────────────────────────

  /**
   * ★ 接线这一格：窗口取值来自配置 {@code app.segmentation.context-window}，不是写死的常量。
   *
   * <p>这一条单独存在的理由：{@code Segmenter} 自己的用例只能证明"给它窗口它就并段"， 证明不了"生产路径真的把配置传下去了"。把 yml 里的 80 改成
   * 0，这条必须红。
   */
  @Test
  void theWindowValueComesFromConfiguration() {
    assertThat(properties.contextWindow())
        .as("★ 默认 80，来自 application.yml（可用 SEGMENT_CONTEXT_WINDOW 覆盖；0 = 关闭）")
        .isEqualTo(80);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private SegmentPlan plan(int window) {
    return segmenter.plan(
        SPLIT_BY_HEADING, 500, 0, 1200, DOC_TYPE, ORG_CODE, TITLE, ChunkSource.TEXT, window);
  }

  private List<UUID> hits() {
    return SecurityContexts.asUser(
            me,
            ClassificationLevel.CONFIDENTIAL,
            () ->
                segmentSearch.fullText(
                    new SegmentSearchPort.TextQuery(
                        QUESTION, new RetrievalScope(List.of(kb), null, null, me), LIMIT)))
        .stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .toList();
  }

  private UUID insertKb(UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, kind, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at) values (?, ?,"
            + " 'SHARED', '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, 'PUBLIC', now(), now())",
        id,
        "窗口口径-" + id.toString().substring(0, 8),
        UUID.randomUUID(),
        owner);
    return id;
  }

  /** 把 {@link Segmenter} 的产物原样落库（{@code search_text} 用计划里的那一份 —— 不在这里另拼一遍）。 */
  private UUID insertPlanned(PlannedSegment segment) {
    UUID docId =
        DocumentFixture.insertWithScope(
            jdbc,
            UUID.randomUUID(),
            kb,
            "KB",
            kb,
            me,
            "窗口文档-" + UUID.randomUUID().toString().substring(0, 8),
            ROOT_ORG,
            "INTERNAL");

    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, owner_id, ordinal,"
            + " title, title_path, content, search_text, content_hash, char_count, is_table,"
            + " is_code, oversized_risk, enabled, embedding_status, created_at, updated_at,"
            + " classification)"
            + " values (?, ?::uuid, ?, 'KB', ?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, false,"
            + " true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid))",
        segmentId,
        docId.toString(),
        kb,
        kb,
        me,
        segment.ordinal(),
        segment.title(),
        segment.titlePath(),
        segment.content(),
        segment.searchText(),
        segment.contentHash(),
        segment.charCount(),
        docId.toString());
    return segmentId;
  }
}
