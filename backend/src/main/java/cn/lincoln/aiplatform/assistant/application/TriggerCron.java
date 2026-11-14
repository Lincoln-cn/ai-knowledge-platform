package cn.lincoln.aiplatform.assistant.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/**
 * 5 段 UNIX cron 的解析与「下次该跑的时刻」计算（{@code #295} / 0.7.3 的触发器）。
 *
 * <p>★ <strong>用 Spring 的 {@link CronExpression} 而不是自写解析器</strong>：cron 的难点不在语法 （{@code * / range /
 * list}），在语义边界 —— 日与周同时指定时是「与」还是「或」、月末没有 31 号时跳过、 闰年 2 月 29 日。这些自己写一遍等于把日历的坑重踩一次，而 {@code
 * CronExpression} 已经在框架里、 且与本项目 {@code @Scheduled} 同源。
 *
 * <p>★ <strong>对外只收 5 段，内部补一个秒位</strong>：{@code CronExpression} 吃的是 Spring 的 6 段 （秒 分 时 日 月
 * 周），而所有人写 cron 时脑子里都是 5 段。所以这里收 5 段、拼成 {@code "0 " + cron} 再交出去 ——
 * 用户少写一段、不少一类"为什么它没按我写的跑"。六段（带秒）刻意**不支持**： 界面上写对秒级 cron 的人极少，而"每分钟第 0 秒"与"每分钟"在用户脑子里是同一件事。
 *
 * <p>★ 时区取<strong>服务进程的默认时区</strong>并把它显示给用户（{@link #describe}）。用户写 {@code 0 9 * * 1-5}
 * 想的是"每个工作日九点"，而九点是哪个时区的九点 —— 只有显示出来才不会被误解。
 */
public final class TriggerCron {

  private TriggerCron() {}

  /** 校验 cron，返回错误文案（直接给用户看）；能解析就给 {@code empty}。 */
  public static Optional<String> validate(String cron) {
    if (cron == null || cron.isBlank()) {
      return Optional.of("没有写触发时间：需要一个 5 段的 cron 表达式，例如 0 9 * * 1-5（每个工作日九点）");
    }
    String trimmed = cron.trim();
    int fields = trimmed.split("\\s+").length;
    if (fields != 5) {
      return Optional.of("cron 要 5 段（分 时 日 月 周），现在写了 " + fields + " 段：" + trimmed);
    }
    try {
      CronExpression.parse(toSpringCron(trimmed));
      return Optional.empty();
    } catch (IllegalArgumentException ex) {
      return Optional.of("cron 看不懂：" + ex.getMessage());
    }
  }

  /**
   * 从 {@code after} 之后的下一次触发时刻。
   *
   * <p>★ 返回 {@link Optional#empty()} 只表示"这个表达式在这个时间点之后不再有下一次"（例如 {@code 0 0 29 2 *}
   * 跨过闰年之后的窗口），不是解析失败 —— 解析失败在 {@link #validate} 那一关就拦掉了。
   */
  public static Optional<Instant> next(String cron, Instant after) {
    ZonedDateTime base = ZonedDateTime.ofInstant(after, ZoneId.systemDefault());
    ZonedDateTime next = CronExpression.parse(toSpringCron(cron.trim())).next(base);
    return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
  }

  /**
   * 从 {@code from} 之后到现在之间，这个表达式还有几个应跑的时刻（不含 {@code from} 本身）。
   *
   * <p>★ <strong>逐次推进而不是用平均周期做除法</strong>：{@code 0 9 * * 1-5} 的"周期"是 24 小时还是 72 小时取决于今天星期几 ——
   * 用除法在跨周末时会把"错过 1 轮"算成"错过 3 轮"，而用户看的是 "我漏了哪几天"。
   *
   * @param limit 上限，防止一个很早的 {@code from} 让这里跑很久（超出后返回 {@code limit}，含义是"很多"）
   */
  public static int countBetween(String cron, Instant from, Instant now, int limit) {
    int count = 0;
    Instant cursor = from;
    while (count < limit) {
      Optional<Instant> next = next(cron, cursor);
      if (next.isEmpty() || !next.get().isBefore(now)) {
        break;
      }
      count++;
      cursor = next.get();
    }
    return count;
  }

  /** 给界面看的一句话（含时区，避免"九点是哪个九点"）。 */
  public static String describe(String cron) {
    return cron.trim() + "（按 " + ZoneId.systemDefault().getId() + " 解释）";
  }

  /** 5 段 → Spring 6 段（补秒位 0）。 */
  private static String toSpringCron(String fiveFields) {
    return "0 " + fiveFields;
  }
}
