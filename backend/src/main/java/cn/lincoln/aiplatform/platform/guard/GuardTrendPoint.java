package cn.lincoln.aiplatform.platform.guard;

/**
 * 某一天的拦截次数。
 *
 * <p>只用于看板的趋势线，所以日期给的是 {@code yyyy-MM-dd} 字符串而不是时间类型 —— 前端要的是 x 轴的刻度标签，传一个时刻过来还得再格式化一次。
 *
 * @param day 本地日期（见 {@code GuardStore.statsByDay} 里对时区的说明）
 * @param hits 这一天的拦截次数
 */
public record GuardTrendPoint(String day, long hits) {}
