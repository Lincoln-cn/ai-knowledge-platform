package cn.lincoln.aiplatform.assistant.domain;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 应用收藏的仓储端口（{@code #195} 的「收藏」子项）。
 *
 * <p>★ 两条写操作都是<strong>幂等</strong>的：重复收藏 / 重复取消都返回成功。契约的 {@code favoriteAssistant}
 * 注释写的就是"幂等：重复收藏/取消不报错"（{@code frontend/src/api/assistants.ts}）， 而页面是<strong>乐观更新 + 失败回滚</strong>
 * —— 一个"已经收藏过"的 400 会让界面把刚点亮的心形回滚，看起来像"点了没反应"。
 *
 * <p>★ 收藏<strong>不是</strong>一种权限：能收藏一个应用的前提是"当前用户看得见它"（服务层用 {@code requireVisible}
 * 保证），但收藏<strong>不</strong>改变可见性，也不出现在任何范围谓词里 —— 它只回答"我的市场页该不该点亮心形"。
 */
public interface AssistantFavoriteRepository {

  /** 收藏（幂等：已经收藏过不报错、不产生第二行）。 */
  void add(UUID assistantId, UUID userId);

  /** 取消收藏（幂等：本来就没收藏也不报错）。 */
  void remove(UUID assistantId, UUID userId);

  /** 这一条应用是否被这个用户收藏。 */
  boolean exists(UUID assistantId, UUID userId);

  /**
   * 批量回答"这些应用里哪几个被我收藏"。
   *
   * <p>★ 列表页不能用 {@link #exists} 逐条问（每页 20 个 ⇒ 20 次查询）；这个方法是"一次 IN 查询"的形状。
   *
   * @param assistantIds 页内应用的 id；<strong>空集合直接返回空集</strong>（调用方不必先判空，实现也不会拼出 {@code in ()} 这种非法
   *     SQL）
   */
  Set<UUID> favoritedAmong(UUID userId, Collection<UUID> assistantIds);
}
