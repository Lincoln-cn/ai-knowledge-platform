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
 * / TopN / 无引用策略与检索模式接线，思考另行处理）。把它们放进本对象会让"应用配了 3 条查询" 看起来已经生效 —— 那正是 {@code #185} 要消灭的那种假象。
 *
 * <h3>★★ 但它带上了 {@code multiHopEnabled / maxHops}（{@code #283}）</h3>
 *
 * <p>与上面三个刚好相反：多跳原来<strong>只挂在 {@code HitTestQuery} 上</strong>，落库的检索参数里根本没有它，
 * 于是用户能在命中测试里开多跳、能看到它有效果，而<strong>真实问答一次都不会跑</strong> —— 这比"没有这个功能"更糟， 因为它<em>看起来有</em>。
 *
 * <p>⇒ 判据很简单：<strong>凡是"界面上能开、读数能看到"的策略开关，都必须在这份值对象里有一格</strong>， 否则它就只是调试工具。多跳按这条进了本对象，查询理解那三个按
 * {@code #185} 的拆分暂时不进（它们还没有消费点）。
 *
 * <p>★ 默认<strong>关</strong>：开启后每轮多一次模型调用，且会给存量知识库静默改变行为与成本。
 *
 * @param mode 检索模式。★ 三种取值都已接线：FULLTEXT 全文、VECTOR 向量、HYBRID 两路 RRF 融合； VECTOR/HYBRID 需要装配 {@code
 *     EmbeddingPort} 且知识库已绑定向量模型，缺依赖时本轮零引用（不静默降级）
 * @param threshold 相似度 / 词覆盖率下限（0–1）。低于它的命中不进上下文。★ 向量模式是余弦相似度；混合模式取两路中该分段的<strong>最大原始分</strong>
 * @param topN 引用条数上限
 * @param noRefStrategy 零引用时的策略；{@code REFUSE} ⇒ 不调用模型，直接回固定拒答文案
 */
public record RetrievalParams(
    RetrievalMode mode,
    double threshold,
    int topN,
    NoRefStrategy noRefStrategy,
    boolean multiHopEnabled,
    int maxHops) {

  /**
   * 通用问答（没有应用）的默认值：全文、阈值 0、TopN 5、自由回答。
   *
   * <p>★ 阈值 0 与"平台设置读不到"同义（{@code ChatPolicyResolver} 缺席时的旧行为），不是产品默认；平台设置存在时以它为准。
   */
  public static RetrievalParams platformDefaults() {
    return new RetrievalParams(RetrievalMode.FULLTEXT, 0.0, 5, NoRefStrategy.FREE_ANSWER);
  }

  /** 空值语义：模式缺省按全文，策略缺省按拒答（与 {@link RetrievalMode#parse}/{@link NoRefStrategy#parse} 一致）。 */
  /** 多跳轮数缺省（含首轮）：首轮 + 最多再检一轮。与 {@code HitTestQuery.DEFAULT_MAX_HOPS} 同值。 */
  public static final int DEFAULT_MAX_HOPS = 2;

  /** 多跳轮数硬顶。与 {@code HitTestQuery.MAX_HOPS_CAP} 同值 —— 两处不一致会让"界面能填、后端拒收"。 */
  public static final int MAX_HOPS_CAP = 3;

  public RetrievalParams {
    mode = mode == null ? RetrievalMode.FULLTEXT : mode;
    noRefStrategy = noRefStrategy == null ? NoRefStrategy.REFUSE : noRefStrategy;
    // ★ 轮数收敛到 [1, MAX_HOPS_CAP]：越界不抛（存量数据可能没有这一列 ⇒ 0），
    //   但也不能让 0 轮/99 轮传到循环里 —— 那条错误会出现在问答链路上，离配置很远。
    maxHops = maxHops < 1 ? DEFAULT_MAX_HOPS : Math.min(maxHops, MAX_HOPS_CAP);
  }

  /** 不跑多跳的版本（旧签名，供存量调用点与测试用）。 */
  public RetrievalParams(
      RetrievalMode mode, double threshold, int topN, NoRefStrategy noRefStrategy) {
    this(mode, threshold, topN, noRefStrategy, false, DEFAULT_MAX_HOPS);
  }
}
