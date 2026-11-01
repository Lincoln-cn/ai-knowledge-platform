package cn.lincoln.aiplatform.platform.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 检索链路的时间预算与低配开关（{@code 10 §6.2} 第 6 项）。
 *
 * <h3>★ 为什么这两个数必须可配</h3>
 *
 * <p>它们原来是两个 {@code static final} 常量（各 5000ms），而<b>单次模型调用多久取决于硬件</b>：
 *
 * <ul>
 *   <li>GPU + 7B：一次改写 ~0.3–1s ⇒ 5s 预算绰绰有余；
 *   <li>★ <b>CPU + 1.5B（实测）</b>：一次改写 <b>4–5s</b> ⇒ 查询理解要调两次（一次重试） 就是 <b>7.9s</b>，5s 预算在<b>第 0
 *       路</b>就耗尽，日志是 {@code 检索预算 5000ms 已耗尽（已跑 0 路）}，随后整条链路降级成单路 ——
 *       用户看到的是一次"慢且没改写"的检索，而根因是<b>预算和硬件不匹配</b>。
 * </ul>
 *
 * <p>⇒ 预算从常量变配置。★ 默认值<b>保持 5000</b>（与设计册 {@code 10 §6.2} 一致， 不改变已有部署的行为）；低配环境用 {@code
 * --spring.profiles.active=lowend} 覆盖（见 {@code application-lowend.yml}）。
 *
 * <h3>★★ {@code total-budget-ms} 量的是"检索阶段"，不含查询理解（{@code #230}）</h3>
 *
 * <p>两者同为 5000 时，"查询理解用了 4.9s" 与 "检索总预算 5s" 是同一把尺子 ⇒ 查询理解只要不是瞬间返回， 检索在第 0
 * 路就被判"预算耗尽"，整条链路静默降级成单路（真栈读数 B1 在两轮之间从 99% 摆到 67%）。 现在检索的时钟从查询理解**之后**开始（{@code
 * RetrievalService#hitTest} 里的 {@code retrievalStartedAt}）， 所以这两个数**可以**相等乃至反过来 —— 但相等意味着"查询理解 +
 * 检索"的总上界是两倍， 对 {@code ROADMAP §二} 的 B4（首字 ≤10s）不友好，低配环境仍建议显式调大或用 {@code lowend} profile。
 *
 * <h3>★ 三个"低配"开关，各自省的是不同的东西</h3>
 *
 * <table border="1">
 *   <caption>开关与代价</caption>
 *   <tr><th>开关</th><th>关掉省什么</th><th>损失什么</th></tr>
 *   <tr>
 *     <td>{@code query-understanding-enabled}</td>
 *     <td>★ 一次（或两次）模型调用，CPU 上 = 4–10s</td>
 *     <td>查询改写 / 子问题拆分（口语化问题、指代、多意图会变差）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code query-understanding-retry-on-parse-failure}</td>
 *     <td>★ 解析失败时的<b>第二次</b>调用，CPU 上 = 4–5s</td>
 *     <td>"模型多说了一句话"这类可修复的格式问题不再自愈</td>
 *   </tr>
 *   <tr>
 *     <td>{@code hop-budget-ratio}</td>
 *     <td>多跳那一轮的预算水位（按比例提前停）</td>
 *     <td>更早放弃多跳（低配本来就跑不完）</td>
 *   </tr>
 * </table>
 *
 * <p>★ <b>关闭查询理解 ≠ 降级</b>：{@link #queryUnderstandingEnabled()} 为 {@code false} 时
 * 检索<b>直接按原始问题单路走</b>，{@code degradeLevel} 保持 0 —— 那是<b>主动选择</b>， 不是"某条路失败了"。两者在 trace
 * 里必须能分开，否则"关掉了"会被读成"坏了"。
 *
 * @param queryUnderstandingBudgetMs 单次查询理解调用的超时（毫秒）
 * @param totalBudgetMs 检索阶段<strong>净</strong>预算（毫秒，<strong>不含查询理解</strong>）—— 每一路开始前检查余量
 * @param hopBudgetRatio 多跳的"该收手了"水位（{@code 10 §6.3}：D1 = 用掉 60%）
 * @param queryUnderstandingEnabled 是否做查询理解（低配可关，直接单路）
 * @param queryUnderstandingRetryOnParseFailure 解析失败时是否重试一次
 */
@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalBudgetProperties(
    @DefaultValue("5000") long queryUnderstandingBudgetMs,
    @DefaultValue("5000") long totalBudgetMs,
    @DefaultValue("0.6") double hopBudgetRatio,
    @DefaultValue("true") boolean queryUnderstandingEnabled,
    @DefaultValue("true") boolean queryUnderstandingRetryOnParseFailure) {

  /**
   * 构造即校验（不是 {@code @PostConstruct}）：值的合法性属于<strong>这个类型</strong>， 于是"直接 {@code new} 一个"的测试与"由 Boot
   * 绑定"的生产走同一条检查。
   *
   * @throws IllegalArgumentException 任一数值不合法
   */
  public RetrievalBudgetProperties {
    if (queryUnderstandingBudgetMs <= 0) {
      throw new IllegalArgumentException("查询理解预算必须为正（毫秒）：" + queryUnderstandingBudgetMs);
    }
    if (totalBudgetMs <= 0) {
      throw new IllegalArgumentException("检索总预算必须为正（毫秒）：" + totalBudgetMs);
    }
    if (hopBudgetRatio <= 0 || hopBudgetRatio > 1) {
      throw new IllegalArgumentException(
          "多跳水位必须在 (0, 1] 之间（0.6 = 用掉 60% 就不再开新一轮）：" + hopBudgetRatio);
    }
    // ★ 重试只在"做查询理解"的前提下有意义：开了重试却关了理解，是一个自相矛盾的配置，
    //   而它的表现是"配置看起来生效了、实际一次都没调用" —— 这类静默不生效要在启动时就拒绝。
    if (!queryUnderstandingEnabled && queryUnderstandingRetryOnParseFailure) {
      throw new IllegalArgumentException("关闭查询理解时，'解析失败重试' 无意义 —— 请一并设为 false（否则这个配置看起来生效、实际不会发生）");
    }
  }
}
