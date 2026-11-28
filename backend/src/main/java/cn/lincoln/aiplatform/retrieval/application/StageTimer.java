package cn.lincoln.aiplatform.retrieval.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索各阶段耗时的累加器（2026-11-28，用户要求「对召回的每个步骤的耗时都进行追踪」）。
 *
 * <p>★ <strong>为什么要有它</strong>：一条问句在真栈上要 20 秒，而此前只看得见两个数 ——「查询理解」 与「总计」。中间那 9
 * 秒是黑盒，于是"该优化哪里"只能靠猜，而猜错的代价是把并行加到已经吃满 CPU 的 重排上（见 {@code OnnxRerankerAdapter} 的 intra-op
 * 线程数注释：同一批候选 Python 2.6s vs 真栈 13.1s， 差 5 倍就出在那行）。有了分阶段数字，"下一步动哪"才是可核对的。
 *
 * <p>★ <strong>为什么按阶段名累加而不是逐个记时间点</strong>：多路（子问题 × 查询）与多跳会重复进入同一段， 逐个记时间点会得到几十行无法汇总的流水。按名字累加 +
 * 记次数，既能看总量，也能看"是不是重复跑了" —— 后者正是三路扩召回曾经每路子问题都重跑一遍的那个浪费。
 *
 * <p>不是线程安全的：一个 {@code detailed(...)} 调用一个实例，不跨线程共享。
 */
final class StageTimer {

  /** 阶段名 → [累计纳秒, 次数]。用 LinkedHashMap 保留首次出现的顺序，日志读起来才是流程顺序。 */
  private final Map<String, long[]> buckets = new LinkedHashMap<>();

  /**
   * 只吞不记。给「外层已经整体记了一段」的嵌套调用用 —— 比如多跳第 2 轮的检索： 它的耗时已经算在「多跳」里，再摊给「向量检索」会把同一段时间记两次，
   * 于是"各阶段之和"与"合计"的差就再也读不出"还有多少没解释"。
   */
  private final boolean silent;

  StageTimer() {
    this(false);
  }

  private StageTimer(boolean silent) {
    this.silent = silent;
  }

  static StageTimer silent() {
    return new StageTimer(true);
  }

  /**
   * 记一段耗时。
   *
   * @param name 阶段名（中文，与日志同词）
   * @param millis 毫秒（允许为 0 —— 亚毫秒的阶段不必掩盖）
   */
  /**
   * 记一段耗时。
   *
   * <p>★ 自 2026-11-28 起检索各路并行（{@code RetrievalService} 的 PARALLEL）， 这个方法会被多个线程同时调用 ⇒ 必须加锁。锁的粒度是整次
   * add（十几个指令）， 每次检索只调十几次，竞争可以忽略；换 ConcurrentHashMap + LongAdder 反而更绕。
   */
  synchronized void add(String name, long millis) {
    if (silent) {
      return;
    }
    long[] bucket = buckets.computeIfAbsent(name, key -> new long[2]);
    bucket[0] += Math.max(0, millis);
    bucket[1]++;
  }

  /**
   * 转成对外形状。
   *
   * <p>★ 末尾补一条「合计」= 调用方给的整次耗时（{@code elapsedMs}），<strong>不与各阶段之和相等</strong>： 差值是没被单独计的碎片（scope
   * 解析、日志、直答挑选…）。两个数都留着是为了让"还有多少没解释"可见 —— 只报各阶段之和，会让人以为已经 100% 解释完了。
   *
   * @param totalMs 整次检索耗时
   */
  List<HitTestView.Stage> stages(long totalMs) {
    List<HitTestView.Stage> stages = new ArrayList<>(buckets.size() + 1);
    for (Map.Entry<String, long[]> entry : buckets.entrySet()) {
      long[] bucket = entry.getValue();
      stages.add(new HitTestView.Stage(entry.getKey(), bucket[0], (int) bucket[1]));
    }
    stages.add(new HitTestView.Stage("合计", Math.max(0, totalMs), 1));
    return stages;
  }

  /**
   * 给日志用的一行摘要，例如 {@code 查询理解 3738ms · 向量检索 210ms×3 · 重排 8500ms · 合计 20469ms}。
   *
   * <p>次数为 1 时不打 {@code ×1} —— 通篇 {@code ×1} 会把真正重复的那几段淹掉。
   */
  static String describe(List<HitTestView.Stage> stages) {
    return stages.stream()
        .map(
            stage ->
                stage.count() > 1
                    ? stage.name() + " " + stage.ms() + "ms×" + stage.count()
                    : stage.name() + " " + stage.ms() + "ms")
        .collect(Collectors.joining(" · "));
  }
}
