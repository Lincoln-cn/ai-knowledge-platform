package cn.lincoln.aiplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★ 公开面委派入口 {@code AccessScopePort.asSubject} 的三条判据的<strong>真栈</strong>读数（第 17 周 · {@code 08
 * §4.4.4} · Issue {@code #92}）。
 *
 * <h3>为什么必须是真栈（而不是只留一个单测）</h3>
 *
 * <p>这三条判据验的不是"某个 if 写对了吗"，而是三件只有真栈才成立的事：
 *
 * <ol>
 *   <li><strong>身份真的从库里读出来</strong>：主体（{@code iam_user} → {@code iam_role}）的角色 / 数据范围 / 密级 /
 *       部门归属，全部来自迁移种子（{@code V4} / {@code V9} / {@code V25} / {@code V28}）—— 用一个手写的桩身份验不了
 *       "这条链在生产里读得到东西"（{@code #38} 的全部内容就是"分支写好了、零消费方"）；
 *   <li><strong>检索真的走通</strong>：{@code visibleAmong} 用的是 {@code JdbcSegmentSearchQuery}
 *       里<strong>同一份</strong> {@code SECURITY_PREDICATE}（{@code 13 §三} 闸门②），密级上限则由 {@code
 *       accessScope.currentScope()} 提供 —— 于是"委派范围装上了没有"直接决定 SQL 返回什么（假替身读不到这一层）；
 *   <li><strong>匿名是真的匿名</strong>：本类在 {@code @BeforeEach} 里显式清空认证上下文，而不是"碰巧没装"。
 * </ol>
 *
 * <h3>三条判据（方向相反、必须同时绿）</h3>
 *
 * <ul>
 *   <li>① {@link #anonymousVisitorGetsTheSubjectsIdentityCappedByTheFrozenClearance} —— 不带任何认证上下文 +
 *       有效凭证 ⇒ 检索走通，且返回内容不超过冻结密级；
 *   <li>② {@link #loggedInCallerCannotReachBeyondHisOwnRangeThroughTheSameLink} —— clearance
 *       低于归属人的登录用户拿同一条链接 ⇒ 拿不到超出他自身范围的内容；
 *   <li>③ 金丝雀的证据在交付报告里（把"按 {@code subjectId} 载入身份"改回"照抄请求者" ⇒ 判据①必须变红）。 ★ 本类里长期守着同一条方向的还有 {@link
 *       #subjectThatCannotBeLoadedSeesNothingEvenWhenACallerIsLoggedIn}：
 *       它把"绝不回落到调用者"钉成一条<strong>常驻</strong>断言（不是一次性破坏）。
 * </ul>
 *
 * <h3>夹具怎么造</h3>
 *
 * <p>身份侧<strong>不动</strong>：主体与调用者都用迁移种子里的账号（{@code zhangyun} = {@code KB_OPERATOR}（ALL /
 * SECRET，技术部）、{@code chenjun} = {@code USER}（SHARED / 公开，市场部）、{@code admin} = {@code ADMIN}（ALL /
 * 机密， 总部））—— 这本身就是一条读数：这条链在真实迁移数据上跑得通。
 *
 * <p>知识侧走裸 SQL 建库 / 文档 / 分段（与 {@code AccessCredentialFilteringTest} 同一套夹具形状，{@code #48} 记着这笔账）：
 * 本类要验的是<strong>过滤</strong>，不是建档链路，走服务层会把解析 / 向量化这类无关前置一起拖进来。 ★ 三篇文档的密级分别 PUBLIC / INTERNAL /
 * SECRET，分段密级<strong>从文档继承</strong>（{@code V22} 的列是 {@code not null}，写死一个就把被测的谓词换成另一条）。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class PublicSubjectDelegationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 迁移种子里的账号（{@code V4} / {@code V9} 给的角色与密级）。 */
  private static final UUID ZHANGYUN = UUID.fromString("10000000-0000-0000-0000-000000000002");

  private static final UUID CHENJUN = UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID ADMIN = UUID.fromString("10000000-0000-0000-0000-000000000001");

  /** {@code V25} 给根节点的固定 UUID。 */
  private static final UUID ROOT_ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Autowired JdbcTemplate jdbc;

  @Autowired AccessScopePort accessScope;

  @Autowired SegmentSearchPort segmentSearch;

  private UUID kbId;
  private UUID modelId;
  private UUID publicSegment;
  private UUID internalSegment;
  private UUID secretSegment;

  /**
   * ★ 判据①/② 的前提：<strong>不带任何认证上下文</strong>（匿名）是显式清出来的，不是"碰巧没装"。
   *
   * <p>SecurityContext 是 {@code ThreadLocal} 而 surefire 复用线程 ⇒ 上一条用例装过身份的话，
   * "匿名"这一条会静默地变成一个"已登录但主体是别人"的用例。
   */
  @BeforeEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    // ★ 删除顺序必须"从下往上"：kb_segment 引用 kb_document、kb_document 引用 kb_knowledge_base。
    //   容器是共享的（Spring 上下文缓存），只清本类造的那一行 —— 全表 delete 会打到别的测试类留下的夹具
    if (kbId != null) {
      jdbc.update("delete from kb_segment where kb_id = ?", kbId);
      jdbc.update("delete from kb_document where kb_id = ?", kbId);
      jdbc.update("delete from kb_knowledge_base where id = ?", kbId);
    }
    if (modelId != null) {
      jdbc.update("delete from ai_model where id = ?", modelId);
    }
    kbId = null;
    modelId = null;
    publicSegment = null;
    internalSegment = null;
    secretSegment = null;
  }

  /**
   * ★★ 判据①：<strong>匿名能走通</strong>（{@code #92} 的病根就在这一条上）。
   *
   * <p>不带任何认证上下文、用一条有效凭证（这里由调用方直接给出"凭证已解析成功"的三个事实 —— 冻结密级与归属人）⇒
   * 检索必须走通，且返回内容<strong>不超过冻结密级</strong>。
   *
   * <p>★ 第一条断言（{@code scope.me()} 等于归属人）同时是金丝雀的落点："照抄当前请求者"在匿名面上就是 {@link
   * ScopeFilter#nobody()}（{@code me == null}）⇒ 检索必然返空 ⇒ 这条红。
   */
  @Test
  void anonymousVisitorGetsTheSubjectsIdentityCappedByTheFrozenClearance() {
    seed();

    DelegatedRead read = readThroughLink(ZHANGYUN, ClassificationLevel.INTERNAL);

    assertThat(read.scope().me())
        .as("★ 判据① 的根：匿名时生效范围的主体必须是**归属人**（照抄请求者 ⇒ 这里是 null ⇒ 什么都看不见）")
        .isEqualTo(ZHANGYUN);
    assertThat(read.scope().scope()).as("数据范围来自归属人的角色（KB_OPERATOR = ALL）").isEqualTo(DataScope.ALL);
    assertThat(read.scope().myRoles())
        .as("角色代码按 code 读（= 令牌里的 role 声明 = 共享主体里存的值）")
        .containsExactly("KB_OPERATOR");
    assertThat(read.scope().maxClassification())
        .as("★ 判据① 的核心：密级仍取冻结值 —— 归属人是 SECRET，但链接冻结在 INTERNAL")
        .isEqualTo(ClassificationLevel.INTERNAL);
    assertThat(read.scope().myOrgIds())
        .as("部门链只许有一处展开：登录面读令牌、公开面读库，两条来源共用同一个展开（技术部 + 总部）")
        .containsExactlyInAnyOrder(techOrgId().toString(), ROOT_ORG.toString());

    assertThat(read.kbIds()).as("★ 判据① 前半：检索走通（库可见）").containsExactly(kbId);
    assertThat(read.segments())
        .as("★ 判据① 后半：返回内容不超过冻结密级（公开 + 内部可见，秘密不可见）")
        .containsExactlyInAnyOrder(publicSegment, internalSegment)
        .doesNotContain(secretSegment);
  }

  /**
   * ★★ 判据②：<strong>登录用户不能借链接放大</strong>。
   *
   * <p>同一个主体（{@code zhangyun}，SECRET / ALL）的同一条链接（冻结 INTERNAL），被一个 clearance 更低的登录用户 （{@code
   * chenjun}，PUBLIC / SHARED / 市场部）打开 ⇒ 生效范围必须<strong>不超过他自己那一份</strong>。
   *
   * <p>★ 三条断言各守一个分量：密级取<strong>更低</strong>的、数据范围与角色<strong>照抄调用者</strong>（不是归属人的 ALL /
   * KB_OPERATOR）、以及真栈检索里<strong>拿不到</strong> INTERNAL 那条。
   */
  @Test
  void loggedInCallerCannotReachBeyondHisOwnRangeThroughTheSameLink() {
    seed();
    givenLogin(CHENJUN, "USER", "SHARED", ClassificationLevel.PUBLIC, marketingOrgId());

    DelegatedRead read = readThroughLink(ZHANGYUN, ClassificationLevel.INTERNAL);

    assertThat(read.scope().me())
        .as("取数行使的仍是**链接那一份**的身份（公开面 = 归属人），调用者并不因此变成归属人")
        .isEqualTo(ZHANGYUN);
    assertThat(read.scope().maxClassification())
        .as("★ 判据②：密级取更低 —— 归属人 SECRET ⊓ 冻结 INTERNAL ⊓ 调用者 PUBLIC = PUBLIC")
        .isEqualTo(ClassificationLevel.PUBLIC);
    assertThat(read.scope().scope())
        .as("★ 判据②：数据范围照抄调用者（SHARED），不是归属人的 ALL —— 取反了也能跑，症状是借链接放大")
        .isEqualTo(DataScope.SHARED);
    assertThat(read.scope().myRoles()).as("角色也照抄调用者").containsExactly("USER");

    assertThat(read.segments())
        .as("★ 判据② 的真栈读数：拿不到超出他自身范围的内容（INTERNAL 那条看不见）")
        .containsExactly(publicSegment)
        .doesNotContain(internalSegment, secretSegment);
  }

  /**
   * ★★ <strong>常驻的反证（不是一次性的破坏）</strong>：主体载入不出来 ⇒ 什么都看不见，<strong>绝不</strong>回落到调用者。
   *
   * <p>调用者故意选一个"看得见一切"的账号（{@code admin}，ALL / 机密）：如果实现回落到"照抄请求者"，那么库可见、 三条分段也都可见（密级最高）⇒
   * 下面三条断言全红。这正是 {@code 08 §4.4.4} 判据③ 要守的方向，只不过这里用的是
   * <strong>匿名以外的</strong>另一种触发方式（主体不存在），所以它能在<strong>有</strong>登录上下文时也把方向钉住。
   */
  @Test
  void subjectThatCannotBeLoadedSeesNothingEvenWhenACallerIsLoggedIn() {
    seed();
    givenLogin(ADMIN, "ADMIN", "ALL", ClassificationLevel.CONFIDENTIAL, ROOT_ORG);

    DelegatedRead read = readThroughLink(UUID.randomUUID(), ClassificationLevel.INTERNAL);

    assertThat(read.scope().me()).as("载入不出主体 ⇒ nobody()（**不**退回调用者那一份）").isNull();
    assertThat(read.kbIds()).as("★ 回落的话这里会看到库（admin 是 ALL）").isEmpty();
    assertThat(read.segments()).isEmpty();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 一次委派期间读到的三样东西（范围、可见的库、可见的分段）。 */
  private record DelegatedRead(ScopeFilter scope, List<UUID> kbIds, Set<UUID> segments) {}

  /**
   * 在 {@code asSubject} 的委派里跑一次真实检索（库 → 分段），把三样读数带出来。
   *
   * <p>★ 检索<strong>必须</strong>在 body 里跑：范围只在委派期间生效（{@code DELEGATED}），出了 body 读到的就是调用者自己的。
   *
   * <p>★ 用 {@link SegmentSearchPort#visibleAmong}（候选复判）而不是 {@code visibleSegments}：前者从 {@code
   * accessScope.currentScope()} 取密级上限，后者要调用方显式传一个 ceiling —— 本类要验的正是"**装上的范围**决定了返回什么"。
   */
  private DelegatedRead readThroughLink(UUID subjectId, ClassificationLevel frozenClearance) {
    List<UUID> candidates = List.of(publicSegment, internalSegment, secretSegment);
    return accessScope.asSubject(
        subjectId,
        frozenClearance,
        () -> {
          ScopeFilter scope = accessScope.currentScope();
          List<UUID> kbIds = segmentSearch.visibleKbIds(List.of(kbId));
          Set<UUID> segments =
              kbIds.isEmpty()
                  ? Set.of()
                  : Set.copyOf(segmentSearch.visibleAmong(candidates, kbIds));
          return new DelegatedRead(scope, kbIds, segments);
        });
  }

  /**
   * 装一个"已登录"的上下文（与 {@code AccessScopeOrgScopeTest} 同一套做法）。
   *
   * <p>★ {@code setAuthenticated(true)} 是必须的：{@code new JwtAuthenticationToken(jwt)} 之后它仍是 {@code
   * false}， 而适配器第一句话就是"未认证 ⇒ nobody"。漏了它，本类所有断言会一起变成"什么都看不见"。
   */
  private static void givenLogin(
      UUID userId, String roleCode, String scope, ClassificationLevel clearance, UUID orgId) {
    Jwt.Builder builder =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .claim("role", roleCode)
            .claim("scope", scope)
            .claim("clearance", clearance.name());
    if (orgId != null) {
      builder.claim("orgId", orgId.toString());
    }
    JwtAuthenticationToken authentication = new JwtAuthenticationToken(builder.build());
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  /** 建模型 → 建库（归属人 = {@code zhangyun}）→ 三篇文档（PUBLIC / INTERNAL / SECRET），各一个分段。 */
  private void seed() {
    modelId = UUID.randomUUID();
    jdbc.update(
        "insert into ai_model (id, name, kind, supplier, base_model, api_base, dimension, status,"
            + " enabled, created_at, updated_at)"
            + " values (?, '公开面委派夹具模型', 'EMBEDDING', 'LOCAL_INFERENCE', 'bge-m3',"
            + " 'http://localhost:11434/v1', 768, 'LOADED', true, now(), now())",
        modelId);

    kbId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, '公开面委派夹具库', '制度规范', 'LOCAL', ?, 'CONFIDENTIAL', ?, 'PRIVATE',"
            + " now(), now())",
        kbId,
        modelId,
        ZHANGYUN);

    publicSegment = insertDocumentAndSegment("公开文档", ClassificationLevel.PUBLIC, "差旅住宿标准");
    internalSegment = insertDocumentAndSegment("内部文档", ClassificationLevel.INTERNAL, "差旅报销流程");
    secretSegment = insertDocumentAndSegment("秘密文档", ClassificationLevel.SECRET, "并购尽调材料");
  }

  private UUID insertDocumentAndSegment(String name, ClassificationLevel level, String text) {
    UUID docId = UUID.randomUUID();
    jdbc.update(
        "insert into kb_document (id, kb_id, name, source, doc_type, org_id, classification,"
            + " status, char_count, segment_count, enabled, created_at, updated_at)"
            + " values (?, ?, ?, 'local', '制度', ?::uuid, ?, 'SUCCEEDED', 0, 1, true,"
            + " now(), now())",
        docId,
        kbId,
        name,
        ROOT_ORG.toString(),
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
   * {@code V28} 把 {@code zhangyun} 归到技术部（{@code V25} 用 {@code gen_random_uuid()} 建它 ⇒ 只能按 code 找）。
   */
  private UUID techOrgId() {
    return orgIdByCode("TECH");
  }

  private UUID marketingOrgId() {
    return orgIdByCode("MARKETING");
  }

  private UUID orgIdByCode(String code) {
    return jdbc.queryForObject("select id from iam_org where code = ?", UUID.class, code);
  }
}
