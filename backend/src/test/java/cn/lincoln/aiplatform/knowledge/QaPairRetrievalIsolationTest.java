package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.application.QaPairService.ImportPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ 判据 ③：<strong>检索链路现在不消费问答对</strong>（Issue {@code #78}）—— 真 PG，纯 SQL 夹具。
 *
 * <h3>为什么这一条是这一批最重要的断言</h3>
 *
 * <p>它防的是两件都<strong>不会报错</strong>的事：
 *
 * <ol>
 *   <li><strong>"留了数据层"被读成"已接入"</strong> —— 下一代人（或三个月后的自己）看到 {@code kb_qa_pair} 这张表与 {@code
 *       QaPairService}，很自然会以为问答对已经在检索里生效了； 而 {@code 10 §7.4} 把它的<strong>用途</strong>定成"B1
 *       不达标时的补救手段"，这时候读错 会让人<strong>把 B1 的读数归因到它头上</strong>。
 *   <li><strong>第 18 周的 B1 读数被它污染</strong> —— 只要它悄悄进了候选集，B1 就不再是"向量 + 全文 + 策略" 的读数，而 {@code §二}
 *       的判定（B1 ≥70%）就失去了判据。
 * </ol>
 *
 * <h3>三条断言各管什么（缺一条就有一种"读成已接入"的路径没人守）</h3>
 *
 * <ul>
 *   <li>{@link #fullTextHitsAndOrderAreUnchangedByQaPairs()} —— <strong>候选集与顺序逐字节不变</strong>：
 *       导入前后各跑一次真检索，断言 {@code segmentId} 列表{@code ==}（含顺序）。
 *   <li>{@link #qaPairContentIsNotInTheFullTextIndex()} —— <strong>连内容都不在索引里</strong>：
 *       拿问答对独有的词去检索，<strong>导入前后都是空</strong>。★ 这条比上一条更硬：上一条可以说 "碰巧顺序没变"，这一条直接证明"问答对的文字压根没进检索文本"。
 *   <li>{@link #qaPairsAreNotSegments()}/{@link #qaPairRowsDoNotEnterTheSegmentUnion()} ——
 *       <strong>库层形状</strong>：问答对不在 {@code kb_segment} 里，也没有让分段的并集多出任何一行。
 * </ul>
 *
 * <h3>★ 为什么夹具是纯 SQL（不建库、不建模型、不要桩服务）</h3>
 *
 * <p>要断言的只是"检索的候选集由 {@code kb_segment} 决定"这一件事，而两条检索路里<strong>全文路</strong>完全 在库里（{@code
 * content_tsv} + {@code zhparser}），不需要向量、也就不需要桩模型。这样这个类 <strong>不会因为桩服务不可达而整类跳过</strong> ——
 * 而"留缝被读成接入"这件事不该跟着桩一起失效。
 *
 * <p>★ 为什么走 {@code SegmentSearchPort} 而不是 HTTP：它就是 {@code RetrievalService} 消费的那个端口 （{@code
 * retrieveOneQuery} 的第二段），也就是<strong>真正的检索入口</strong>；而问答对如果想混进候选集， 唯一可能的路径就是这里多出一条
 * union。<strong>一条能红、且指向唯一实现点的断言</strong>比走页面更有价值。
 */
@SpringBootTest
@Testcontainers
class QaPairRetrievalIsolationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID CURRENT_USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  /** 语料用的查询词。★ 与问答对的问题**刻意不同**（避免把"语料本就该命中"混进断言）。 */
  private static final String QUERY = "合同违约金";

  /** 问答对独有的词 —— 语料里一个都不含它。★ 用它检索必须为空，否则说明问答对被索引了。 */
  private static final String QA_ONLY_TERM = "量子鲸落";

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  @Autowired QaPairService qaPairs;

  // ────────────────────────── 断言 ①：候选集与顺序逐字节不变 ──────────────────────────

  /**
   * 造一条"内容与查询高度相似"的问答对 ⇒ 检索的命中集与顺序<strong>完全不变</strong>。
   *
   * <p>★ "高度相似"是刻意构造的：问答对的<strong>问题</strong>用的就是查询词本身、<strong>答案</strong>用的就是 语料分段里的原文 ——
   * 如果哪天有人把它当成候选来源接进 {@code fullText}（或并进候选集）， 这条会立刻红，而且红在"多了一条 / 顺序变了"上。
   *
   * <p>★ 断言用 {@code containsExactly}（含顺序），不是 {@code contains}：只查"在不在"的话， "问答对插在中间把既有命中挤下去"这种污染照样绿。
   */
  @Test
  void fullTextHitsAndOrderAreUnchangedByQaPairs() {
    String kbId = insertKb();
    // 两条都能命中查询词，顺序由 ts_rank 定：先造的那条（正文更短、词更集中）在前
    insertSegment(kbId, 0, "违约金按合同约定计算，具体见第二章");
    insertSegment(kbId, 1, "合同违约金按日计收，违约金上限为合同金额的两成");

    List<UUID> before = hitIds(kbId);
    assertThat(before).as("前提：语料本身必须能命中（否则'不变'这件事没有意义）—— 回包为空说明夹具或分词有问题").hasSize(2);

    importQaPair(kbId, "合同违约金怎么算？", "合同违约金按日计收，违约金上限为合同金额的两成。", ClassificationLevel.INTERNAL);

    assertThat(hitIds(kbId))
        .as(
            "★★ 判据 ③：问答对**不许改变既有命中集与顺序**（10 §7.4 定它是补救手段，不是候选来源）。"
                + "若这里红了：说明检索链路（JdbcSegmentSearchQuery / RetrievalService）开始消费 kb_qa_pair 了 —— "
                + "而这一批的边界就是'检索侧一行不接'")
        .containsExactlyElementsOf(before);
  }

  // ────────────────────────── 断言 ②：问答对的文字压根不在索引里 ──────────────────────────

  /**
   * 问答对独有的词 ⇒ 导入前后检索都是<strong>空</strong>。
   *
   * <p>★ 这条与上一条的分工：上一条证"没多出候选"，这一条证"问答对的文字没进检索文本"。两条都可能是对方的 反面（例如"问答对被索引了但恰好与某条分段同分、顺序没变"）——
   * 所以两条都要有。
   */
  @Test
  void qaPairContentIsNotInTheFullTextIndex() {
    String kbId = insertKb();
    insertSegment(kbId, 0, "违约金按合同约定计算，具体见第二章");

    assertThat(hits(kbId, QA_ONLY_TERM)).as("前提：语料里没有任何一段含这个词").isEmpty();

    importQaPair(
        kbId,
        QA_ONLY_TERM + "是什么？",
        "这个词只在问答对的答案里出现：" + QA_ONLY_TERM,
        ClassificationLevel.INTERNAL);

    assertThat(hits(kbId, QA_ONLY_TERM))
        .as(
            "★★ 判据 ③：问答对的 question / answer 都没有进 content_tsv —— "
                + "本表没有 search_text、没有全文索引列，也没有任何地方把它的文字并进分段检索文本")
        .isEmpty();
  }

  // ────────────────────────── 断言 ③：库层形状（不是分段、不进分段并集） ──────────────────────────

  /**
   * 问答对不是分段：它的 id 在 {@code kb_segment} 里查不到。
   *
   * <p>★ 另一条可能的接入路径是"把问答对也插一份到 {@code kb_segment}"（这样两条检索路一行都不用改）。 那条路比改 SQL 更隐蔽 ——
   * 它<strong>不改任何查询</strong>，只多写一种数据。这条断言就是钉它的。
   */
  @Test
  void qaPairsAreNotSegments() {
    String kbId = insertKb();
    insertSegment(kbId, 0, "违约金按合同约定计算，具体见第二章");

    UUID qaId = importQaPair(kbId, "合同违约金怎么算？", "按日计收。", ClassificationLevel.INTERNAL);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from kb_segment where id = ?", Integer.class, qaId))
        .as("★ 问答对的 id 不许出现在 kb_segment 里 —— 那是'不改查询、只多写一种数据'那条更隐蔽的接入路径")
        .isZero();
  }

  /**
   * 导入问答对<strong>不改变任何库的分段数</strong>。
   *
   * <p>★ 这一条把"分段并集"这个更宽的形状也钉住：将来的检索候选来自"所有可见库的分段"，所以只要 问答对没有以任何形式进入 {@code kb_segment}，候选集就与它无关。
   */
  @Test
  void qaPairRowsDoNotEnterTheSegmentUnion() {
    String kbId = insertKb();
    insertSegment(kbId, 0, "违约金按合同约定计算，具体见第二章");

    long before = segmentCount(kbId);
    importQaPair(kbId, "合同违约金怎么算？", "按日计收。", ClassificationLevel.INTERNAL);

    assertThat(segmentCount(kbId)).as("导入问答对之后分段数必须一模一样（问答对是另一个聚合，不是分段的一种）").isEqualTo(before);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private List<UUID> hitIds(String kbId) {
    return hits(kbId, QUERY).stream().map(SegmentSearchPort.Scored::segmentId).toList();
  }

  /** 走<strong>真正的全文检索入口</strong>（{@code RetrievalService.retrieveOneQuery} 消费的就是它）。 */
  private List<SegmentSearchPort.Scored> hits(String kbId, String question) {
    return SecurityContexts.asClearance(
        ClassificationLevel.CONFIDENTIAL,
        () ->
            segmentSearch.fullText(
                new SegmentSearchPort.TextQuery(question, List.of(UUID.fromString(kbId)), 10)));
  }

  /**
   * 导入一条问答对，返回它的 id。
   *
   * <p>★ 用 {@code getFirst()} 而不是"从列表里找"：本批的 {@code importPairs} 承诺返回<strong>与传入同序</strong>的
   * 落库结果，所以第一条就是刚才那条 —— 而这里顺带把那个承诺当成前提用了一次。
   */
  private UUID importQaPair(
      String kbId, String question, String answer, ClassificationLevel classification) {
    String id =
        SecurityContexts.asUser(
                CURRENT_USER,
                ClassificationLevel.CONFIDENTIAL,
                () ->
                    qaPairs.importPairs(
                        UUID.fromString(kbId),
                        List.of(
                            new ImportPair(
                                question, answer, null, classification, QaPairOrigin.MANUAL, null)),
                        CURRENT_USER))
            .getFirst()
            .id();
    return UUID.fromString(id);
  }

  private long segmentCount(String kbId) {
    return jdbc.queryForObject(
        "select count(*) from kb_segment where kb_id = ?::uuid", Long.class, kbId);
  }

  private String insertKb() {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?::uuid, ?, '制度规范', 'LOCAL', ?::uuid, 'CONFIDENTIAL', ?::uuid,"
            + " 'PUBLIC', now(), now())",
        id,
        "问答对隔离夹具库-" + id.substring(0, 8),
        UUID.randomUUID().toString(),
        CURRENT_USER.toString());
    return id;
  }

  private void insertSegment(String kbId, int ordinal, String text) {
    String docId = UUID.randomUUID().toString();
    jdbc.update(
        // ★ 责任部门已改成 org_id（UUID 外键，V27）。用 ROOT 的**确定性** id（V25 种子写死的）：
        //   org_id 是外键 ⇒ 不能塞随机 UUID。见其它夹具的同一条注释（Issue #48）。
        "insert into kb_document (id, kb_id, scope_kind, scope_id, name, source, doc_type, org_id,"
            + " classification, status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, 'KB', ?::uuid, ?, 'local', '制度',"
            + " '00000000-0000-4000-8000-000000000001'::uuid, 'INTERNAL', 'PENDING_EMBEDDING', 0,"
            + " 0, true, now(), now())",
        docId,
        kbId,
        kbId,
        "问答对隔离夹具文档-" + docId.substring(0, 8));

    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, ?, '夹具', '第一章', ?, ?, ?, ?,"
            + " false, false, false, true, 'EMBEDDED', now(), now(),"
            // 密级从文档继承（V22 的列是 not null）：夹具也用同一条规则，别自己另造一份
            + " (select classification from kb_document where id = ?::uuid))",
        UUID.randomUUID().toString(),
        docId,
        kbId,
        kbId,
        ordinal,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
  }
}
