package cn.lincoln.aiplatform.platform.guard;

import java.util.List;

/**
 * 「最近 N 天拦截」一屏的读数：汇总 + 明细。
 *
 * <p>★ 两个都要：汇总回答"严重不严重"（一眼看总量与分类分布），明细回答"是哪一次" （谁、什么时候、哪个会话）。只给汇总时管理员看到"色情 2 次"就断了 ——
 * 而他下一个问题必然是那两次是哪两次。
 *
 * @param days 窗口天数（实际生效值，已夹到 1–90）
 * @param total 窗口内总拦截次数
 * @param byCategory 按分类的次数（降序）
 * @param recent 最近若干条明细（新→旧），最多 {@value #MAX_RECENT} 条
 * @param truncated 明细是否被截断（还有更早的没返回）
 */
public record GuardHitPage(
    int days,
    long total,
    List<GuardHitCount> byCategory,
    List<GuardHitDetail> recent,
    boolean truncated) {

  /** 明细最多返回多少条：够看"最近发生了什么"，又不至于把审计表整页拖进浏览器。 */
  public static final int MAX_RECENT = 100;
}
