package cn.lincoln.aiplatform.platform.guard;

import java.util.List;

/**
 * 安全围栏的聚合读数：「拦了多少、哪类多、在涨还是在落」。
 *
 * <p>★ 这是**聚合**，与 {@link GuardHitDetail} 的逐条事实是两件事： 看板回答"这个月比上个月多了吗"，拦截记录回答"具体是哪一次"。
 * 合成一个接口就得把明细一起拉过来，看板一打开就是几百行 JSON。
 *
 * <p>★ 数据源仍是 {@code pf_audit_log}（action = {@code guard.block}），不另建计数表 —— 同一件事两处记账，早晚会不一致。
 *
 * @param days 统计窗口（天）；{@code 0} 表示不限时间
 * @param total 窗口内拦截总次数
 * @param byDay 按本地日期聚合的趋势（只有有拦截的日期才出现，前端要自己补零）
 * @param byCategory 按分类聚合的分布，次数多的在前
 */
public record GuardStats(
    int days, long total, List<GuardTrendPoint> byDay, List<GuardHitCount> byCategory) {}
