package cn.lincoln.aiplatform.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 数据范围与密级的判定（{@code ROADMAP §4.7.3} 第 3 周测试项 ②）。
 *
 * <p>★ 纯单测：判定是**纯函数**（给定范围、给定资源，返回布尔），不启动 Spring、不连库。
 * 它值得被如此细致地覆盖，因为它是全工程<strong>唯一</strong>一处"能不能看见"的实现 —— 这里错一次，所有模块都跟着错，而且不会报任何错。
 *
 * <p>★ 两个维度各有一组用例，另有一组专门验它们<strong>同时生效</strong>： 归属看得见不等于该看得见（密级可能超限），密级够也不等于该看得见（可能压根不是我的）。
 * 只测单个维度的用例，发现不了"两个条件被写成 or"这类错误。
 */
class ScopeFilterTest {

  private static final UUID ME = UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
  private static final Instant TOMORROW = Instant.parse("2026-09-28T00:00:00Z");
  private static final Instant DAY_AFTER = Instant.parse("2026-09-29T00:00:00Z");

  // ────────────────────────── 数据范围维度 ──────────────────────────

  @Test
  void all范围看得见任何人的资源() {
    ScopeFilter scope = scope(DataScope.ALL, ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSee(privateOf(OTHER), NOW)).isTrue();
  }

  @Test
  void own范围只看得见自己的() {
    ScopeFilter scope = scope(DataScope.OWN, ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSee(privateOf(ME), NOW)).isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).isFalse();
  }

  /** ★ `own` 与 `shared` 的区别就在这一条：别人分享给我的，`own` 也不看。 */
  @Test
  void own范围连共享给自己的也不看() {
    ScopeFilter scope = scope(DataScope.OWN, ClassificationLevel.CONFIDENTIAL);
    OwnedResource shared = sharedWithUser(OTHER, ME, TOMORROW);

    assertThat(scope.canSee(shared, NOW)).as("own 是个人沙箱 —— 合并它和 shared 就再也分不出来了").isFalse();
  }

  @Test
  void shared范围看得见被共享给自己的() {
    ScopeFilter scope = scope(DataScope.SHARED, ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSee(sharedWithUser(OTHER, ME, TOMORROW), NOW)).isTrue();
    assertThat(scope.canSee(sharedWithUser(OTHER, OTHER, TOMORROW), NOW)).isFalse();
  }

  @Test
  void shared范围看得见共享给自己角色的() {
    ScopeFilter scope =
        scope(DataScope.SHARED, Set.of("KB_OPERATOR"), ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSee(sharedWithRole(OTHER, "KB_OPERATOR", TOMORROW), NOW)).isTrue();
    assertThat(scope.canSee(sharedWithRole(OTHER, "ADMIN", TOMORROW), NOW)).isFalse();
  }

  /**
   * ★ {@code kind} 与 {@code id} 必须**一起**比（{@code 07 §8.2}）。
   *
   * <p>这条用例故意让"角色 id"恰好等于"我的用户 id"：只比 id 的实现会在这里放行一个 不该看见资源的人，而撞号在真实系统里很容易发生（各表都用同一种 id 生成方式）。
   */
  @Test
  void kind与id必须一起比对() {
    ScopeFilter scope = scope(DataScope.SHARED, Set.of("role-1"), ClassificationLevel.CONFIDENTIAL);
    OwnedResource trap = sharedWithRole(OTHER, ME.toString(), TOMORROW);

    assertThat(scope.canSee(trap, NOW))
        .as("我是用户 %s，资源共享给了角色 %s —— 同一个字符串，但根本不是一个主体", ME, ME)
        .isFalse();
  }

  @Test
  void 过期的授权不再有效() {
    ScopeFilter scope = scope(DataScope.SHARED, ClassificationLevel.CONFIDENTIAL);
    OwnedResource expiring = sharedWithUser(OTHER, ME, TOMORROW);

    assertThat(scope.canSee(expiring, NOW)).as("明天才到期，现在有效").isTrue();
    assertThat(scope.canSee(expiring, DAY_AFTER)).as("写明了 expiresAt 就该在到期后失效").isFalse();
  }

  @Test
  void public对登录用户可见但private不行() {
    ScopeFilter scope = scope(DataScope.OWN, ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSee(new SimpleResource(OTHER, Visibility.PUBLIC), NOW)).isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).isFalse();
  }

  /** ★ 未认证什么都看不见 —— 包括 public。免登录面走的是公开凭证那条独立链路。 */
  @Test
  void 未认证时什么都看不见() {
    ScopeFilter nobody = ScopeFilter.nobody();

    assertThat(nobody.canSee(new SimpleResource(OTHER, Visibility.PUBLIC), NOW)).isFalse();
    assertThat(nobody.canSee(privateOf(ME), NOW)).isFalse();
    assertThat(nobody.canSeeClassification(ClassificationLevel.PUBLIC, null)).isFalse();
  }

  // ────────────────────────── 密级维度 ──────────────────────────

  @Test
  void 资源密级不超过上限时可见() {
    ScopeFilter scope = scope(DataScope.OWN, ClassificationLevel.SECRET);

    assertThat(scope.canSeeClassification(ClassificationLevel.PUBLIC, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.INTERNAL, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.SECRET, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.CONFIDENTIAL, null)).isFalse();
  }

  @Test
  void 标签与角色可涉标签相交时可见() {
    ScopeFilter scope =
        scopeWithLabels(DataScope.OWN, ClassificationLevel.CONFIDENTIAL, Set.of("财务"));

    assertThat(scope.canSeeClassification(ClassificationLevel.INTERNAL, Set.of("财务", "人事")))
        .isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.INTERNAL, Set.of("人事"))).isFalse();
  }

  /**
   * ★ 空标签集表示**不按标签限制**，而不是"什么标签都看不到"。
   *
   * <p>这是最容易写反的一条：写成"必须相交"的话，那些没配标签的角色（绝大多数） 会看不到任何带标签的资源 —— 现象是"某些文档凭空消失"，很难联想到标签。
   */
  @Test
  void 空标签集表示不按标签限制() {
    ScopeFilter noLabels = scope(DataScope.OWN, ClassificationLevel.CONFIDENTIAL);

    assertThat(noLabels.canSeeClassification(ClassificationLevel.SECRET, Set.of("财务"))).isTrue();
    // 资源侧没有标签时也同理
    ScopeFilter withLabels =
        scopeWithLabels(DataScope.OWN, ClassificationLevel.CONFIDENTIAL, Set.of("财务"));
    assertThat(withLabels.canSeeClassification(ClassificationLevel.SECRET, Set.of())).isTrue();
  }

  // ────────────────────────── ★★ 两个维度同时生效 ──────────────────────────

  @Test
  void 归属看得见但密级超限时仍然看不见() {
    ScopeFilter scope = scope(DataScope.ALL, ClassificationLevel.INTERNAL);

    assertThat(scope.canSee(privateOf(OTHER), NOW)).as("归属上看得见").isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.CONFIDENTIAL, null))
        .as("但密级超限")
        .isFalse();
  }

  @Test
  void 密级够但归属看不见时仍然看不见() {
    ScopeFilter scope = scope(DataScope.OWN, ClassificationLevel.CONFIDENTIAL);

    assertThat(scope.canSeeClassification(ClassificationLevel.PUBLIC, null)).as("密级上够").isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).as("但压根不是我的").isFalse();
  }

  // ────────────────────────── 密级的传播语义 ──────────────────────────

  @Test
  void 密级比较按声明顺序且传播就高不就低() {
    assertThat(ClassificationLevel.PUBLIC.within(ClassificationLevel.PUBLIC)).isTrue();
    assertThat(ClassificationLevel.CONFIDENTIAL.within(ClassificationLevel.SECRET)).isFalse();

    assertThat(ClassificationLevel.INTERNAL.max(ClassificationLevel.CONFIDENTIAL))
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
    assertThat(ClassificationLevel.CONFIDENTIAL.max(ClassificationLevel.PUBLIC))
        .isEqualTo(ClassificationLevel.CONFIDENTIAL);
    assertThat(ClassificationLevel.INTERNAL.max(null)).isEqualTo(ClassificationLevel.INTERNAL);
  }

  // ────────────────────────── ★★ 部门共享（`07 §十一` 消费位③ → ①`）──────────────────────────

  /**
   * 共享给"我的部门"时可见。
   *
   * <p>★ 这个能力在契约里**早就预留了**（{@code SubjectKind.ORG}），但在这条用例之前 **零消费方** —— 它一直掉在 `switch` 的 `default
   * -> false` 里（Issue #38）。
   */
  @Test
  void 共享给我所属部门时可见() {
    ScopeFilter scope = scopeWithOrgs(DataScope.SHARED, Set.of("TECH", "FINANCE"));

    assertThat(scope.canSee(sharedWithOrg(OTHER, "TECH", TOMORROW), NOW)).isTrue();
    assertThat(scope.canSee(sharedWithOrg(OTHER, "HR", TOMORROW), NOW)).as("别的部门（兄弟）看不见").isFalse();
  }

  /**
   * ★★ 金丝雀：**"含下级"这条口径必须成立** —— 资源共享给上级部门时，下级部门的人看得见。
   *
   * <p>做法是把两个方向**并排**写在同一处（`myOrgIds` 展开过的 vs 只算我自己那一个）： 谁把实现"优化"成精确匹配（只比 `subject.id() == 我的部门
   * id`），第一条断言立刻变红。
   *
   * <p>为什么值得一条金丝雀：这条口径是<strong>产品承诺</strong>（"共享给部门"意味着整条部门线都能看）， 不是算法形状；而写错它**不会报错** ——
   * 只会让下级部门的人"看不见上级共享的库"， 现象与"没共享过"一模一样。
   */
  @Test
  void 金丝雀_把含下级改成精确匹配必须变红() {
    String parent = "TECH";
    String child = "TECH_PLATFORM";
    // 我是下级部门的人 ⇒ 我的可见集合里既有我自己，也有我的上级（展开"我的部门及下级"是在构造范围时做的）
    ScopeFilter containsUpper = scopeWithOrgs(DataScope.SHARED, Set.of(child, parent));
    ScopeFilter onlyMyself = scopeWithOrgs(DataScope.SHARED, Set.of(child));

    OwnedResource sharedToParent = sharedWithOrg(OTHER, parent, TOMORROW);

    assertThat(containsUpper.canSee(sharedToParent, NOW))
        .as("含下级：共享给上级部门 ⇒ 下级部门的人看得见（07 §10.5 判据 1）")
        .isTrue();
    assertThat(onlyMyself.canSee(sharedToParent, NOW))
        .as("★ 金丝雀的另一半：这正是「精确匹配」的样子 —— 它必须 false，否则上面那条断言就在说谎")
        .isFalse();
  }

  /** ★ 没有部门归属（`iam_user.org_id` 为空）⇒ 看不见任何部门共享，且**不报错**（fail-closed）。 */
  @Test
  void 没有部门归属时看不见任何部门共享() {
    ScopeFilter noOrg = scope(DataScope.SHARED, ClassificationLevel.CONFIDENTIAL);

    assertThat(noOrg.canSee(sharedWithOrg(OTHER, "TECH", TOMORROW), NOW)).isFalse();
  }

  // ────────────────────────── 工具 ──────────────────────────

  private static ScopeFilter scope(DataScope dataScope, ClassificationLevel clearance) {
    return scopeWithLabels(dataScope, clearance, Set.of());
  }

  private static ScopeFilter scope(
      DataScope dataScope, Set<String> roles, ClassificationLevel clearance) {
    return new ScopeFilter(ME, dataScope, roles, clearance, Set.of());
  }

  private static ScopeFilter scopeWithLabels(
      DataScope dataScope, ClassificationLevel clearance, Set<String> clLabels) {
    return new ScopeFilter(ME, dataScope, Set.of(), clearance, clLabels);
  }

  /** 带部门归属集的范围（`myOrgIds` = 我所属部门 + 其全部下级，已在构造时展开）。 */
  private static ScopeFilter scopeWithOrgs(DataScope dataScope, Set<String> myOrgIds) {
    return new ScopeFilter(
        ME, dataScope, Set.of(), ClassificationLevel.CONFIDENTIAL, Set.of(), myOrgIds);
  }

  private static OwnedResource sharedWithOrg(UUID owner, String orgId, Instant expiresAt) {
    return new SimpleResource(
        owner, Visibility.SHARED, List.of(grant(SubjectKind.ORG, orgId, expiresAt)));
  }

  private static OwnedResource privateOf(UUID owner) {
    return new SimpleResource(owner, Visibility.PRIVATE);
  }

  private static OwnedResource sharedWithUser(UUID owner, UUID who, Instant expiresAt) {
    return new SimpleResource(
        owner, Visibility.SHARED, List.of(grant(SubjectKind.USER, who.toString(), expiresAt)));
  }

  private static OwnedResource sharedWithRole(UUID owner, String roleId, Instant expiresAt) {
    return new SimpleResource(
        owner, Visibility.SHARED, List.of(grant(SubjectKind.ROLE, roleId, expiresAt)));
  }

  private static Grant grant(SubjectKind kind, String id, Instant expiresAt) {
    return new Grant(new Subject(kind, id), Set.of("knowledge:view"), expiresAt, false);
  }

  /** 测试用的最小资源实现。 */
  private record SimpleResource(UUID ownerId, Visibility visibility, List<Grant> grants)
      implements OwnedResource {

    SimpleResource(UUID ownerId, Visibility visibility) {
      this(ownerId, visibility, List.of());
    }
  }
}
