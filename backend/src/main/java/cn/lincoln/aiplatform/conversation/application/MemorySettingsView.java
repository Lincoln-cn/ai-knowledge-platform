package cn.lincoln.aiplatform.conversation.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;

/**
 * 记忆设定（{@code #296}，表 {@code cv_memory_setting}，单行 · 迁移 {@code V101}）。
 *
 * <p>★ 用 record 而不是 JPA 实体：它是<strong>单行、七个标量、零聚合行为</strong>的配置行， 唯一的不变量（范围校验）在 {@link #checked()}
 * 里，不需要聚合身份（判据与 {@code ChatSettingsView} 相同）。
 *
 * @param enabled 总开关（关掉后既不再写、也不再召回）
 * @param minImportance 召回的重要度下限（1–5）
 * @param recallLimit 一次召回最多注入几条（1–20）
 * @param injectProfile 是否把画像整段注入
 * @param dreamCron 做梦任务的 cron（Spring 六段式）
 * @param forgetAfterDays 多少天没被用到就可以遗忘（7–3650）
 * @param lastDreamAt 上次做梦的时刻（{@code null} = 从来没跑过）
 * @param lastDreamNote 上次做梦的结果摘要（一句话，给界面用）
 * @param updatedAt 最后修改时间
 * @param updatedBy 最后修改人
 */
public record MemorySettingsView(
    boolean enabled,
    int minImportance,
    int recallLimit,
    boolean injectProfile,
    String dreamCron,
    int forgetAfterDays,
    Instant lastDreamAt,
    String lastDreamNote,
    Instant updatedAt,
    UUID updatedBy) {

  /** 召回条数上限（与迁移里的 check 一致）。 */
  public static final int MAX_RECALL_LIMIT = 20;

  /** 遗忘天数上限。 */
  public static final int MAX_FORGET_DAYS = 3650;

  /** 遗忘天数下限（再小会把一周前的正常记忆删掉）。 */
  public static final int MIN_FORGET_DAYS = 7;

  /**
   * 校验并返回自身。
   *
   * <p>文案带当前值：用户看到的应该是"现在写的是 30"，而不是"参数非法"。
   *
   * @return 自身
   * @throws IllegalArgumentException 越界
   */
  public MemorySettingsView checked() {
    if (minImportance < 1 || minImportance > 5) {
      throw new IllegalArgumentException("重要度下限要在 1～5 之间，现在写的是 " + minImportance);
    }
    if (recallLimit < 1 || recallLimit > MAX_RECALL_LIMIT) {
      throw new IllegalArgumentException(
          "召回条数要在 1～" + MAX_RECALL_LIMIT + " 之间，现在写的是 " + recallLimit);
    }
    if (forgetAfterDays < MIN_FORGET_DAYS || forgetAfterDays > MAX_FORGET_DAYS) {
      throw new IllegalArgumentException(
          "遗忘天数要在 " + MIN_FORGET_DAYS + "～" + MAX_FORGET_DAYS + " 之间，现在写的是 " + forgetAfterDays);
    }
    if (dreamCron == null || dreamCron.isBlank()) {
      throw new IllegalArgumentException("做梦时间不能为空（留空请改成关闭做梦，而不是清空 cron）");
    }
    /*
     * ★ cron 必须在这里解析，而不是留给调度器：这一格现在是运行期调度的唯一权威
     *   （见 MemoryDreamScheduler），一个看不懂的表达式存进库里等于把"做梦"静默关掉 ——
     *   调度器只会在日志里 WARN，没人会去看。前端 lib/cron.ts 有同规则的检查，但那只是
     *   "早一点告诉用户"，不能替后端做决定。
     */
    try {
      CronExpression.parse(dreamCron.trim());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "做梦时间看不懂：" + error.getMessage() + "（要 6 段：秒 分 时 日 月 周，例如 0 40 3 * * *）", error);
    }
    return this;
  }
}
