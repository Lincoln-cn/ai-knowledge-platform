package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 问答对的来源（Issue {@code #78} 数据层留缝）—— 库存值 / 线上值 / 标识符三者同名。
 *
 * <p>★ 取值一律 {@code SCREAMING_SNAKE_CASE}（{@code 14 §2.1}）。展示文案（"会话沉淀"等）只出现在界面 label
 * 映射里，<strong>不进标识符、不进线上值、不进库</strong>。
 *
 * <h3>★ 为什么它是枚举而不是自由串</h3>
 *
 * <p>这三条渠道各自决定「<strong>这条问答对凭什么可信、被质疑时怎么追溯</strong>」：
 *
 * <ul>
 *   <li>{@link #CONVERSATION} —— 会话里被确认有用的一问一答（第 17 周才有数据源）
 *   <li>{@link #FEEDBACK} —— 赞踩沉淀出来的（第 18 周才有数据源）
 *   <li>{@link #MANUAL} —— 人工导入。★ 只有这一条是<strong>人标定</strong>的，而 {@code 10 §7.4} 把问答对定为
 *       「策略层唯一的<strong>确定性</strong>召回手段」，靠的正是"人工标定"这四个字
 * </ul>
 *
 * <p>写成自由串的话，这三种来源在库里长得一样，而"这条是模型编的还是人标定的"是<strong>判据级</strong>的差别 —— 系统只会拿它当普通文本比对，也不会有 check
 * 约束拦下拼错的取值。
 */
public enum QaPairOrigin {

  /** 会话消息沉淀（第 17 周的会话消息是它的数据源）。 */
  CONVERSATION,

  /** 赞踩沉淀（第 18 周的反馈是它的数据源）。 */
  FEEDBACK,

  /** 人工导入。★ 本批唯一真正产数据的那一种。 */
  MANUAL,

  /**
   * 文档加工沉淀（<strong>预留</strong>）。
   *
   * <p>★ 现在没有任何写入路径会产生它。留着它是刻意的：文档版本与重新加工（{@code #86}）将来会需要"这条问答对是从
   * 哪一版文档抽出来的"，而<strong>那个取值一旦需要就是历史数据要回填</strong> —— 与密级那一列是同一条理由。
   */
  DOCUMENT;
}
