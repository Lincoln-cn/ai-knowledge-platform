package cn.lincoln.aiplatform.platform.guard;

/**
 * 一段时间内某一分类的拦截次数（{@code GET /api/v1/guard/hits} 的一行）。
 *
 * <p>★ 只有分类与次数，<b>没有</b>被拦的原文、也没有命中的词：这个接口是给运营看"哪一类在涨"的， 不是给任何人取一份词表副本的通道。
 *
 * @param categoryCode 分类机器码；审计里没写分类的历史记录归 {@code UNKNOWN}
 * @param hits 次数
 */
public record GuardHitCount(String categoryCode, long hits) {}
