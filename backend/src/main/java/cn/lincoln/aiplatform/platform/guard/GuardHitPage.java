package cn.lincoln.aiplatform.platform.guard;

import java.util.List;

/**
 * 「拦截记录」一屏的读数：汇总 + 明细 + 分页。
 *
 * <p>★ 两个都要：汇总回答"严重不严重"（一眼看总量与分类分布），明细回答"是哪一次"（谁、什么时候、哪个会话）。 只给汇总时管理员看到"色情 2 次"就断了 ——
 * 而他下一个问题必然是那两次是哪两次。
 *
 * <p>★ 为什么要分页而不是"只给最近 N 条"：最近 N 条是"看一眼"的口径，回答不了"这个月一共拦了多少、第 87 条是什么"。 窗口与页码都是查询参数，全部拦截记录都在（{@code
 * days=0} 表示不限时间）。
 *
 * @param days 窗口天数；0 表示不限时间（全部）
 * @param total 窗口内总拦截次数（用于分页）
 * @param byCategory 按分类的次数（降序）
 * @param recent 当前页的明细（新→旧）
 * @param page 当前页码（从 1 开始）
 * @param size 每页条数
 */
public record GuardHitPage(
    int days,
    long total,
    List<GuardHitCount> byCategory,
    List<GuardHitDetail> recent,
    int page,
    int size) {

  /** 每页最多返回多少条：够看一屏，又不至于把审计表整页拖进浏览器。 */
  public static final int MAX_SIZE = 100;

  /** 默认每页条数。 */
  public static final int DEFAULT_SIZE = 20;

  /** 明细表是否还有下一页。 */
  public boolean hasMore() {
    return (long) page * size < total;
  }
}
