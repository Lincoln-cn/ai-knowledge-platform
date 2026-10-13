package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.assistant.application.AccessCredentialService;
import cn.lincoln.aiplatform.assistant.application.AccessCredentialView;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
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
 * 判据 ②③ 的<strong>真栈</strong>读数（第 16 周 · 0.4.4）—— 真 PG + 真 identity 适配器 + 真迁移（V30）。
 *
 * <h3>为什么必须有这一类（而不是只有 {@code AccessCredentialTest}）</h3>
 *
 * <p>{@code AccessCredentialTest} 用手写假实现验"服务层有没有把冻结值用上"， 它<strong>验不了</strong>三件真栈才有的事：
 *
 * <ol>
 *   <li><strong>SQL 里的密级谓词</strong>：{@code visibleSegments} 用的必须是 {@code JdbcSegmentSearchQuery}
 *       里<strong>同一份</strong> {@code SECURITY_PREDICATE} （{@code 13 §三} 闸门②：只给一路加条件就是静默漏洞）；
 *   <li><strong>V30 迁移真的建出了那两列</strong>（{@code max_classification} 与 {@code revoked_at}） 且 {@code
 *       ddl-auto=validate} 认可实体与表一致；
 *   <li><strong>{@code AccessScopeAdapter.asScope} 真的把冻结密级装上了</strong> —— 假的 {@code AccessScope}
 *       替身不做那件事，所以"收窄方向取反了也能跑"这类错误只有真适配器能抓。
 * </ol>
 *
 * <h3>夹具怎么造（为什么不走 HTTP）</h3>
 *
 * <p>直接 JDBC 建应用 / 库 / 文档 / 分段，再用 {@link SecurityContexts#asClearance} 装一个"归属人已登录"
 * 的上下文调服务层。理由：本类要验的是<strong>过滤</strong>，不是建档链路 —— 走 HTTP 会把"模型加载" 这类无关前置一起拖进来。★
 * 密级<strong>从文档继承到分段</strong>（{@code V22} 的列是 {@code not null}）， 夹具走的是真的继承路径，不写死分段密级。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class AccessCredentialFilteringTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000f1");

  /** V25 种子写死的 ROOT 部门（{@code org_id} 是外键，不能塞随机 UUID —— 见 Issue #48）。 */
  private static final String ROOT_ORG = "00000000-0000-4000-8000-000000000001";

  @Autowired JdbcTemplate jdbc;

  @Autowired AccessCredentialService service;

  private UUID lastAppId;

  @AfterEach
  void cleanUp() {
    // ★ 只清本类造的：容器是共享的（Spring 上下文缓存），全表 delete 会打到别的测试类留下的夹具
    if (lastAppId != null) {
      jdbc.update(
          "delete from ai_model where id = (select model_id from as_assistant where id = ?)",
          lastAppId);
      // ★ as_access_credential 与 as_assistant_kb 都是 on delete cascade ⇒ 删应用即连带清掉，不必单独删
      jdbc.update("delete from as_assistant where id = ?", lastAppId);
    }
    // ★★ 删除顺序必须"从下往上"：kb_segment 引用 kb_document、kb_document 引用 kb_knowledge_base。
    //   直接删库会在真栈上炸 DataIntegrityViolation（`kb_document_kb_id_fkey`）——
    //   ★ 这正是 CI 抓出来的：本机 Docker 未运行 ⇒ 这一类真栈测试在本机根本没跑起来，
    //     "写好的测试"与"跑过的测试"是两件事（本项目对这条极敏感）。
    jdbc.update(
        "delete from kb_segment where kb_id in"
            + " (select id from kb_knowledge_base where owner_id = ?)",
        OWNER);
    jdbc.update(
        "delete from kb_document where kb_id in"
            + " (select id from kb_knowledge_base where owner_id = ?)",
        OWNER);
    jdbc.update("delete from kb_knowledge_base where owner_id = ?", OWNER);
    lastAppId = null;
  }

  // ────────────────────────── 判据 ②：创建时冻结（真落库值）──────────────────────────

  /**
   * ★ 冻结值真的落进 {@code as_access_credential.max_classification}，且它来自**请求者的 clearance**。
   *
   * <p>上下文装的是 {@code INTERNAL} ⇒ 断言库里那一行就是 {@code INTERNAL}（不是更高、也不是默认）。
   */
  @Test
  void frozenClassificationIsPersistedFromRequesterClearance() {
    Fixture f = seed();

    AccessCredentialView created =
        SecurityContexts.asUser(
            OWNER, ClassificationLevel.INTERNAL, () -> service.create(f.appId(), null, OWNER));

    String stored =
        jdbc.queryForObject(
            "select max_classification from as_access_credential where id = ?",
            String.class,
            created.id());

    assertThat(stored).as("★ 库里那一位必须是创建者当时的 clearance（13 §5.2 第①级：创建时冻结）").isEqualTo("INTERNAL");
    assertThat(created.maxClassification()).isEqualTo("INTERNAL");
  }

  // ────────────────────────── 判据 ②/③：升密 ⇒ 同一条 token 的可见集合收窄 ──────────────────────────

  /**
   * ★★ 判据 ② 的后半 + 判据 ③：<strong>升密后原链接失效</strong>，且<strong>每次访问都重新过滤</strong>。
   *
   * <p>三次访问同一条 token：
   *
   * <ol>
   *   <li>升密前：两条都看得见（冻结 INTERNAL ⊇ PUBLIC + INTERNAL）；
   *   <li>把其中一条抬到 SECRET ⇒ 第二次访问<strong>收窄成一条</strong>；
   *   <li>再全部抬到 CONFIDENTIAL ⇒ 第三次访问<strong>为空</strong>。
   * </ol>
   *
   * <p>★ 第 ② 步证明"升密 ⇒ 原链接不再返回那部分内容"；第 ③ 步证明"不是复用创建时算出来的结果" —— 复用的话它仍会返回第一次那两条。★
   * 这就是金丝雀要破坏的那一条判据的<strong>真栈读数</strong>。
   */
  @Test
  void sameTokenSeesLessAfterContentIsUpgraded() {
    Fixture f = seed();

    SecurityContexts.asUser(
        OWNER,
        ClassificationLevel.INTERNAL,
        () -> {
          AccessCredentialView credential = service.create(f.appId(), null, OWNER);

          assertThat(visible(f.appId(), credential.token()))
              .as("升密前：PUBLIC 与 INTERNAL 两条都在冻结值 INTERNAL 之内")
              .containsExactlyInAnyOrder(f.publicSegment(), f.internalSegment());

          // ① 把 INTERNAL 那条抬到 SECRET（高于冻结值）
          setClassification(f.internalSegment(), ClassificationLevel.SECRET);
          assertThat(visible(f.appId(), credential.token()))
              .as("★ 升密后同一条凭证不再返回那部分内容（13 §5.2 第②级 + §5.3）")
              .containsExactly(f.publicSegment());

          // ② 再把 PUBLIC 那条也抬到 CONFIDENTIAL
          setClassification(f.publicSegment(), ClassificationLevel.CONFIDENTIAL);
          assertThat(visible(f.appId(), credential.token()))
              .as("★ 第三次访问为空 ⇒ 它每次都用冻结值重新过滤了一遍；" + "复用创建时的结果的话这里仍会返回第一次那两条")
              .isEmpty();
          return null;
        });
  }

  // ────────────────────────── 回归：登录调用者不得借链接放大（2026-10-13 夜发现）──────────────────────────

  /**
   * ★★ 回归：**登录调用者不得读到高于自己密级的内容**（借别人的链接也不行）。
   *
   * <p>病根（当晚由 `#92` 的实现批次实测发现）：`preview` 曾把<strong>凭证的冻结值</strong>直接当 SQL 上限传进 {@code
   * visibleSegments}，于是 `asScope` 装上的"取更低"（{@code narrower(冻结值, 调用者)}）<strong>没有被消费</strong> ——
   * "凭证冻结 INTERNAL + 调用者 PUBLIC"会连 INTERNAL 那一条一起返回，而且<strong>不报错</strong> （本项目的头号病：不报错的越权）。
   *
   * <p>★ 修法不是"再算一次更小值"，而是**取生效范围自己的那一份**（`currentScope().maxClassification()`）——
   * 于是"收窄"仍然只有一处实现（`narrow`），不存在第二套合并规则。
   *
   * <p>★ 这条断言与"匿名访客能按冻结值拿到内容"（判据①）方向相反、必须同时绿。
   */
  @Test
  void loggedInCallerCannotPreviewAboveHisOwnClearance() {
    Fixture f = seed();
    UUID caller = UUID.fromString("00000000-0000-4000-8000-0000000000f9");

    String token =
        SecurityContexts.asUser(
                OWNER, ClassificationLevel.INTERNAL, () -> service.create(f.appId(), null, OWNER))
            .token();

    Set<UUID> seen =
        SecurityContexts.asUser(
            caller,
            ClassificationLevel.PUBLIC,
            () ->
                service.preview(f.appId(), token, caller).stream()
                    .map(SegmentSearchPort.Scored::segmentId)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));

    assertThat(seen)
        .as("★ 调用者只有 PUBLIC ⇒ 冻结 INTERNAL 的链接也不得把 INTERNAL 那一条给他（借链接不得放大）")
        .containsExactly(f.publicSegment());
  }

  // ────────────────────────── 判据 ①：撤销即失效（真栈）──────────────────────────

  /**
   * ★ 撤销之后<strong>同一个请求周期内</strong>再访问即被拒，且被拒原因是"已撤销"（不是别的）。
   *
   * <p>先访问一次（把解析结果放进缓存 —— 不先访问一次，这条断言证明不了"缓存被清了"），再撤销， 再访问一次 ⇒ 必须红在"已撤销"。
   */
  @Test
  void revokeInvalidatesTheTokenImmediatelyInTheRealStack() {
    Fixture f = seed();

    SecurityContexts.asUser(
        OWNER,
        ClassificationLevel.INTERNAL,
        () -> {
          AccessCredentialView credential = service.create(f.appId(), null, OWNER);
          assertThat(visible(f.appId(), credential.token())).as("撤销之前这条 token 是可用的").isNotEmpty();

          service.revoke(f.appId(), credential.id(), OWNER);

          assertThatThrownBy(() -> visible(f.appId(), credential.token()))
              .as("★ 撤销之后同一个请求周期内再访问即被拒（判据①）")
              .isInstanceOf(BusinessException.class)
              .hasMessageContaining("已撤销");
          return null;
        });
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private record Fixture(UUID appId, UUID publicSegment, UUID internalSegment) {}

  private Set<UUID> visible(UUID appId, String token) {
    List<SegmentSearchPort.Scored> hits = service.preview(appId, token, OWNER);
    return hits.stream()
        .map(SegmentSearchPort.Scored::segmentId)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
  }

  /** 建模型 → 建库 → 建应用 → 两篇文档（PUBLIC / INTERNAL），各一个分段；分段密级**从文档继承**。 */
  private Fixture seed() {
    UUID modelId = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at)"
            + " values (?, '公开凭证夹具模型', 'EMBEDDING', 'LOCAL_INFERENCE', 'bge-m3',"
            + " 'http://localhost:11434/v1', 768, 'LOADED', true, now(), now())",
        modelId);

    UUID kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, '公开凭证夹具库', '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, 'PRIVATE',"
            + " now(), now())",
        kbId,
        modelId,
        OWNER);

    UUID appId = UUID.randomUUID();
    jdbc.update(
        "insert into as_assistant (id, name, type, model_id, owner_id, visibility, published,"
            + " created_at, updated_at)"
            + " values (?, '公开凭证夹具应用', 'SIMPLE', ?, ?, 'PRIVATE', true, now(), now())",
        appId,
        modelId,
        OWNER);
    jdbc.update(
        "insert into as_assistant_kb (assistant_id, knowledge_base_id) values (?, ?)", appId, kbId);
    lastAppId = appId;

    UUID publicSegment =
        insertDocumentAndSegment(kbId, "公开文档", ClassificationLevel.PUBLIC, "差旅住宿标准");
    UUID internalSegment =
        insertDocumentAndSegment(kbId, "内部文档", ClassificationLevel.INTERNAL, "差旅报销流程");
    return new Fixture(appId, publicSegment, internalSegment);
  }

  private UUID insertDocumentAndSegment(
      UUID kbId, String name, ClassificationLevel level, String text) {
    UUID docId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_document (id, kb_id, name, source, doc_type, org_id, classification,"
            + " status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?, ?, ?, 'local', '制度', ?::uuid, ?, 'SUCCEEDED', 0, 1, true,"
            + " now(), now())",
        docId,
        kbId,
        name,
        ROOT_ORG,
        level.name());

    UUID segmentId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_segment (id, document_id, kb_id, ordinal, content, search_text,"
            + " content_hash, char_count, enabled, embedding_status, created_at, updated_at,"
            + " classification)"
            + // ★ 密级**从文档继承**（不进参数）：写死一个就会把被测的谓词换成另一条
            " values (?, ?, ?, 0, ?, ?, ?, ?, true, 'PENDING_EMBEDDING', now(), now(),"
            + " (select classification from kb_document where id = ?))",
        segmentId,
        docId,
        kbId,
        text,
        text,
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "").substring(0, 32),
        text.length(),
        docId);
    return segmentId;
  }

  /**
   * 升密：文档与它的分段<strong>同一事务</strong>改（{@code 13 §6.4} 的一致性纪律）。
   *
   * <p>★ 分段那一列是<b>冗余</b>（{@code V22}）：只改文档会让检索侧（读分段）看不见这次升密， 而"巡检"要等到下一次跑才发现 —— 那正是 {@code 13 §6.4}
   * 说的"两处不一致且不报错"。
   */
  private void setClassification(UUID segmentId, ClassificationLevel level) {
    jdbc.update(
        "update kb_document set classification = ?, updated_at = now()"
            + " where id = (select document_id from kb_segment where id = ?)",
        level.name(),
        segmentId);
    jdbc.update(
        "update kb_segment set classification = ?, updated_at = now() where id = ?",
        level.name(),
        segmentId);
  }
}
