package cn.lincoln.aiplatform.platform.dictionary;

/**
 * 字典种类（{@code 08 §十}）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（与 {@code 14 册}一致）： 标识符 = 线上值 =
 * 库存值三者同名，展示文案只出现在前端的 label 映射里。
 *
 * <p>★ 为什么是枚举而不是"又一张字典表"：种类是<strong>代码里要 switch 的东西</strong> （每一类有各自的校验规则与消费者——见 {@link
 * #CLASSIFICATION} 的特例）， 而"种类"本身没人会去加。把种类也做成数据，等于把编译期能查出来的错误挪到运行期。
 */
public enum DictionaryKind {

  /**
   * 密级。★ <strong>这一类是特例</strong>：它是**排序比较轴**，不只是清单 —— {@code kb_document.classification_rank} /
   * {@code kb_segment.classification_rank} 是生成列， {@code CASE} 写死了四档（{@code V21}/{@code
   * V22}）。所以本类<strong>禁止增删</strong>， 只能改展示名、排序与启停；加第五档要连生成列一起迁移（一次迁移 + 一次巡检）。
   */
  CLASSIFICATION,

  /** 文种（契约里叫 {@code doc_type}）。★ {@code code} 就是历史数据与召回前缀里那个值。 */
  DOC_TYPE,

  /** 提示词类型（界面上原有这个 tab，但后端一直没落地）。 */
  PROMPT_TYPE,

  /** 知识库分类。今天是从已有库的 {@code category} 去重统计出来的，现在有了真源。 */
  KB_CATEGORY
}
