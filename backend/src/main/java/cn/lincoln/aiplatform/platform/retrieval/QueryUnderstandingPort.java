package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;

/**
 * 查询理解端口（`#297`）：把一句用户提问变成「子问题 × 检索查询」。
 *
 * <p>★★ <strong>为什么要有这个端口</strong>：`conversation` 模块要用查询理解，而实现在 {@code retrieval} 模块。按 {@code
 * ModuleBoundaryTest} 的规则，跨模块只能用"被暴露的"类型 —— 直接 {@code import
 * cn.lincoln.aiplatform.retrieval.application.QueryUnderstandingService} 会让架构闸门报
 *
 * <pre>
 * Module 'conversation' depends on non-exposed type
 *   cn.lincoln.aiplatform.retrieval.application.QueryUnderstandingService within module 'retrieval'!
 * </pre>
 *
 * <p>★ 处置同理 {@code #293} 的 {@link HopDeciderPort}：**不是**把实现类开成 {@code @NamedInterface}
 * （那是"改闸门迁就实现"），而是在{@code platform} 里定一个只含<strong>数据形状</strong>的契约， 由 {@code retrieval}
 * 侧写一个零逻辑的薄壳适配。
 *
 * <p>★ 这里的 {@link SubQuestion} 与 {@code retrieval.domain.QueryUnderstanding.SubQuestion}
 * 是<strong>两份独立声明</strong>，不是别名 —— 端口不许反向依赖实现模块的域类型，否则这条边界白划。 两边的字段刻意逐字对应（{@code question}
 * 是这个子问题的独立问法，{@code queries} 是它对应的 1..N 条检索查询），让适配成为纯粹的搬运。
 *
 * @see cn.lincoln.aiplatform.platform.retrieval.HopDeciderPort
 */
public interface QueryUnderstandingPort {

  /**
   * 一个子问题及其检索查询。
   *
   * @param question 这个子问题的<strong>独立问题</strong>（引用与上下文标注用它，{@code 10 §3.3.4}）
   * @param queries 检索用的查询（1..queryCount 条，已去重、已截断）
   */
  record SubQuestion(String question, List<String> queries) {}

  /**
   * 一次查询理解的结果。
   *
   * <p>★ 只带<strong>调用方真的要用的</strong>那几格：降级层级用于日志与判据 1 的读数， {@code elapsedMs} 让"这一次改写花了多久"可核对。内部的
   * {@code events} / {@code truncated} / {@code parseFailed} 不往端口外带 —— 它们属于实现细节，调用方拿不到也不该拿。
   *
   * @param subQuestions 改写后的子问题列表；降级到 D4 时是"原句单路"的一条
   * @param degradeLevel 降级层级（D0 = 完全成功，数字越大降级越多）
   * @param degradeReason 可读的降级原因；未降级时为 null
   * @param elapsedMs 这次理解花了多少毫秒
   */
  record Result(
      List<SubQuestion> subQuestions, int degradeLevel, String degradeReason, long elapsedMs) {}

  /**
   * 跑一次查询理解。
   *
   * @param question 用户的原始问题（<strong>永远是保底输入</strong>）
   * @param queryCount 每个子问题最多产出几条检索查询；{@code 0} = 不改写（调用方应在此之前短路）
   * @param decomposeEnabled 是否允许把一个复合问题拆成多个子问题
   * @param maxSubQuestions 子问题数量上限
   * @param budgetMs 预算毫秒；{@code 0} = 用实现侧的配置
   * @param recentContext 最近几轮对话（供消解指代与省略；null = 没有可用上文）
   * @return 理解结果；实现必须保证<strong>不抛</strong> —— 任何失败都降级成"原句单路"返回
   */
  Result understand(
      String question,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions,
      long budgetMs,
      String recentContext);
}
