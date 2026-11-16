package cn.lincoln.aiplatform.platform.guard;

/**
 * 一段时间内某一分类的拦截次数（{@code GET /api/v1/guard/hits} 与 {@code /guard/stats} 的一行）。
 *
 * <p>★ 只有分类与次数，<b>没有</b>被拦的原文、也没有命中的词：这个接口是给运营看"哪一类在涨"的， 不是给任何人取一份词表副本的通道。
 *
 * <p>★ {@code categoryName} 由后端现场从 {@code pf_guard_category} 取，<b>前端不许自己写一张码→名的表</b>：
 * 分类是运营配置项（可以新建、可以改名），前端复制一份的话，改名之后界面上还是旧名、 自建分类则只剩一个机器码。分类被删掉时这里为 {@code null}，读的人按 {@code
 * categoryCode} 回落。
 *
 * @param categoryCode 分类机器码；审计里没写分类的历史记录归 {@code UNKNOWN}
 * @param categoryName 分类展示名；分类已不存在时为 {@code null}
 * @param hits 次数
 */
public record GuardHitCount(String categoryCode, String categoryName, long hits) {}
