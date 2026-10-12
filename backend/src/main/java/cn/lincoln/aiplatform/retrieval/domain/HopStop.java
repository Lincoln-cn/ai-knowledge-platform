package cn.lincoln.aiplatform.retrieval.domain;

/**
 * 多跳"为什么停下"（{@code 10 §3.4.4} 的四条停止条件 + 三种"没开始/没想法"的情形）。
 *
 * <p>★ 为什么它必须是一个**枚举**而不是一句日志：{@code ROADMAP} 第 14 周的演示判据是 "埋点看到轮次与停止原因"，而 {@code 10 §7.1}
 * 要的是"多跳的边际收益" —— 这两件事都要能<strong>被数</strong>。 一句自由文本只能被人读，不能被统计，也不能被断言。
 *
 * <p>★ 前四条是设计册写死的停止条件（{@code §3.4.4} 表格的 1–4）；后三条<strong>不是停止条件</strong>，
 * 而是"迭代根本没开始"或"模型没想法了"这两类同样需要计数的事实 —— 把它们混进前四条会让 "多跳到底有没有触发"这个问题无法回答（而它正是决定这个特性值不值得保留的第一个数）。
 */
public enum HopStop {

  /** 配置里没开（{@code multiHopEnabled=false}，默认）。★ 这不是降级，是配置。 */
  NOT_ENABLED("未开启多跳"),

  /** 开了，但首轮命中已经够（{@code §3.4.2} 的触发条件不成立）。 */
  NOT_TRIGGERED("首轮命中已足够，未触发多跳"),

  /** 条件 1：模型判定已有命中足够回答（{@code sufficient=true}）——最省的停止路径。 */
  SUFFICIENT("模型判定：已有命中足够回答"),

  /** 条件 2：达到 {@code maxHops} 硬上限。 */
  MAX_HOPS("达到 maxHops 上限"),

  /** 条件 3：检索阶段时间预算耗尽（{@code §6}）。 */
  BUDGET("检索阶段时间预算耗尽"),

  /** 条件 4：本轮结果与已有结果的 Jaccard 相似度 > 0.6（防原地打转，{@code §3.4.4}）。 */
  REPEATED("本轮结果与已有结果高度重合（Jaccard > 0.6），停止以免原地打转"),

  /** 判定调用不可用（不可达 / 超时 / 解析失败）⇒ 停止迭代、用已有结果生成（{@code §3.4.6}）。 */
  JUDGE_FAILED("多跳判定不可用，改用已有结果"),

  /** 模型认为不够、但给不出下一轮查询 ⇒ 停止（不硬编，{@code §3.4.3}）。 */
  NO_QUERY("模型未能给出下一轮查询，不再硬编"),

  /** 下一轮检索 0 命中 ⇒ 停止并用已有结果（{@code §3.4.6}，也是"多跳未改善"的那笔数据）。 */
  NO_NEW_HITS("下一轮 0 命中，多跳未改善");

  private final String label;

  HopStop(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** 埋点里用的那一行：**机器可判的前缀** + 人读的说明（例：{@code SUFFICIENT：模型判定…}）。 */
  public String describe() {
    return name() + "：" + label;
  }
}
