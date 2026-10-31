package cn.lincoln.aiplatform.knowledge.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 「删除多久之后还算在保留期内」的唯一口径。
 *
 * <p>★ 为什么单独一个类：这个判断有两处消费者 —— 回收站列表/恢复（`restorable`）与超期清理作业 （`cutoff`）。两处各写一遍 `minus`/`plus`
 * 看似一样，但它们对**边界**的处理迟早会分叉， 而分叉的症状很难看出来：列表说"还能恢复"、恢复说"已过期"，用户点两下就懵。
 *
 * <p>★ 边界口径（写死在这里，别处不要重写）：**恰好等于截止时刻算"过期"** —— 保留期是「删完之后 N 天之内」，第 N 天那一刻起就不再属于保留期。
 */
public final class RetentionWindow {

  private RetentionWindow() {}

  /**
   * 保留期的起点：早于它的都已过期。
   *
   * @param now 现在（由调用方传入，便于用固定时钟断言）
   * @param days 保留天数（≤ 0 视为「立刻过期」，即截止时刻就是 now）
   */
  public static Instant cutoff(Instant now, int days) {
    return days <= 0 ? now : now.minus(Duration.ofDays(days));
  }

  /**
   * 删除时间是否仍在保留期内（可用于恢复）。
   *
   * @param deletedAt 删除时间；{@code null} 表示没删过 ⇒ 由调用方判断，这里返回 false
   */
  public static boolean within(Instant deletedAt, Instant now, int days) {
    return deletedAt != null && cutoff(now, days).isBefore(deletedAt);
  }
}
