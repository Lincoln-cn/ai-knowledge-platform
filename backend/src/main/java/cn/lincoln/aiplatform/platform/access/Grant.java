package cn.lincoln.aiplatform.platform.access;

import java.time.Instant;
import java.util.Set;

/**
 * 一条共享授权（{@code 07 §8.1}）。
 *
 * <p>★ 为什么不用 {@code { userIds, roleCodes }} 那种"最简形状"：**共享的演化空间最大** ——
 * 很快会需要"只读地共享给这个角色""到某天失效""被共享者可以再分享给别人"。 一个 {@code Grant} 把这三件事都留了位置，而最简形状每加一条都要改契约、改库、改判定。
 *
 * @param actions 授权动作，与权限点同一套词汇（{@code knowledge:view} / {@code assistant:edit} …）
 * @param expiresAt 时效；{@code null} 表示长期有效
 * @param canDelegate 被共享者能否再分享给别人
 */
public record Grant(Subject subject, Set<String> actions, Instant expiresAt, boolean canDelegate) {

  /**
   * 这条授权此刻是否仍然有效。
   *
   * <p>★ 判定必须带上时限检查：只存 {@code expiresAt} 而不查它，等于写了一个永远不会过期的共享。
   */
  public boolean isEffectiveAt(Instant now) {
    return expiresAt == null || expiresAt.isAfter(now);
  }
}
