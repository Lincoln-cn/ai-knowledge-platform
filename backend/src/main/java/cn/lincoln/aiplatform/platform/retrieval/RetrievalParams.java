package cn.lincoln.aiplatform.platform.retrieval;

/**
 * 一轮问答真正生效的检索参数（应用级配置的<strong>值对象</strong>）。
 *
 * <h3>★ 为什么它住在 {@code platform}（而不是 assistant.domain 或 conversation）</h3>
 *
 * <p>三个模块都要说同一件事，而它们之间没有依赖边：{@code assistant} 存它（{@code as_assistant.retrieval_*}）、 {@code
 * conversation} 消费它（问答链路）、检索侧拥有它的语义（{@link RetrievalMode} / {@link NoRefStrategy}）。
 * 与那两个枚举同一条做法：<strong>契约词汇放共享内核</strong>，同一个概念只许有一份定义。
 *
 * <h3>★★ 它刻意不带 {@code queryCount / decomposeEnabled / maxSubQuestions}</h3>
 *
 * <p>那三个是「查询理解」的参数，只有 {@code RetrievalService.hitTest} 那条链消费它们；问答链路今天不做查询理解 （{@code #185} 的拆分：先把阈值
 * / TopN / 无引用策略接线，模式与思考另行处理）。把它们放进本对象会让"应用配了 3 条查询" 看起来已经生效 —— 那正是 {@code #185} 要消灭的那种假象。
 *
 * @param mode 检索模式。★ 问答链路今天只实现全文一路；非 {@code FULLTEXT} 会按全文执行并在日志里留痕（不是静默降级）
 * @param threshold 相似度 / 词覆盖率下限（0–1）。低于它的命中不进上下文
 * @param topN 引用条数上限
 * @param noRefStrategy 零引用时的策略；{@code REFUSE} ⇒ 不调用模型，直接回固定拒答文案
 */
public record RetrievalParams(
    RetrievalMode mode, double threshold, int topN, NoRefStrategy noRefStrategy) {

  /**
   * 通用问答（没有应用）的默认值：全文、阈值 0、TopN 5、自由回答。
   *
   * <p>★ 阈值 0 与"平台设置读不到"同义（{@code ChatPolicyResolver} 缺席时的旧行为），不是产品默认；平台设置存在时以它为准。
   */
  public static RetrievalParams platformDefaults() {
    return new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);
  }

  /** 空值语义：模式缺省按全文，策略缺省按拒答（与 {@link RetrievalMode#parse}/{@link NoRefStrategy#parse} 一致）。 */
  public RetrievalParams {
    mode = mode == null ? RetrievalMode.FULLTEXT : mode;
    noRefStrategy = noRefStrategy == null ? NoRefStrategy.REFUSE : noRefStrategy;
  }
}
