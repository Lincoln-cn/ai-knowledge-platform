package cn.lincoln.aiplatform.platform.access;

/**
 * 角色的**数据范围**（{@code 07 §3.2}）—— 回答"能看到哪些"，与"能不能做这个动作"（权限点）正交。
 *
 * <p>★ 三档的语义（不要合并 {@code own} 与 {@code shared}）：
 *
 * <ul>
 *   <li>{@link #own} <strong>个人沙箱</strong>：我只管我建的 —— 别人分享给我的我也不想看见
 *   <li>{@link #shared} 我建的 <strong>+</strong> 被共享给我的（使用者就是这个语义）
 *   <li>{@link #all} 全平台
 * </ul>
 *
 * <p>★ 它们<strong>不是唯一形状</strong>："本部门""我参与的项目"这类范围一定会来。 逃生口是 {@code Role.dataScopeRule}（{@code 07
 * §8.3}）—— 先加枚举值， 真需要表达式时才实现解析器。现在不预埋解析器。
 */
public enum DataScope {
  own,
  shared,
  all
}
