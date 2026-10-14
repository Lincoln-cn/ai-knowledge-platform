package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ {@code V34} 的**形状**（{@code 13 §11.3} / {@link
 * cn.lincoln.aiplatform.knowledge.domain.DocumentScopeKind}） —— 真 PG + 纯 SQL 夹具。
 *
 * <h3>它盯的是"形状说了、库没落"那类静默问题（{@code 13 §11.7} 判据 5 / 6）</h3>
 *
 * <ol>
 *   <li><strong>三条列真的在</strong>，且 {@code conversation_id} 是<strong>生成列</strong> （{@link
 *       #conversationIdIsDerivedFromScopeKindAndScopeId} 用"改 scope_id ⇒ 它跟着变"来证明，
 *       而不是"它存在"这种可以被写成普通列骗过的断言）；
 *   <li><strong>三条 CHECK 真的拦得住</strong>：{@link #scopeShapeChecksRejectSilentWrongDomains()} —— ★
 *       其中两条挡的是 {@code #31} 点名的"静默越权"（会话附件被落成库文档）；
 *   <li><strong>没有孤儿</strong>（{@code 13 §11.7} 判据 5 的巡检）： {@link
 *       #orphanPatrolReportsNothingAndProvesItCanGoRed()} —— ★ 巡检本身也要能红，
 *       所以它在同一条用例里**先造一个孤儿、再把它清掉**；
 *   <li>★ <strong>冗余列是载重的</strong>：{@link #redundantSegmentScopeIsTheOneThatFilters()} ——
 *       让分段的域与文档**故意不一致**，断言"检索按分段那一列判"。 这正是第二条金丝雀盯的形状（把 {@code s.} 换成 {@code d.} ⇒ 这条会红）。
 * </ol>
 *
 * <p>★ 夹具全是 SQL ⇒ 本类<strong>不依赖桩服务</strong>：形状闸门不该跟着桩一起失效。
 */
@SpringBootTest
@Testcontainers
class DocumentScopeShapeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** V25 种子写死的 ROOT 部门 id（{@code kb_document.org_id} 是外键 —— Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  private static final String QUERY = "合同违约金";

  @Autowired JdbcTemplate jdbc;

  @Autowired SegmentSearchPort segmentSearch;

  // ────────────────────────── ① 形状：三条列与生成列 ──────────────────────────

  /**
   * 三条列真的在，且口径与 {@code 13 §11.3} 的列映射表一致。
   *
   * <p>★ {@code conversation_id} 必须是 <strong>{@code is_generated = 'ALWAYS'}</strong>：
   * "它是一列"这件事骗不过这个读数，而"它是不是生成列"决定了它会不会变成**第二个真相**。
   */
  @Test
  void scopeColumnsExistOnBothTablesAndConversationIdIsGenerated() {
    for (String table : List.of("kb_document", "kb_segment")) {
      assertThat(columnKind(table, "scope_kind"))
          .as("%s.scope_kind 必须存在（13 §11.3 三支的第一列）", table)
          .isEqualTo("character varying");
      assertThat(columnKind(table, "scope_id"))
          .as("%s.scope_id 必须存在（归属主体）", table)
          .isEqualTo("uuid");
      assertThat(isNullable(table, "scope_id"))
          .as("%s.scope_id 必须 not null：域为空的行在检索侧恒假（谁都看不见），而那是静默的", table)
          .isFalse();
      assertThat(isGenerated(table, "conversation_id"))
          .as("%s.conversation_id 必须是**生成列**（GENERATED ALWAYS AS … STORED）：普通列 = 第二个真相", table)
          .isTrue();
      assertThat(isNullable(table, "kb_id"))
          .as("%s.kb_id 必须可空（会话临时没有库行，13 §11.6 迁移纪律②）", table)
          .isTrue();
    }
    assertThat(columnKind("kb_knowledge_base", "kind"))
        .as("kb_knowledge_base.kind 必须存在（个人库 = kind = PERSONAL 的那一行）")
        .isEqualTo("character varying");
  }

  /**
   * {@code conversation_id} 由 {@code scope_kind + scope_id} 唯一决定 —— 改 {@code scope_id} 它跟着变。
   *
   * <p>★ 这一条是"生成列"与"普通列"唯一可核对的分界：普通列在 {@code update scope_id} 之后**不会跟着动**， 而那时它就与 {@code scope_id}
   * 不一致了（第二个真相），且不报错。
   */
  @Test
  void conversationIdIsDerivedFromScopeKindAndScopeId() {
    UUID me = UUID.randomUUID();
    UUID conversationA = insertConversation(me, "会话 A");
    UUID conversationB = insertConversation(me, "会话 B");
    UUID kbId = insertKb(me);

    UUID kbDoc = insertDocument(kbId, "KB", kbId, me);
    UUID convDoc = insertDocument(null, "CONVERSATION", conversationA, me);

    assertThat(conversationIdOf("kb_document", kbDoc))
        .as("库域那一行：conversation_id 必须是空（CASE 的 else 分支）")
        .isNull();
    assertThat(conversationIdOf("kb_document", convDoc))
        .as("会话域那一行：conversation_id 必须等于 scope_id")
        .isEqualTo(conversationA);

    jdbc.update("update kb_document set scope_id = ? where id = ?", conversationB, convDoc);
    assertThat(conversationIdOf("kb_document", convDoc))
        .as("★ 改了 scope_id ⇒ 生成列必须跟着变（普通列不会 —— 那正是'第二个真相'）")
        .isEqualTo(conversationB);
  }

  /**
   * ★★ 三条 CHECK 真的拦得住 —— 逐条对应一种"不报错但归属错了"的写法。
   *
   * <p>★ 断言里看的是<strong>约束名</strong>而不是异常类型：类型只能说明"库拒绝了"， 约束名才能说明"是哪一条规则拒绝的"（写错了规则时，这一条会红在约束名上）。
   */
  @Test
  void scopeShapeChecksRejectSilentWrongDomains() {
    UUID me = UUID.randomUUID();
    UUID conversation = insertConversation(me, "会话");
    UUID kbId = insertKb(me);
    UUID otherKb = insertKb(me);

    assertViolates(
        "chk_doc_scope_shape",
        "库域但没有知识库（kb_id 为空）—— ★ 只写 kb_id = scope_id 是挡不住它的：SQL 的三值逻辑下 NULL = uuid 求值为 NULL，整条 CHECK"
            + " 判过",
        () -> insertDocumentRaw(null, "KB", kbId, me));

    assertViolates(
        "chk_doc_scope_shape",
        "库域但 kb_id 与 scope_id 不一致（第二处真相）",
        () -> insertDocumentRaw(otherKb, "KB", kbId, me));

    assertViolates(
        "chk_doc_scope_shape",
        "★ 会话域却挂着知识库 —— 这条挡的是 #31 点名的静默越权：附件会变成那个库的文档，凡能看那个库的人都检索得到",
        () -> insertDocumentRaw(kbId, "CONVERSATION", conversation, me));

    assertViolates(
        "chk_doc_conversation_owner",
        "★ 会话域没有传输者 —— 第三支谓词 owner_id = :me 会**恒假** ⇒ 会话附件对所有人都检索不到（静默失效）",
        () -> insertDocumentRaw(null, "CONVERSATION", conversation, null));

    assertViolates(
        "chk_doc_scope_kind",
        "没预料的域取值（将来加第五档却只改了 Java 枚举）",
        () -> insertDocumentRaw(null, "TEAM", conversation, me));
  }

  /**
   * ★ {@code kb_segment} 上那三条 CHECK 与文档侧同构 —— 逐条同样要拦得住。
   *
   * <p>★ 为什么分段侧也要验：检索过滤读的就是**分段**那一列（见 {@link
   * #redundantSegmentScopeIsTheOneThatFilters()}），所以"分段被落成错误的域"比文档侧更直接 —— 会话附件若被落成 {@code
   * scope_kind = 'KB'} 且挂着那个库的 id，它会立刻变成那个库的文档。
   */
  @Test
  void segmentScopeChecksRejectSilentWrongDomains() {
    UUID me = UUID.randomUUID();
    UUID conversation = insertConversation(me, "会话");
    UUID kbId = insertKb(me);
    UUID otherKb = insertKb(me);
    UUID docId = insertDocument(null, "CONVERSATION", conversation, me);

    assertViolates(
        "chk_segment_scope_shape",
        "库域但 kb_id 与 scope_id 不一致（冗余列被写成两个真相）",
        () -> insertSegmentRaw(docId, otherKb, "KB", kbId, me, "合同违约金 坏行", 0));

    assertViolates(
        "chk_segment_scope_shape",
        "★ 会话域的分段却挂着知识库 —— 附件会变成那个库的文档（静默越权）",
        () -> insertSegmentRaw(docId, kbId, "CONVERSATION", conversation, me, "合同违约金 坏行", 0));

    assertViolates(
        "chk_segment_conversation_owner",
        "★ 会话域的分段没有传输者 ⇒ 第三支谓词恒假 ⇒ 附件谁都检索不到（静默失效）",
        () -> insertSegmentRaw(docId, null, "CONVERSATION", conversation, null, "合同违约金 坏行", 0));
  }

  // ────────────────────────── ② 巡检：没有孤儿 + 冗余不漂移 ──────────────────────────

  /**
   * ★ {@code 13 §11.7} 判据 5 的巡检：{@code scope_kind = 'CONVERSATION'} 的行必须能反查到会话； KB
   * 域的行必须能反查到库；分段的域必须与文档一致。
   *
   * <p>★★ <strong>巡检本身也要能红</strong>：所以这条用例先断言"当前是 0"， 再**故意造一个孤儿**（会话域 + 一个不存在的会话 id ⇒ 没有外键拦它，这正是
   * D14 说的那一半） 并断言"按这个 id 查得到 1"，最后清掉。 只断言"等于 0"的巡检与"没写"没有区别 —— 那是本项目最贵的一类假绿（{@code #31} 判据 5
   * 的原话是"谓词永远为真**不会报错**"）。
   */
  @Test
  void orphanPatrolReportsNothingAndProvesItCanGoRed() {
    assertThat(orphanConversationDocuments(null))
        .as("会话域的行必须能反查到会话（多态引用没有外键，所以只能靠这条巡检 —— D14）")
        .isZero();
    assertThat(orphanKbDocuments())
        .as("库域的行必须能反查到库（kb_id = scope_id 且 kb_id 带外键 ⇒ 库层就保住了）")
        .isZero();
    assertThat(scopeDriftBetweenSegmentAndDocument())
        .as("分段的域必须与它的文档一致（13 §6.4 冗余列的代价，与'文档密级 ≠ 分段密级'同构）")
        .isZero();

    UUID ghost = UUID.randomUUID();
    UUID me = UUID.randomUUID();
    UUID planted = insertDocument(null, "CONVERSATION", ghost, me);
    try {
      assertThat(orphanConversationDocuments(planted))
          .as("★ 反证：造一个孤儿（scope_id 指向不存在的会话）⇒ 巡检必须报得出来，否则它是一条永远为真的规则")
          .isEqualTo(1);
    } finally {
      jdbc.update("delete from kb_document where id = ?", planted);
    }
  }

  // ────────────────────────── ③ 冗余列是载重的（金丝雀②的形状） ──────────────────────────

  /**
   * ★★ 让**分段的域与文档的域不一致**，断言"检索按分段那一列判"。
   *
   * <p>★ 为什么这条用例就是第二条金丝雀盯的形状（{@code 13 §6.4} 的结构论证）： 域过滤必须能在 {@code kb_segment} 上直接生效 —— 若有人把谓词改成读
   * {@code d.scope_kind/d.scope_id} （"每组一次 join 文档就完事"），那么下面两条断言会<strong>整体对调</strong>： 文档说"会话
   * A"而分段说"会话 B"的那一行，会出现在会话 A 的检索结果里（泄露）， 而会话 B 检索不到它（少看见）。
   *
   * <p>★ 它同时说明"冗余列的代价由巡检兜"：漂移会被 {@link #scopeDriftBetweenSegmentAndDocument()} 报出来。
   */
  @Test
  void redundantSegmentScopeIsTheOneThatFilters() {
    UUID me = UUID.randomUUID();
    UUID conversationA = insertConversation(me, "会话 A");
    UUID conversationB = insertConversation(me, "会话 B");
    UUID kbId = insertKb(me);

    // 文档说"会话 A"，而它的分段（冗余列）说"会话 B" —— 只可能由旁路写入造成（写侧从文档继承）
    UUID docId = insertDocument(null, "CONVERSATION", conversationA, me);
    // ★ 两条分段必须放在 try 里：第一条若插入成功、第二条失败，finally 仍要能把它们清掉 ——
    //   否则残留的漂移行会让本类另一条"巡检必须为 0"的用例红在别处（测试之间串味）
    try {
      UUID drifted =
          insertSegmentRaw(docId, null, "CONVERSATION", conversationB, me, "合同违约金 漂移行", 0);
      UUID honest =
          insertSegmentRaw(docId, null, "CONVERSATION", conversationA, me, "合同违约金 一致行", 1);
      assertThat(hitIds(new RetrievalScope(List.of(kbId), null, conversationA, me)))
          .as("★ 检索按**分段**的域判：分段说会话 A 的那条在，说会话 B 的不在（改读 d. 列 ⇒ 这条会红）")
          .containsExactly(honest);
      assertThat(hitIds(new RetrievalScope(List.of(kbId), null, conversationB, me)))
          .as("★ 另一面：分段说会话 B 的那条只出现在会话 B 的结果里")
          .containsExactly(drifted);
      assertThat(scopeDriftBetweenSegmentAndDocument())
          .as("冗余列的代价必须由巡检兜住：这一行正是'文档说 A、分段说 B'")
          .isEqualTo(1);
    } finally {
      jdbc.update("delete from kb_segment where document_id = ?", docId);
      jdbc.update("delete from kb_document where id = ?", docId);
    }
  }

  // ────────────────────────── ④ 谓词只有一处（grep 级判据） ──────────────────────────

  /**
   * ★★ "一个谓词，不是三个分支"以及"谓词只有一处"的**可核对**版本（{@code 13 §11.3} 的判据载体）。
   *
   * <p>它扫的是 {@code src/main/java} 里两段**只可能属于那一个谓词**的 SQL 字面量：
   *
   * <ul>
   *   <li>{@code " and s.classification_rank <= ?"}（密级那一半，带引号所以注释里的引用不算）；
   *   <li>{@code s.scope_kind = 'KB' and s.scope_id in (}（共用库那一支）。
   * </ul>
   *
   * <p>★ 两条都必须只出现在{@code JdbcSegmentSearchQuery.java} 里，而且那个文件里 {@code
   * securityPredicate(scope.sharedKbIds().size())} 必须恰好出现 4 次 —— 四条检索路（全文 / 向量 / 候选复判 / 显式密级列举）各一次。
   * <strong>少一次</strong>就说明有一条路自己拼了条件（"只给一路加过滤条件"那个病）。
   */
  @Test
  void securityPredicateIsBuiltInExactlyOnePlace() {
    Path mainJava = resolveMainJavaDir();
    String predicateFile = "JdbcSegmentSearchQuery.java";
    List<String> classificationPredicateFiles =
        filesContaining(mainJava, "\" and s.classification_rank <= ?\"");
    List<String> domainPredicateFiles =
        filesContaining(mainJava, "s.scope_kind = 'KB' and s.scope_id in (");

    assertThat(classificationPredicateFiles)
        .as("密级谓词的 SQL 字面量只许出现在一处（13 §三 闸门②：两处写法迟早会漂）")
        .containsExactly(predicateFile);
    assertThat(domainPredicateFiles)
        .as("域谓词的 SQL 字面量只许出现在一处（13 §11.3：一个谓词，不是三个分支）")
        .containsExactly(predicateFile);

    String source =
        read(
            mainJava
                .resolve("cn/lincoln/aiplatform/knowledge/infrastructure")
                .resolve(predicateFile));
    int callSites = countOccurrences(source, "securityPredicate(scope.sharedKbIds().size())");
    assertThat(callSites)
        .as(
            "四条检索路都要走同一个构建点（全文 / 向量 / 候选复判 / 显式密级列举）：实际 %d 处。"
                + "少一处就说明那条路自己拼了一套条件 —— '只给一路加过滤条件'正是 13 §三 点名的静默漏洞",
            callSites)
        .isEqualTo(4);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private Object columnKind(String table, String column) {
    return jdbc.queryForObject(
        "select data_type from information_schema.columns where table_name = ? and column_name = ?",
        String.class,
        table,
        column);
  }

  private boolean isNullable(String table, String column) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select is_nullable = 'YES' from information_schema.columns"
                + " where table_name = ? and column_name = ?",
            Boolean.class,
            table,
            column));
  }

  private boolean isGenerated(String table, String column) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select is_generated = 'ALWAYS' from information_schema.columns"
                + " where table_name = ? and column_name = ?",
            Boolean.class,
            table,
            column));
  }

  private UUID conversationIdOf(String table, UUID id) {
    return jdbc.queryForObject(
        "select conversation_id from " + table + " where id = ?", UUID.class, id);
  }

  /**
   * 孤儿巡检：会话域的行反查不到会话。
   *
   * @param onlyDocumentId 只看这一行（{@code null} = 全表）—— ★ 给"能红"那条反证用： 它要断言的是"我自己造的那一行被报出来了"，而不是"全表恰好为
   *     1"（后者会被别的用例污染）
   */
  private int orphanConversationDocuments(UUID onlyDocumentId) {
    String scope = onlyDocumentId == null ? "" : " and d.id = '" + onlyDocumentId + "'::uuid";
    return jdbc.queryForObject(
        "select count(*) from kb_document d"
            + " where d.scope_kind = 'CONVERSATION'"
            + " and not exists (select 1 from cv_conversation c where c.id = d.scope_id)"
            + scope,
        Integer.class);
  }

  private int orphanKbDocuments() {
    return jdbc.queryForObject(
        "select count(*) from kb_document d"
            + " where d.scope_kind = 'KB'"
            + " and not exists (select 1 from kb_knowledge_base k where k.id = d.scope_id)",
        Integer.class);
  }

  /** 冗余列巡检：分段的域与文档不一致（与 `ClassificationRankTest` 的密级漂移巡检同构）。 */
  private int scopeDriftBetweenSegmentAndDocument() {
    return jdbc.queryForObject(
        "select count(*) from kb_segment s join kb_document d on d.id = s.document_id"
            + " where s.scope_kind is distinct from d.scope_kind"
            + " or s.scope_id is distinct from d.scope_id"
            + " or s.owner_id is distinct from d.owner_id",
        Integer.class);
  }

  private void assertViolates(String constraint, String why, Runnable insert) {
    assertThatThrownBy(insert::run)
        .as("库层必须拒绝：%s", why)
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(constraint);
  }

  private List<UUID> hitIds(RetrievalScope scope) {
    return SecurityContexts.asClearance(
            ClassificationLevel.CONFIDENTIAL,
            () -> segmentSearch.fullText(new SegmentSearchPort.TextQuery(QUERY, scope, 20)))
        .stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .toList();
  }

  private UUID insertKb(UUID owner) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, now(), now())",
        id,
        "形状夹具库-" + id.toString().substring(0, 8),
        UUID.randomUUID(),
        owner);
    return id;
  }

  private UUID insertConversation(UUID owner, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into cv_conversation (id, user_id, title, created_at, updated_at)"
            + " values (?, ?, ?, now(), now())",
        id,
        owner,
        title);
    return id;
  }

  private UUID insertDocument(UUID kbId, String scopeKind, UUID scopeId, UUID owner) {
    UUID id = UUID.randomUUID();
    // ★ 走的就是生产写侧的形状（kb_id 与 scope_id 在库域一致、会话域 kb_id 为空）
    insertDocumentRaw(kbId, scopeKind, scopeId, owner, id);
    return id;
  }

  private void insertDocumentRaw(UUID kbId, String scopeKind, UUID scopeId, UUID owner) {
    insertDocumentRaw(kbId, scopeKind, scopeId, owner, UUID.randomUUID());
  }

  private void insertDocumentRaw(UUID kbId, String scopeKind, UUID scopeId, UUID owner, UUID id) {
    jdbc.update(
        "insert into kb_document (id, kb_id, scope_kind, scope_id, owner_id, name, source,"
            + " doc_type, org_id, classification, status, char_count, segment_count, enabled,"
            + " created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?, 'local', '制度', ?, 'INTERNAL',"
            + " 'PENDING_EMBEDDING', 0, 0, true, now(), now())",
        id,
        kbId,
        scopeKind,
        scopeId,
        owner,
        "形状夹具文档-" + id.toString().substring(0, 8),
        UUID.fromString(ROOT_ORG));
  }

  private UUID insertSegmentRaw(
      UUID docId, UUID kbId, String scopeKind, UUID scopeId, UUID owner, String text, int ordinal) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, scope_kind, scope_id, owner_id, ordinal,"
            + " title, title_path, content, search_text, content_hash, char_count, is_table,"
            + " is_code, oversized_risk, enabled, embedding_status, created_at, updated_at,"
            + " classification)"
            + " values (?, ?, ?, ?, ?, ?, ?, '夹具', '第一章', ?, ?, ?, ?, false, false, false,"
            + " true, 'EMBEDDED', now(), now(),"
            + " (select classification from kb_document where id = ?))",
        id,
        docId,
        kbId,
        scopeKind,
        scopeId,
        owner,
        ordinal,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
    return id;
  }

  // ────────────────────────── 源码扫查（"谓词只有一处"的 grep 级判据） ──────────────────────────

  /**
   * 定位 {@code src/main/java}。
   *
   * <p>★ Surefire 的工作目录是 {@code backend/}，但本地直接跑 / IDE 里可能是项目根 ⇒ 两个候选都试；
   * <strong>找不到必须失败</strong>：静默跳过会让这条判据永远绿而什么都不查（与 {@code ContractDriftTest} 定位前端契约目录同一条口径）。
   */
  private static Path resolveMainJavaDir() {
    Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    for (Path candidate :
        List.of(
            userDir.resolve("src/main/java"),
            userDir.resolve("backend/src/main/java"),
            userDir.resolve("../backend/src/main/java"))) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized)) {
        return normalized;
      }
    }
    throw new AssertionError("找不到 src/main/java（user.dir=" + userDir + "）—— 这条判据不能静默跳过");
  }

  private static List<String> filesContaining(Path root, String needle) {
    List<String> hits = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (read(file).contains(needle)) {
          hits.add(file.getFileName().toString());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return hits;
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int at = text.indexOf(needle);
    while (at >= 0) {
      count++;
      at = text.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
