package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.DocumentFixture;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 密级标签的过滤口径（Issue {@code #26}）—— 真 PG，纯 SQL 夹具（不需要桩服务）。
 *
 * <h3>它守的是什么</h3>
 *
 * <p>{@code 13 §1.1} 把「等级链」与「标签」定成两条<strong>正交</strong>的轴：等级比的是
 * <em>不高于</em>（偏序格），标签比的是<em>相交</em>（集合）。前者写错会漏，后者写错会<strong>反向</strong>地漏 ——
 * 而"反向"这一半没有任何报错，也没有任何页面会显得不对，只会让某些人看见不该看的东西。
 *
 * <h3>今天两列恒为 NULL，那这几条测试在验什么</h3>
 *
 * <p>{@code V57} 只落了列、没有写侧（建档时不填标签），所以生产数据里 {@code labels} 全是 NULL ⇒ 谓词恒真、行为零变化。⭐
 * 这个"零变化"本身要被钉住（{@link #documentsWithoutLabelsStayVisibleToEveryone}），
 * 否则将来真开始写标签时，没人能判断"看不见了"是标签生效还是谓词写错。
 *
 * <p>而其余三条验的是<strong>语义已经接上了</strong>：写侧一旦开始填值，过滤立刻按 {@code 13 §1.1} 的口径生效，
 * 不需要再改检索代码。这正是本项目反复吃的那个亏的反面 —— {@code #40} / {@code #160} / {@code #228} 都是"东西写进去了，但没人按它判断"。
 *
 * <h3>为什么走 {@code SegmentSearchPort} 而不是 HTTP</h3>
 *
 * <p>与 {@code RetrievalDomainScopeTest} 同一条理由：它就是检索链路消费的那个端口，也是标签谓词 <strong>唯一</strong>的实现点。走 HTTP
 * 要先有完整的建库建档流程，而本类要造的是产品路径暂时 <strong>还产生不了</strong>的形状（带标签的文档）。
 */
@SpringBootTest
@Testcontainers
class SegmentLabelFilterTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V25 种子写死的 ROOT 部门 id（{@code kb_document.org_id} 是外键，不能塞随机 UUID —— Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  /** 三段语料都含它 ⇒ 一次查询能同时拿到三条，"谁进来了"一目了然。 */
  private static final String QUERY = "财务报销标准";

  private static final int LIMIT = 20;

  private static final String FINANCE = "财务资料";
  private static final String HR = "人事资料";

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  private UUID me;
  private UUID kb;
  private UUID segFinance;
  private UUID segHr;
  private UUID segNoLabel;

  @BeforeEach
  void seed() {
    me = UUID.randomUUID();
    kb = insertKb();
    segFinance = insertSegment("财务报销标准：差旅报销上限", new String[] {FINANCE});
    segHr = insertSegment("财务报销标准：入职体检费用报销", new String[] {HR});
    segNoLabel = insertSegment("财务报销标准：公共条款（未打标签）", null);
  }

  // ────────────────────────── ① 没配标签的角色：不受标签限制 ──────────────────────────

  /**
   * ★★ {@code 13 §1.1} 的口径：角色没有标签 <strong>= 不按标签限制</strong>，不是"什么都看不到"。
   *
   * <p>这一条是把"必须相交"那个写法挡住的地方：它看起来更"严"，实际后果是**绝大多数角色检索结果全空** —— 而没人会给系统报 bug，只会说"这个知识库好像没内容"。
   */
  @Test
  void roleWithoutLabelsIsNotRestrictedByLabels() {
    assertThat(hits(Set.of()))
        .as("★ 空标签集 = 不按标签限制：三条都该出现（否则没配标签的角色看不到任何东西）")
        .containsExactlyInAnyOrder(segFinance, segHr, segNoLabel);
  }

  // ────────────────────────── ② 带标签的角色：相交才可见 ──────────────────────────

  /**
   * 角色带 {@code 财务资料} ⇒ 只命中「打财务标签」与「没打标签」的，**不命中**「打人事标签」的。
   *
   * <p>★ 后半（没打标签的仍然可见）是刻意对齐 {@code ScopeFilter.canSeeClassification} 的第 ② 条豁免： 标签是可选字段，没打标签不是"绝密"。
   */
  @Test
  void roleWithLabelsOnlySeesIntersectingDocuments() {
    assertThat(hits(Set.of(FINANCE)))
        .as("★ 只有标签相交的才可见；未打标签的仍可见；打了别的标签的不可见")
        .containsExactlyInAnyOrder(segFinance, segNoLabel)
        .doesNotContain(segHr);
  }

  /** 两个标签的角色：两边的文档都要命中（并集语义来自「相交」而不是「全部包含」）。 */
  @Test
  void roleWithTwoLabelsSeesBothFamilies() {
    assertThat(hits(Set.of(FINANCE, HR)))
        .as("★ 两个标签 ⇒ 两个标签族都是相交的 ⇒ 两条都在")
        .containsExactlyInAnyOrder(segFinance, segHr, segNoLabel);
  }

  // ────────────────────────── ③ 谓词读的是分段那一列 ──────────────────────────

  /**
   * ★★ 过滤发生在 {@code kb_segment.labels} 上，不在文档那一列。
   *
   * <p>为什么必须是分段列（与 {@code classification} 同一个理由，{@code 13 §6.4}）：{@code content_tsv} 与 {@code
   * kb_id} 都在分段表上，两条检索路都在分段表上过滤。谓词只长在文档表上时， "两路同谓词"立刻退化成"两处 join 写法必须一致"，而那种一致没人能保证。
   *
   * <p>★ 这条用例造的是<strong>冗余列漂移</strong>的形状：文档打了财务标签、分段没打。 今天写侧还没有（{@code V57} 只落列），所以这种漂移不可能由产品产生 ——
   * 一旦写侧接上， 它会变成一条真实的巡检缺口（本类只钉住"过滤按分段列算"，巡检另记）。
   */
  @Test
  void filteringUsesTheSegmentColumnNotTheDocumentColumn() {
    jdbc.update(
        "update kb_document set labels = (?::text[]) where id = (select document_id from kb_segment"
            + " where id = ?)",
        sqlArray(new String[] {HR}),
        segNoLabel);

    assertThat(hits(Set.of(FINANCE)))
        .as("★ 文档标签是「人事」而分段没打标签 ⇒ 按分段列判定 ⇒ 它仍然可见（若按文档列判定，这条会消失）")
        .contains(segNoLabel);
  }

  // ────────────────────────── ④ 零变化：没打标签的一律可见 ──────────────────────────

  /**
   * 今天（写侧未接）所有文档的 {@code labels} 都是 NULL ⇒ 任何带标签的角色都看得到它们。
   *
   * <p>★ 这条是 {@link #roleWithLabelsOnlySeesIntersectingDocuments} 的对照组：它保证"看不见"永远
   * 只能由<strong>另一个标签</strong>造成，而不是由"标签这一维恰好还没数据"造成。少了它， 上面那条在"谓词写反、结果全空"的情况下也会绿。
   */
  @Test
  void documentsWithoutLabelsStayVisibleToEveryone() {
    UUID plain = insertSegment("财务报销标准：另一份未打标签的材料", null);

    assertThat(hits(Set.of(FINANCE)))
        .as("★ 未打标签的文档对任何角色都可见（标签是可选字段，不是密级）")
        .contains(plain, segNoLabel);
  }

  // ────────────────────────── 夹具（纯 SQL） ──────────────────────────

  private List<UUID> hits(Set<String> labels) {
    return SecurityContexts.asUserWithLabels(
            me,
            ClassificationLevel.CONFIDENTIAL,
            labels,
            () ->
                segmentSearch.fullText(
                    new SegmentSearchPort.TextQuery(
                        QUERY, new RetrievalScope(List.of(kb), null, null, me), LIMIT)))
        .stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .toList();
  }

  private UUID insertKb() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, kind, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at) values (?, ?,"
            + " 'SHARED', '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, 'PUBLIC', now(), now())",
        id,
        "标签口径-" + id.toString().substring(0, 8),
        UUID.randomUUID(),
        me);
    return id;
  }

  /** 一份文档 + 一段分段；{@code labels} 同时写进文档与分段（产品将来该有的写侧形状）。 */
  private UUID insertSegment(String text, String[] labels) {
    UUID docId =
        DocumentFixture.insertWithScope(
            jdbc,
            UUID.randomUUID(),
            kb,
            "KB",
            kb,
            me,
            "标签文档-" + UUID.randomUUID().toString().substring(0, 8),
            ROOT_ORG,
            "INTERNAL");
    if (labels != null) {
      jdbc.update(
          "update kb_document set labels = (?::text[]) where id = ?", sqlArray(labels), docId);
    }

    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, owner_id, ordinal,"
            + " title, title_path, content, search_text, content_hash, char_count, is_table,"
            + " is_code, oversized_risk, enabled, embedding_status, created_at, updated_at,"
            + " classification, labels)"
            + " values (?, ?::uuid, ?, 'KB', ?, ?, 0, '夹具', '第一章', ?, ?, ?, ?, false, false, false,"
            + " true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid), (?::text[]))",
        segmentId,
        docId.toString(),
        kb,
        kb,
        me,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId.toString(),
        labels == null ? null : sqlArray(labels));
    return segmentId;
  }

  /** PG 的数组字面量（只用于夹具；生产代码走 JDBC 的数组参数，见 {@code JdbcSegmentSearchQuery.labelArray()}）。 */
  private static String sqlArray(String[] values) {
    StringBuilder builder = new StringBuilder("{");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append('"').append(values[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return builder.append('}').toString();
  }
}
