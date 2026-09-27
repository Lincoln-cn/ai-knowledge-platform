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
    ScopeFilter scope = scope(DataScope.all, ClassificationLevel.机密);

    assertThat(scope.canSee(privateOf(OTHER), NOW)).isTrue();
  }

  @Test
  void own范围只看得见自己的() {
    ScopeFilter scope = scope(DataScope.own, ClassificationLevel.机密);

    assertThat(scope.canSee(privateOf(ME), NOW)).isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).isFalse();
  }

  /** ★ `own` 与 `shared` 的区别就在这一条：别人分享给我的，`own` 也不看。 */
  @Test
  void own范围连共享给自己的也不看() {
    ScopeFilter scope = scope(DataScope.own, ClassificationLevel.机密);
    OwnedResource shared = sharedWithUser(OTHER, ME, TOMORROW);

    assertThat(scope.canSee(shared, NOW)).as("own 是个人沙箱 —— 合并它和 shared 就再也分不出来了").isFalse();
  }

  @Test
  void shared范围看得见被共享给自己的() {
    ScopeFilter scope = scope(DataScope.shared, ClassificationLevel.机密);

    assertThat(scope.canSee(sharedWithUser(OTHER, ME, TOMORROW), NOW)).isTrue();
    assertThat(scope.canSee(sharedWithUser(OTHER, OTHER, TOMORROW), NOW)).isFalse();
  }

  @Test
  void shared范围看得见共享给自己角色的() {
    ScopeFilter scope = scope(DataScope.shared, Set.of("KB_OPERATOR"), ClassificationLevel.机密);

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
    ScopeFilter scope = scope(DataScope.shared, Set.of("role-1"), ClassificationLevel.机密);
    OwnedResource trap = sharedWithRole(OTHER, ME.toString(), TOMORROW);

    assertThat(scope.canSee(trap, NOW))
        .as("我是用户 %s，资源共享给了角色 %s —— 同一个字符串，但根本不是一个主体", ME, ME)
        .isFalse();
  }

  @Test
  void 过期的授权不再有效() {
    ScopeFilter scope = scope(DataScope.shared, ClassificationLevel.机密);
    OwnedResource expiring = sharedWithUser(OTHER, ME, TOMORROW);

    assertThat(scope.canSee(expiring, NOW)).as("明天才到期，现在有效").isTrue();
    assertThat(scope.canSee(expiring, DAY_AFTER)).as("写明了 expiresAt 就该在到期后失效").isFalse();
  }

  @Test
  void public对登录用户可见但private不行() {
    ScopeFilter scope = scope(DataScope.own, ClassificationLevel.机密);

    assertThat(scope.canSee(new SimpleResource(OTHER, Visibility.PUBLIC), NOW)).isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).isFalse();
  }

  /** ★ 未认证什么都看不见 —— 包括 public。免登录面走的是公开凭证那条独立链路。 */
  @Test
  void 未认证时什么都看不见() {
    ScopeFilter nobody = ScopeFilter.nobody();

    assertThat(nobody.canSee(new SimpleResource(OTHER, Visibility.PUBLIC), NOW)).isFalse();
    assertThat(nobody.canSee(privateOf(ME), NOW)).isFalse();
    assertThat(nobody.canSeeClassification(ClassificationLevel.公开, null)).isFalse();
  }

  // ────────────────────────── 密级维度 ──────────────────────────

  @Test
  void 资源密级不超过上限时可见() {
    ScopeFilter scope = scope(DataScope.own, ClassificationLevel.秘密);

    assertThat(scope.canSeeClassification(ClassificationLevel.公开, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.内部, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.秘密, null)).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.机密, null)).isFalse();
  }

  @Test
  void 标签与角色可涉标签相交时可见() {
    ScopeFilter scope = scopeWithLabels(DataScope.own, ClassificationLevel.机密, Set.of("财务"));

    assertThat(scope.canSeeClassification(ClassificationLevel.内部, Set.of("财务", "人事"))).isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.内部, Set.of("人事"))).isFalse();
  }

  /**
   * ★ 空标签集表示**不按标签限制**，而不是"什么标签都看不到"。
   *
   * <p>这是最容易写反的一条：写成"必须相交"的话，那些没配标签的角色（绝大多数） 会看不到任何带标签的资源 —— 现象是"某些文档凭空消失"，很难联想到标签。
   */
  @Test
  void 空标签集表示不按标签限制() {
    ScopeFilter noLabels = scope(DataScope.own, ClassificationLevel.机密);

    assertThat(noLabels.canSeeClassification(ClassificationLevel.秘密, Set.of("财务"))).isTrue();
    // 资源侧没有标签时也同理
    ScopeFilter withLabels = scopeWithLabels(DataScope.own, ClassificationLevel.机密, Set.of("财务"));
    assertThat(withLabels.canSeeClassification(ClassificationLevel.秘密, Set.of())).isTrue();
  }

  // ────────────────────────── ★★ 两个维度同时生效 ──────────────────────────

  @Test
  void 归属看得见但密级超限时仍然看不见() {
    ScopeFilter scope = scope(DataScope.all, ClassificationLevel.内部);

    assertThat(scope.canSee(privateOf(OTHER), NOW)).as("归属上看得见").isTrue();
    assertThat(scope.canSeeClassification(ClassificationLevel.机密, null)).as("但密级超限").isFalse();
  }

  @Test
  void 密级够但归属看不见时仍然看不见() {
    ScopeFilter scope = scope(DataScope.own, ClassificationLevel.机密);

    assertThat(scope.canSeeClassification(ClassificationLevel.公开, null)).as("密级上够").isTrue();
    assertThat(scope.canSee(privateOf(OTHER), NOW)).as("但压根不是我的").isFalse();
  }

  // ────────────────────────── 密级的传播语义 ──────────────────────────

  @Test
  void 密级比较按声明顺序且传播就高不就低() {
    assertThat(ClassificationLevel.公开.within(ClassificationLevel.公开)).isTrue();
    assertThat(ClassificationLevel.机密.within(ClassificationLevel.秘密)).isFalse();

    assertThat(ClassificationLevel.内部.max(ClassificationLevel.机密))
        .isEqualTo(ClassificationLevel.机密);
    assertThat(ClassificationLevel.机密.max(ClassificationLevel.公开))
        .isEqualTo(ClassificationLevel.机密);
    assertThat(ClassificationLevel.内部.max(null)).isEqualTo(ClassificationLevel.内部);
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

  private static OwnedResource privateOf(UUID owner) {
    return new SimpleResource(owner, Visibility.PRIVATE);
  }

  private static OwnedResource sharedWithUser(UUID owner, UUID who, Instant expiresAt) {
    return new SimpleResource(
        owner, Visibility.SHARED, List.of(grant(SubjectKind.user, who.toString(), expiresAt)));
  }

  private static OwnedResource sharedWithRole(UUID owner, String roleId, Instant expiresAt) {
    return new SimpleResource(
        owner, Visibility.SHARED, List.of(grant(SubjectKind.role, roleId, expiresAt)));
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
