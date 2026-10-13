package cn.lincoln.aiplatform.identity;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 可见性判定的<strong>测试替身</strong>（{@code work in test sources}）。
 *
 * <p>★ 它为什么住在 {@code identity} 的测试包里，而不是"需要它的那个模块的测试包"： {@code AccessScopeUniquenessTest}
 * 的规则是"<strong>任何</strong> {@code AccessScopePort} 的实现都必须住在 {@code identity} 下" ——
 * 那条规则的意图是"判定属于身份域"，而它<strong>认不出</strong>生产实现与测试替身的区别 （真事：把替身写在 {@code assistant} 的测试包里，CI 立刻报 1
 * 处违规）。
 *
 * <p>★ 与生产实现同口径的两件事（否则被测的那条链在替身上会变成空断言）：
 *
 * <ol>
 *   <li>{@link #currentScope()} 返回<strong>当前生效</strong>的范围：委派期间是委派值，否则是"请求者自己的"。 ★ 使用者（假的 {@code
 *       SegmentSearchPort}）就是靠问它来决定"哪些分段可见"—— 这正是生产实现里 {@code
 *       JdbcSegmentSearchQuery.clearanceRank()} 的取数方式。
 *   <li>{@link #asScope} 真的把委派值装上并**取更低的那一个**（{@code narrower}）—— 取反了也能跑，而症状是"链接能看到的比它能创建的人还多"。
 * </ol>
 *
 * <p>★ 默认行为是"看得见"（{@link #setVisible(boolean)} 可切）：这正是调用方要造的两种情形 —— 看得见 ⇒ 正常路径；看不见 ⇒ 必须 404 而不是
 * 403。
 */
public final class FakeAccessScope implements AccessScopePort {

  /** 是否可见；测试里可随时改。 */
  private boolean visible = true;

  /** 当前"请求者"的密级上限（默认 INTERNAL：演示角色里最常见的那一档）。 */
  private ClassificationLevel clearance = ClassificationLevel.INTERNAL;

  /** 当前"请求者"的 id（{@code asScope} 的校验对象；null = 不校验）。 */
  private UUID requesterId;

  /** 委派期间生效的那一份范围（{@code asScope} 装上、finally 还原）。 */
  private final ThreadLocal<ScopeFilter> delegated = new ThreadLocal<>();

  public void setVisible(boolean visible) {
    this.visible = visible;
  }

  public void setClearance(ClassificationLevel clearance) {
    this.clearance = clearance;
  }

  /** 声明"当前请求者是谁"，供 {@link #asScope} 与真实实现同口径地校验。 */
  public void setRequesterId(UUID requesterId) {
    this.requesterId = requesterId;
  }

  /**
   * 当前生效的范围。
   *
   * <p>★ 未声明请求者时返回 {@code nobody()}（与生产实现的"未认证 ⇒ 什么都看不见"同口径）—— 但要构造出"看得见的范围"需要在 {@link
   * #setRequesterId} 之后问它。
   */
  @Override
  public ScopeFilter currentScope() {
    ScopeFilter installed = delegated.get();
    if (installed != null) {
      return installed;
    }
    if (requesterId == null) {
      return ScopeFilter.nobody();
    }
    // ★ ALL：替身只管"密级"这一维（数据范围由 AccessScopeOrgScopeTest / 真栈验），
    //   否则每个用它的测试都要先造组织树才能看见任何东西
    return new ScopeFilter(requesterId, DataScope.ALL, Set.of(), clearance, Set.of(), Set.of());
  }

  @Override
  public boolean canSee(OwnedResource resource) {
    return visible;
  }

  /**
   * 替身：校验发起人 + 把委派值装上（取更低的那一个），执行取数逻辑后还原。
   *
   * <p>★ 不做真实的"与请求者范围求交"里那些分量（角色 / 标签 / 部门链）—— 那些由真适配器验 （{@code
   * AccessCredentialFilteringTest}）。替身必须让<strong>密级</strong>那一维真的生效， 否则判据 ②③ 在替身里就成了空断言。
   */
  @Override
  public <T> T asScope(ScopeFilter scope, UUID requester, Supplier<T> body) {
    if (requesterId != null && !requesterId.equals(requester)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "无法以别人的身份解析公开令牌", "委派范围只能由发起解析的那个登录用户行使");
    }
    ClassificationLevel effective =
        ClassificationLevel.narrower(scope.maxClassification(), clearance);
    ScopeFilter installed =
        new ScopeFilter(scope.me(), DataScope.ALL, Set.of(), effective, Set.of(), Set.of());
    ScopeFilter previous = delegated.get();
    delegated.set(installed);
    try {
      return body.get();
    } finally {
      if (previous == null) {
        delegated.remove();
      } else {
        delegated.set(previous);
      }
    }
  }
}
