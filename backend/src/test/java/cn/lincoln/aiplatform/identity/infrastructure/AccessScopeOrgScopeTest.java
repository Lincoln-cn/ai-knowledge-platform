package cn.lincoln.aiplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.access.Grant;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.Subject;
import cn.lincoln.aiplatform.platform.access.SubjectIdentityPort;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import cn.lincoln.aiplatform.platform.access.Visibility;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ★ "我的部门及其全部<strong>上级</strong>"这条授权基的<strong>解析侧</strong>（{@code 07 §十一} 消费位③ / {@code T301} 后半 /
 * Issue #38）。
 *
 * <h3>为什么判定侧已经测过（{@code ScopeFilterTest}）还要单独测这一层</h3>
 *
 * <p>因为"判定对"≠"有人在给它材料"。{@code #38} 的全部内容就是"分支写好了、**零消费方**"： 判定侧 18/18 全绿，而生产侧 {@code myOrgIds} 恒为空集
 * ⇒ 共享给部门这件事一次都不会生效， 症状与"从来没共享过"一模一样（本项目头号病）。
 *
 * <h3>这里守的三件事</h3>
 *
 * <ol>
 *   <li><strong>方向</strong>：共享给「技术部」⇒ 技术部的**下级**（研发组）的人看得见。取成子树会得到镜像语义 （"共享给我的下级 ⇒ 我看得见"），两者都不报错 ——
 *       所以下面两个方向都写死在断言里。
 *   <li><strong>一处展开、一次请求</strong>：同一请求内取几次 {@link ScopeFilter} 都只查一次部门链； 而**换一个请求要重新解析**
 *       （缓存挂在请求属性上，不是字段/静态变量 —— 否则会把一个人的可见部门带给所有人）。
 *   <li><strong>没部门 ⇒ 空集</strong>（fail-closed），不是"不限"。
 * </ol>
 */
class AccessScopeOrgScopeTest {

  private static final UUID ROOT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TECH = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID TECH_RD = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final UUID MARKETING = UUID.fromString("00000000-0000-4000-8000-000000000004");
  private static final UUID ME = UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID SOMEONE_ELSE = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

  private StubOrgDirectory directory;
  private AccessScopeAdapter adapter;

  /**
   * ★ 本类只验 {@code currentScope()} 与部门链方向（{@code asSubject} 由 {@code PublicSubjectDelegationTest}
   * 在真栈上验）， 所以身份端口给一个"谁都不认识"的桩 —— 它如果被调到，说明本类测的东西已经跑到另一条路上去了。
   */
  private static final SubjectIdentityPort NO_SUBJECT_IDENTITIES = subjectId -> Optional.empty();

  @BeforeEach
  void setUp() {
    directory = new StubOrgDirectory(true);
    adapter = new AccessScopeAdapter(directory, NO_SUBJECT_IDENTITIES);
    newRequest();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  /**
   * ★★ 判据 1（{@code 07 §10.5}）：共享给「技术部」⇒ 技术部**及下级**都看得见。
   *
   * <p>我是"研发组"的人（技术部的下级）⇒ 技术部必须出现在我的祖先链里。 这正是"含下级"这条产品承诺在解析侧的读数。
   */
  @Test
  void subordinateDepartmentSeesLibrarySharedToParentDepartment() {
    givenRequestOf(TECH_RD);

    ScopeFilter scope = adapter.currentScope();

    assertThat(scope.myOrgIds())
        .as("我的祖先链：研发组 → 技术部 → 总部（少一层就等于把上级共享的库变成看不见）")
        .containsExactlyInAnyOrder(TECH_RD.toString(), TECH.toString(), ROOT.toString());
    assertThat(scope.canSee(sharedWithOrg(TECH), NOW))
        .as("共享给技术部 ⇒ 研发组的人看得见（07 §10.5 判据 1）")
        .isTrue();
    assertThat(scope.canSee(sharedWithOrg(ROOT), NOW)).as("共享给总部 ⇒ 整棵树下的人都看得见").isTrue();
  }

  /** ★ 判据 1 的反向：**兄弟部门看不见**（含"上级看得见下级共享"这个镜像方向也必须不成立）。 */
  @Test
  void siblingDepartmentDoesNotSeeShareToAnotherDepartment() {
    givenRequestOf(TECH_RD);

    ScopeFilter scope = adapter.currentScope();

    assertThat(scope.canSee(sharedWithOrg(MARKETING), NOW)).as("兄弟部门").isFalse();
    assertThat(scope.canSee(sharedWithOrg(TECH_RD.toString()), NOW)).as("我自己那个部门当然看得见").isTrue();
  }

  /**
   * ★★ 一处展开、一次请求 —— 两个方向都要断言。
   *
   * <p>只断言"同一请求只查一次"是不够的：把缓存做成**字段**或静态变量同样能通过那一条， 而那会把第一个访问者的部门链**带给之后的所有请求**
   * （一个人的可见范围被另一个人继承，且不报错）。所以第二条断言是"换请求必须重新解析"。
   */
  @Test
  void orgChainIsResolvedOncePerRequestAndRecomputedOnTheNextOne() {
    givenRequestOf(TECH_RD);

    adapter.currentScope();
    adapter.currentScope();
    adapter.currentScope();

    assertThat(directory.ancestorsCalls)
        .as("同一请求里取 3 次范围 ⇒ 只查一次部门链（canSee 是逐资源调用的，在那里查就是 N+1）")
        .isEqualTo(1);

    newRequest();
    adapter.currentScope();

    assertThat(directory.ancestorsCalls).as("★ 换一个请求必须重新解析（缓存挂在请求属性上，不是字段/静态变量）").isEqualTo(2);
  }

  /** ★ 没有部门归属（{@code iam_user.org_id} 为空）⇒ 看不见任何部门共享，且**不报错**。 */
  @Test
  void missingOrgClaimMeansNoDepartmentShareIsVisible() {
    givenRequestOf(null);

    ScopeFilter scope = adapter.currentScope();

    assertThat(scope.myOrgIds()).isEmpty();
    assertThat(scope.canSee(sharedWithOrg(TECH), NOW))
        .as("fail-closed：漏配只会'少看见'，不会'多看见'")
        .isFalse();
    assertThat(directory.ancestorsCalls).as("没有部门就不该去查部门链").isZero();
  }

  /**
   * ★★ 金丝雀（与本批改动一起交）：把祖先链换成"只算我自己"⇒ 上面判据 1 那条必须变红。
   *
   * <p>做法是两个适配器**并排**（真端口 vs 只返回自己的桩），因为两个方向都不报错、症状却相反 —— 谁把 {@code ancestorsAndSelf}
   * 改成精确匹配（或改回子树），下面第一条断言立刻红。
   *
   * <p>两个断言之间**换一个请求**：缓存是按请求存的，不换的话第二个适配器会读到第一个的缓存， 那时测的就不是方向了。
   */
  @Test
  void canaryShrinkingTheChainToMyselfOnlyMustFail() {
    givenRequestOf(TECH_RD);
    assertThat(adapter.currentScope().canSee(sharedWithOrg(TECH), NOW))
        .as("含下级：共享给技术部 ⇒ 研发组的人看得见（07 §10.5 判据 1）")
        .isTrue();

    newRequest();
    AccessScopeAdapter exactMatchOnly =
        new AccessScopeAdapter(new StubOrgDirectory(false), NO_SUBJECT_IDENTITIES);

    assertThat(exactMatchOnly.currentScope().canSee(sharedWithOrg(TECH), NOW))
        .as("★ 金丝雀的另一半：这正是「精确匹配」的样子 —— 它必须 false，否则上面那条断言在说谎")
        .isFalse();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 装一个"已认证请求"的上下文（与 {@code support/SecurityContexts} 同一套做法）。
   *
   * <p>★ {@code setAuthenticated(true)} 是必须的：{@code new JwtAuthenticationToken(jwt)} 之后它仍是 {@code
   * false}（Spring Security 6 不再由构造器代设），而 {@code AccessScopeAdapter} 第一句话就是 "未认证 ⇒
   * nobody"。漏了它，本类所有断言会一起变成"什么都看不见"，看起来像谓词写错。
   */
  private void givenRequestOf(UUID orgId) {
    Jwt.Builder builder =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(ME.toString())
            .claim("role", "USER")
            .claim("scope", "SHARED")
            .claim("clearance", "CONFIDENTIAL");
    if (orgId != null) {
      builder.claim("orgId", orgId.toString());
    }
    JwtAuthenticationToken authentication = new JwtAuthenticationToken(builder.build());
    authentication.setAuthenticated(true);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  /** ★ 换一个请求：请求属性是"每请求一份"的缓存边界，测缓存必须真的跨过它。 */
  private static void newRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private static OwnedResource sharedWithOrg(UUID orgId) {
    return sharedWithOrg(orgId.toString());
  }

  private static OwnedResource sharedWithOrg(String subjectId) {
    Grant grant =
        new Grant(new Subject(SubjectKind.ORG, subjectId), Set.of("knowledge:view"), null, false);
    return new OwnedResource() {
      @Override
      public UUID ownerId() {
        return SOMEONE_ELSE;
      }

      @Override
      public Visibility visibility() {
        return Visibility.SHARED;
      }

      @Override
      public List<Grant> grants() {
        return List.of(grant);
      }
    };
  }

  /**
   * 桩目录：一棵"总部 &gt; 技术部 &gt; 研发组"的链 + 一个兄弟部门（市场部）。
   *
   * <p>{@code withAncestors=false} 时只返回自己 —— 那是**金丝雀要的坏版本**（= 判定退化成精确匹配）。
   */
  private static final class StubOrgDirectory implements OrgDirectoryPort {

    private final boolean withAncestors;
    private int ancestorsCalls;

    StubOrgDirectory(boolean withAncestors) {
      this.withAncestors = withAncestors;
    }

    @Override
    public Optional<OrgBrief> findById(UUID id) {
      return Optional.empty();
    }

    @Override
    public List<OrgBrief> findByIds(Collection<UUID> ids) {
      return List.of();
    }

    @Override
    public List<UUID> ancestorsAndSelf(UUID orgId) {
      ancestorsCalls++;
      if (!withAncestors) {
        return List.of(orgId);
      }
      if (TECH_RD.equals(orgId)) {
        return List.of(TECH_RD, TECH, ROOT);
      }
      if (TECH.equals(orgId)) {
        return List.of(TECH, ROOT);
      }
      return List.of(orgId);
    }
  }
}
