package cn.lincoln.aiplatform.identity;

import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.OwnedResource;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;

/**
 * 可见性判定的<strong>测试替身</strong>（{@code work in test sources}）。
 *
 * <p>★ 它为什么住在 {@code identity} 的测试包里，而不是"需要它的那个模块的测试包"： {@code AccessScopeUniquenessTest}
 * 的规则是"<strong>任何</strong> {@code AccessScopePort} 的实现都必须住在 {@code identity} 下" ——
 * 那条规则的意图是"判定属于身份域"，而它<strong>认不出</strong>生产实现与测试替身的区别 （真事：把替身写在 {@code assistant} 的测试包里，CI 立刻报 1
 * 处违规）。
 *
 * <p>◇ 为什么不去给闸门开白名单：闸门的类注释专门论证过"不做数量断言、也不做白名单"—— 一旦能列例外，
 * 第二个<strong>生产</strong>实现也能被列进去，而那正是这条闸门要防的。
 *
 * <p>★ 它的默认行为是"看得见"（{@link #visible} 可切），因为这正是调用方要造的两种情形： 看得见 ⇒ 正常路径；看不见 ⇒ 必须 404 而不是 403。
 */
public final class FakeAccessScope implements AccessScopePort {

  /** 是否可见；测试里可随时改（字段可见性 = 包内可改，跨包用 {@link #setVisible(boolean)}）。 */
  private boolean visible = true;

  public void setVisible(boolean visible) {
    this.visible = visible;
  }

  /** ★ 不给"当前请求的范围"任何特权：返回"什么都看不见"，逼调用方显式处理（而不是静默拿到全部可见）。 */
  @Override
  public ScopeFilter currentScope() {
    return ScopeFilter.nobody();
  }

  @Override
  public boolean canSee(OwnedResource resource) {
    return visible;
  }
}
