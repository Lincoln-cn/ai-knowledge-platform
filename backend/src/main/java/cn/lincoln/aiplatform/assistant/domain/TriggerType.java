package cn.lincoln.aiplatform.assistant.domain;

/**
 * 编排应用的触发方式（{@code #295} / 0.7.3）。
 *
 * <p>★ 只有一个取值是刻意的：把"可以自动跑"做成一个布尔会立刻引出一个问题 —— "那它是被什么触发的"。 现在只有 cron 一种，将来加 webhook /
 * 事件时，加一个枚举值比再加一列布尔清楚得多，而 {@code as_assistant.trigger_type} 上有 check 约束钉住取值域（{@code V99}）。
 *
 * <p>★ {@code null}（不是 {@code NONE}）表示"不自动触发"：三种触发语义（无 / cron / 事件）里， "无"就是没有值。给它一个 {@code NONE} 会让
 * {@code where trigger_type = 'CRON'} 这类查询 与"没有值"的行分成两拨，而它们在业务上是同一拨。
 */
public enum TriggerType {
  /** 按 cron 表达式定时触发。 */
  CRON
}
