package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 密级的排序口径与冗余一致性（第 11 周 · 0.3.3）—— 真 PG，纯 SQL 夹具（不需要桩服务）。
 *
 * <h3>为什么值得单独一个类：两个"漂了不会报错"的地方</h3>
 *
 * <ol>
 *   <li>★ <strong>排序口径</strong>：检索谓词写的是 {@code classification_rank <= ?}，而那个 {@code ?} 来自 Java 的
 *       {@code ClassificationLevel.ordinal()}。两边一旦不同序（比如有人在枚举中间插了一档），
 *       后果<strong>不是报错</strong>，而是"某些人莫名看不到了" 或更糟的"某些人莫名看得见了"。
 *   <li>★ <strong>冗余一致性</strong>：{@code kb_segment.classification} 是 {@code 13 §6.4}
 *       要求的冗余列（过滤要能在分段表上直接生效）。冗余的代价就是"两边可能不一致"， 所以它的代价那一栏必须有一条**能红的巡检**，而不是一句"记得同步"。
 * </ol>
 *
 * <h3>夹具为什么全是 SQL</h3>
 *
 * <p>本类不验接口、只验库层事实，所以不走 HTTP（{@code RetrievalSecurityTest} 已经覆盖了那条路）。 好处是它<strong>不依赖桩服务</strong>
 * —— 桩一死，服务级集成测试整类跳过，而口径漂移这件事不该跟着一起失效。
 */
@SpringBootTest
@Testcontainers
class ClassificationRankTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 与 {@code V13} / {@code V22} 里的 check 约束逐字一致（下面那条金丝雀要摘掉它再装回去）。 */
  private static final String DOC_CHECK =
      "check (classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL'))";

  @Autowired JdbcTemplate jdbc;

  // ────────────────────────── 判据：两列的 rank == Java 的 ordinal ──────────────────────────

  @ParameterizedTest
  @EnumSource(ClassificationLevel.class)
  void rankMatchesJavaOrdinal(ClassificationLevel level) {
    String kbId = insertKnowledgeBase();
    String docId = insertDocument(kbId, level.name());
    String segmentId = insertSegment(kbId, docId);

    assertThat(documentRank(docId))
        .as("kb_document.classification_rank 必须等于 Java 的 ordinal（%s）", level)
        .isEqualTo(level.ordinal());
    assertThat(segmentRank(segmentId))
        .as("kb_segment.classification_rank 必须等于 Java 的 ordinal（%s）", level)
        .isEqualTo(level.ordinal());
    assertThat(
            jdbc.queryForObject(
                "select classification from kb_segment where id = ?::uuid",
                String.class,
                segmentId))
        .as("分段密级必须继承文档（13 §2.1 ②）")
        .isEqualTo(level.name());
  }

  // ────────────────────────── 巡检：冗余列与文档一致 ──────────────────────────

  @Test
  void segmentClassificationNeverDriftsFromDocument() {
    for (ClassificationLevel level : ClassificationLevel.values()) {
      String kbId = insertKnowledgeBase();
      String docId = insertDocument(kbId, level.name());
      insertSegment(kbId, docId);
    }

    // zip 形式的 1:1 右表 —— 没有这个后缀，PG 默认用 INNER JOIN，索引匹配方式由统计信息随机决定
    Integer drift =
        jdbc.queryForObject(
            "select count(*) from kb_segment s join kb_document d on d.id = s.document_id"
                + " where s.classification is distinct from d.classification",
            Integer.class);

    assertThat(drift).as("分段密级与文档密级出现漂移（13 §6.4 冗余列的代价）").isZero();
  }

  // ────────────────────────── ★ 金丝雀：未知取值必须 fail-closed ──────────────────────────

  /**
   * 未知密级取值 ⇒ {@code rank = 99} ⇒ <strong>对所有人都不可见</strong>。
   *
   * <p>表上的 check 约束让这一支在正常路径下<strong>不可达</strong> —— 所以"它确实是 99"这件事 没有任何真实数据能证明。而它是一个安全分支：
   * 万一将来有人放宽了约束（或加了第五档却只改了 Java 枚举）， {@code else} 写成 {@code 0} 就是一次<strong>静默的全员可见</strong>。
   *
   * <p>所以这里<strong>临时摘掉约束</strong>把这一支逼出来。Testcontainers 的库是本类私有的、用完即弃， 摘约束不会污染别处；{@code finally}
   * 里再装回去。
   */
  @Test
  void unknownClassificationRanksAsFailClosed() {
    String kbId = insertKnowledgeBase();
    jdbc.execute("alter table kb_document drop constraint chk_doc_classification");
    try {
      String docId = insertDocument(kbId, "TOP_SECRET");
      assertThat(documentRank(docId))
          .as("没预料的密级取值必须落到 99（对所有人的 clearance 都 > 它）—— 落到 0 就是静默的全员可见")
          .isEqualTo(99);
    } finally {
      // ★ 先把那一行清掉再装约束：不清的话"装回去"会因既存行违规而失败，
      //   而那个失败会盖住上面真正的断言结果（第一次跑就踩到了）
      jdbc.update("delete from kb_document where classification = 'TOP_SECRET'");
      jdbc.execute("alter table kb_document add constraint chk_doc_classification " + DOC_CHECK);
    }
  }

  // ────────────────────────── 夹具（纯 SQL：本类不验接口，只验库层事实） ──────────────────────────

  private String insertKnowledgeBase() {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, created_at, updated_at)"
            + " values (?::uuid, ?, '制度规范', 'LOCAL', ?::uuid, 'CONFIDENTIAL', ?::uuid,"
            + " now(), now())",
        id,
        "密级口径-" + id.substring(0, 8),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString());
    return id;
  }

  private String insertDocument(String kbId, String classification) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_document (id, kb_id, name, source, doc_type, department, classification,"
            + " status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?::uuid, ?::uuid, ?, 'local', '制度', '采购部', ?, 'PENDING_EMBEDDING',"
            + " 0, 0, true, now(), now())",
        id,
        kbId,
        "密级口径文档-" + id.substring(0, 8),
        classification);
    return id;
  }

  /** 密级从文档继承 —— 走的就是生产路径那条规则（不是在本类里另造一份）。 */
  private String insertSegment(String kbId, String docId) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, ordinal, title, title_path, content,"
            + " search_text, content_hash, char_count, is_table, is_code, oversized_risk, enabled,"
            + " embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, 0, '夹具', '第一章', '正文', '正文', ?, 2,"
            + " false, false, false, true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?::uuid))",
        id,
        docId,
        kbId,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        docId);
    return id;
  }

  private int documentRank(String docId) {
    return jdbc.queryForObject(
        "select classification_rank from kb_document where id = ?::uuid", Integer.class, docId);
  }

  private int segmentRank(String segmentId) {
    return jdbc.queryForObject(
        "select classification_rank from kb_segment where id = ?::uuid", Integer.class, segmentId);
  }
}
