package cn.lincoln.aiplatform.retrieval.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 集合重合度（{@code 10 §3.4.4} 停止条件 4）。
 *
 * <p>★ 它守的是<strong>"原地打转"</strong>这件事：模型给不出新查询时，会换一种说法再问一遍同一件事， 而"轮数"看不出这个问题 —— 第 2 轮回来了 8 条、其中 7
 * 条第 1 轮就有，那和第 1 轮**没有区别**。 用集合重合度识别比用轮数准：{@code > 0.6} 就说明这一轮基本是重复劳动，停。
 *
 * <p>★ 为什么是"两轮的 segmentId 集合"而不是"查询字符串相似度"：查询的措辞可以完全不同而结果一样 （这正是 RAG
 * 里最常见的重复），而**结果集合**才是这次迭代真正拿回来的东西。
 */
public final class Jaccard {

  /** 阈值（{@code 10 §3.4.4}：默认 0.6）。 */
  public static final double REPEAT_THRESHOLD = 0.6;

  private Jaccard() {}

  /**
   * 两个集合的 Jaccard 相似度：{@code |交| / |并|}。
   *
   * <p>★ 两个都空 ⇒ 约定为 <strong>0</strong>（而不是 1、也不是 NaN）：交与并都为空时"没有新东西"这句话 本来就无从谈起，而返回 1
   * 会让"两轮都是空"被当成"原地打转"（真实原因是**什么都没找到**，是另一件事， 由 §3.4.6 的"第 2 轮 0 命中"那条分支处理）。
   */
  public static double similarity(Set<UUID> previous, Set<UUID> current) {
    if (previous == null || current == null || previous.isEmpty() || current.isEmpty()) {
      return 0.0;
    }
    Set<UUID> intersection = new HashSet<>(previous);
    intersection.retainAll(current);
    Set<UUID> union = new HashSet<>(previous);
    union.addAll(current);
    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
  }

  /** 重合到"基本是重复劳动"了吗（{@code 10 §3.4.4} 条件 4）。 */
  public static boolean repeats(Set<UUID> previous, Set<UUID> current) {
    return similarity(previous, current) > REPEAT_THRESHOLD;
  }
}
