package cn.lincoln.aiplatform.platform.guard;

/**
 * 围栏规则的类型（{@code ROADMAP §11.53.4}）。
 *
 * <p>★ 只有两种，且**不是穷举的终点**：用户 2026-11-16 拍板「先做关键词和敏感问题集」。语义匹配、正则、 模型判定这些以后若要加，加枚举值即可 —— 迁移里的 {@code
 * kind} 是 varchar 而不是 PostgreSQL enum，正是为了不锁死。
 *
 * <p>★ 但**今天没有第三种的匹配实现**：写别的值会在服务层被挡成 400，而不是悄悄走"关键词"那条路 （静默降级会让"我配了正则"的人以为自己配上了）。
 */
public enum GuardRuleKind {

  /**
   * 关键词：归一化后「包含」即命中。
   *
   * <p>★ 它挡的是"说出那个词"，挡不住换个说法 —— 这是已知且写下来的边界，不是缺陷。
   */
  KEYWORD("关键词"),

  /**
   * 敏感问题集：归一化后**去掉标点**再「包含」即命中。
   *
   * <p>★ 与关键词的差别只在归一化口径：整句提问的标点、空格、全半角差别不该决定拦不拦。 代价是更容易误伤（一句正常问题里嵌了同款措辞也会命中）——
   * 所以它单独成类，运营可以只开关键词、把问题集留着。
   */
  QUESTION("敏感问题");

  private final String label;

  GuardRuleKind(String label) {
    this.label = label;
  }

  /** 界面上的中文名。 */
  public String label() {
    return label;
  }
}
