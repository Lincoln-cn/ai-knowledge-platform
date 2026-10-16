package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.application.QaPairService.ImportPair;
import cn.lincoln.aiplatform.knowledge.application.QaPairView;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.web.PageResult;
import cn.lincoln.aiplatform.support.DocumentFixture;
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
 * 问答对「进 → 列出」（Issue {@code #78} 判据 ②）—— 真 PG + Flyway，<strong>纯 SQL 夹具，不依赖桩服务</strong>。
 *
 * <h3>★ 这个类钉的是"留缝"本身，而不是某个功能</h3>
 *
 * <p>{@code #78} 拍的是「数据层留缝」：只建表 + <strong>服务层最小入口</strong>（导入 / 列出）。所以本类的断言全部落在 <strong>Java
 * 服务层的方法</strong>上，不走 HTTP —— 因为本批<strong>刻意不加端点、不改契约</strong>（理由见 {@code QaPairService}
 * 的类注释：契约闸门是双向的，登记了没有页面消费就是"零消费"的三缺一）。
 *
 * <h3>★ 判据 ② 要的"含归属与密级"，在这里是怎么验的</h3>
 *
 * <ul>
 *   <li><strong>归属</strong>：不只断言"存进去的 {@code kbId} 读得回来"，而是断言 <strong>{@link
 *       #listIsScopedToItsOwnKnowledgeBase()}</strong> —— 库 B 的问答对 <strong>不出现在</strong>库 A 的列表里。★
 *       这才是归属的用法（可见性继承库）， 只比对字段值的话，"把归属写对了但查询没按它筛"这种错照样绿。
 *   <li><strong>密级</strong>：断言两件事 —— ① 冗余列的排序值 {@code classification_rank} 与 Java 的 {@code
 *       ordinal()} 同序（这是将来检索谓词的绑定值口径，与 {@code ClassificationRankTest} 同一条判据， 只是这里落在问答对表上）；② 密级越
 *       ceiling 时<strong>导入被拒</strong>（{@code 13 §6}， 与文档建档同一条校验）。
 * </ul>
 *
 * <h3>★ 为什么必须有一条"迁移真的执行了"的读数</h3>
 *
 * <p>其余断言都建立在"表在"这个前提上，而那个前提在别的测试类里只是**隐式**的（Hibernate 的 {@code validate} 顺带证明了）。{@link
 * #migrationIsAppliedAfterExistingChain()} 把它变成一条<strong>能红的</strong>读数： 它同时钉住"V31 是接在现有链之后的"（判据
 * ①）与"V30 没有被碰坏"（本批一个字都不许改更早的迁移）。
 */
@SpringBootTest
@Testcontainers
class QaPairIngestionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 夹具里的"我"（建档人）。★ 用固定值而不是随机值：一条问答对的两个审计列都该是它，这可以断言。 */
  private static final UUID CURRENT_USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  /** 清一色用最高上限建夹具库 —— 除了那条刻意验"越 ceiling 被拒"的用例。 */
  private static final String CEILING = "CONFIDENTIAL";

  @Autowired JdbcTemplate jdbc;

  @Autowired QaPairService qaPairs;

  // ────────────────────────── 判据 ①：迁移接在现有链之后 ──────────────────────────

  /**
   * V31 已执行且成功，且 V30 <strong>仍在</strong>。
   *
   * <p>★ 两条一起断言的理由是它们坏掉的症状完全不同：V31 没跑 ⇒ 表根本不存在（整类起不来，看起来像"代码有问题"）； V30 被改坏 ⇒ Flyway checksum
   * 不匹配后端起不来（看起来像"环境有问题"）。分开说清，排查就只有一个方向。
   */
  @Test
  void migrationIsAppliedAfterExistingChain() {
    assertThat(successOf("31"))
        .as("Flyway 里没有 version=31 的成功记录 ⇒ knowledge/V31 没被执行（判据 ① 要求它接在现有链之后）")
        .isEqualTo(Boolean.TRUE);
    assertThat(successOf("30"))
        .as("V30（assistant 免登录凭证）的记录不见了 —— 本批不许改任何更早的迁移（改了 checksum 就对不上）")
        .isEqualTo(Boolean.TRUE);
  }

  // ────────────────────────── 判据 ②：进 → 列出（含归属与密级） ──────────────────────────

  /**
   * 导入两条（一条带出处、一条不带）⇒ 列出拿回同样两条，且归属 / 密级 / 来源 / 出处都对得上。
   *
   * <p>★ 两条夹具刻意一样一条：{@code segment_id} 是<strong>可空</strong>的（人工导入可以没有出处）， 而"可空列被写成空串 /
   * 0"是本项目踩过的那类静默错值 —— 所以两条都要有读数。
   */
  @Test
  void importThenListRoundTripsOwnershipAndClassification() {
    String kbId = insertVisibleKb(CEILING);
    String segmentId = insertSegment(kbId);

    List<QaPairView> imported =
        SecurityContexts.asUser(
            CURRENT_USER,
            ClassificationLevel.CONFIDENTIAL,
            () ->
                qaPairs.importPairs(
                    UUID.fromString(kbId),
                    List.of(
                        new ImportPair(
                            "差旅住宿标准是多少？",
                            "一线城市每人每天不超过 500 元。",
                            UUID.fromString(segmentId),
                            ClassificationLevel.INTERNAL,
                            QaPairOrigin.MANUAL,
                            "import-batch-2026-10-13"),
                        new ImportPair(
                            "年假怎么计算？",
                            "按工龄分档，详见《考勤制度》第四章。",
                            null,
                            ClassificationLevel.SECRET,
                            QaPairOrigin.CONVERSATION,
                            null)),
                    CURRENT_USER));

    assertThat(imported).as("导入的返回必须与传入同序、同条数 —— 否则调用方无法核对写进去的是什么").hasSize(2);

    PageResult<QaPairView> listed =
        SecurityContexts.asUser(
            CURRENT_USER,
            ClassificationLevel.CONFIDENTIAL,
            () -> qaPairs.list(UUID.fromString(kbId), 1, 20));

    assertThat(listed.total()).as("total 必须是过滤之后的数（不是表行数）").isEqualTo(2);
    assertThat(listed.list())
        .as("★ 「进 → 列出」的往返：归属（kbId）、密级、来源、出处四项都要一字不差")
        .extracting(
            QaPairView::question,
            QaPairView::answer,
            QaPairView::kbId,
            QaPairView::classification,
            QaPairView::origin,
            QaPairView::segmentId)
        .containsExactlyInAnyOrder(
            tuple(
                "差旅住宿标准是多少？",
                "一线城市每人每天不超过 500 元。",
                kbId,
                ClassificationLevel.INTERNAL,
                QaPairOrigin.MANUAL,
                segmentId),
            tuple(
                "年假怎么计算？",
                "按工龄分档，详见《考勤制度》第四章。",
                kbId,
                ClassificationLevel.SECRET,
                QaPairOrigin.CONVERSATION,
                null));

    // ── 密级：冗余列的排序值必须与 Java 的 ordinal() 同序 ──
    //    ★ 这是将来检索谓词（classification_rank <= ?）的绑定值口径。两边一旦不同序，
    //      后果不是报错，而是"某些人莫名看不到了"或更糟的"某些人莫名看得见了"。
    for (QaPairView view : listed.list()) {
      // ★ 两边都取 int 再比：库列是 smallint（映射成 Short），而 ordinal() 是 int ——
      //   拿 Short 与 Integer 直接比会落进断言库的重载歧义里（"看起来在比、实际比的是类型"）
      assertThat(view.classificationRank().intValue())
          .as("kb_qa_pair.classification_rank 必须等于 Java 的 ordinal（%s）", view.classification())
          .isEqualTo(view.classification().ordinal());
    }

    // ── 审计列：建档人必须是调用方传进来的那个（不是 null、也不是别处来的） ──
    assertThat(
            jdbc.queryForObject(
                "select count(*) from kb_qa_pair where kb_id = ?::uuid and created_by = ?::uuid",
                Integer.class,
                kbId,
                CURRENT_USER.toString()))
        .as("两条的 created_by 都应该是当前用户 —— 审计列为空等于事后无人可追")
        .isEqualTo(2);

    // ── 命中计数：本批恒 0（只有检索能写它，而检索侧不接） ──
    assertThat(
            jdbc.queryForObject(
                "select coalesce(sum(hit_count), 0) from kb_qa_pair where kb_id = ?::uuid",
                Long.class,
                kbId))
        .as("★ hit_count 本批恒 0：列已占位，但没有任何地方写它（10 §7.4 要的计数只能由检索产生）")
        .isZero();
  }

  /**
   * ★ 归属的<strong>用法</strong>：库 B 的问答对不出现在库 A 的列表里。
   *
   * <p>只断言"字段值存对了"是不够的 —— 那样"存对了但查询没按它筛"照样绿，而那种错的症状是 <strong>把一个库的问答对拿去回答另一个库的问题</strong>（一次静默的串库）。
   */
  @Test
  void listIsScopedToItsOwnKnowledgeBase() {
    String kbA = insertVisibleKb(CEILING);
    String kbB = insertVisibleKb(CEILING);

    SecurityContexts.asUser(
        CURRENT_USER,
        ClassificationLevel.CONFIDENTIAL,
        () -> qaPairs.importPairs(UUID.fromString(kbA), List.of(pair("A 库的问题")), CURRENT_USER));
    SecurityContexts.asUser(
        CURRENT_USER,
        ClassificationLevel.CONFIDENTIAL,
        () -> qaPairs.importPairs(UUID.fromString(kbB), List.of(pair("B 库的问题")), CURRENT_USER));

    PageResult<QaPairView> listA =
        SecurityContexts.asUser(
            CURRENT_USER,
            ClassificationLevel.CONFIDENTIAL,
            () -> qaPairs.list(UUID.fromString(kbA), 1, 20));

    assertThat(listA.total()).as("A 库只有 1 条").isEqualTo(1);
    assertThat(listA.list())
        .as("★ B 库的问答对不许出现在 A 库的列表里（归属继承知识库，07 §11.3）")
        .extracting(QaPairView::question)
        .containsExactly("A 库的问题");
  }

  /**
   * 密级越 ceiling ⇒ 整批被拒（{@code 13 §6}，与文档建档同一条校验）。
   *
   * <p>★ 这条判据的意义：问答对<strong>将来要进检索</strong>，所以它能进哪个库必须与文档一样受 ceiling 约束 ——
   * 少了它，一个"机密"级的问答对可以进一个"最高只允许公开"的库，那是一次<strong>跨密级越权</strong>， 而且在接入检索之前不会有人发现。
   */
  @Test
  void classificationAboveKbCeilingIsRejected() {
    String kbId = insertVisibleKb("INTERNAL");

    assertThatThrownBy(
            () ->
                SecurityContexts.asUser(
                    CURRENT_USER,
                    ClassificationLevel.CONFIDENTIAL,
                    () ->
                        qaPairs.importPairs(
                            UUID.fromString(kbId),
                            List.of(
                                new ImportPair(
                                    "机密问题",
                                    "机密答案",
                                    null,
                                    ClassificationLevel.CONFIDENTIAL,
                                    QaPairOrigin.MANUAL,
                                    null)),
                            CURRENT_USER)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("高于知识库的密级上限");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from kb_qa_pair where kb_id = ?::uuid", Integer.class, kbId))
        .as("★ 被拒的那一批不许留下任何行（一个事务：要么全进、要么一条都不进）")
        .isZero();
  }

  /**
   * 出处（{@code segmentId}）属于<strong>另一个库</strong> ⇒ 拒绝，且一行都不留。
   *
   * <p>★ 这条是"留缝"里最值钱的一条校验：归属错位<strong>不会在任何地方报错</strong> —— 库里的行长得很正常，
   * 症状要等到检索接入后才出现，而且看起来像"检索串了库"（拿一个库的问题去另一个库的语料里作答）。
   */
  @Test
  void segmentFromAnotherKnowledgeBaseIsRejected() {
    String kbA = insertVisibleKb(CEILING);
    String kbB = insertVisibleKb(CEILING);
    String segmentOfB = insertSegment(kbB);

    assertThatThrownBy(
            () ->
                SecurityContexts.asUser(
                    CURRENT_USER,
                    ClassificationLevel.CONFIDENTIAL,
                    () ->
                        qaPairs.importPairs(
                            UUID.fromString(kbA),
                            List.of(
                                new ImportPair(
                                    "A 库的问题",
                                    "答案",
                                    UUID.fromString(segmentOfB),
                                    ClassificationLevel.INTERNAL,
                                    QaPairOrigin.MANUAL,
                                    null)),
                            CURRENT_USER)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("属于另一个知识库");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from kb_qa_pair where kb_id = ?::uuid", Integer.class, kbA))
        .as("归属错位的那一批不许落库")
        .isZero();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private static ImportPair pair(String question) {
    return new ImportPair(
        question, question + "的答案", null, ClassificationLevel.INTERNAL, QaPairOrigin.MANUAL, null);
  }

  private Boolean successOf(String version) {
    List<Boolean> rows =
        jdbc.queryForList(
            "select success from flyway_schema_history where version = ?", Boolean.class, version);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 建一个当前用户<strong>可见</strong>的知识库。
   *
   * <p>★ 为什么 owner 是当前用户、{@code visibility} 取 {@code PUBLIC}：服务层第一步就是 {@code
   * KnowledgeBaseService#get}（可见性判定）。文档与分段的夹具（{@code ClassificationRankTest}）可以不管这个 ——
   * 它们只验库层事实；而本类<strong>专门走服务层</strong>，所以夹具必须让可见性判定通过。 数据范围走 {@code ALL}（{@code SecurityContexts}
   * 固定给 {@code scope=ALL}）⇒ 断言里不会混进"范围收窄"这一维。
   */
  private String insertVisibleKb(String maxClassification) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?::uuid, ?, '制度规范', 'LOCAL', ?::uuid, ?, ?::uuid, 'PUBLIC', now(), now())",
        id,
        "问答对夹具库-" + id.substring(0, 8),
        UUID.randomUUID().toString(),
        maxClassification,
        CURRENT_USER.toString());
    return id;
  }

  /** 建一个文档 + 一个分段，返回分段 id（出处校验要一个真实存在的 {@code kb_segment.id}）。 */
  private String insertSegment(String kbId) {
    // ★ `#48`：裸 SQL 收进了 `DocumentFixture`（全仓唯一一处写 kb_document 的地方）。
    String docId =
        DocumentFixture.insertPending(
                jdbc, kbId, "问答对夹具文档-" + UUID.randomUUID().toString().substring(0, 8), "INTERNAL")
            .toString();

    String segmentId = UUID.randomUUID().toString();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, ordinal, title,"
            + " title_path, content, search_text, content_hash, char_count, is_table, is_code,"
            + " oversized_risk, enabled, embedding_status, created_at, updated_at, classification)"
            + " values (?::uuid, ?::uuid, ?::uuid, 'KB', ?::uuid, 0, '夹具', '第一章', '正文', '正文', ?, 2,"
            + " false, false, false, true, 'EMBEDDED', now(), now(),"
            // 密级从文档继承（V22 的列是 not null）：夹具也用同一条规则，别自己另造一份
            + " (select classification from kb_document where id = ?::uuid))",
        segmentId,
        docId,
        kbId,
        kbId,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        docId);
    return segmentId;
  }
}
