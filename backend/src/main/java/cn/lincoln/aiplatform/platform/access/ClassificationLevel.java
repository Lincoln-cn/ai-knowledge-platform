package cn.lincoln.aiplatform.platform.access;

/**
 * 密级（企业四级）—— {@code 13} 册的数据隔离轴。
 *
 * <p>★ <strong>这四级是工程惯例，不是法定密级</strong>。法定的只有《保守国家秘密法》的 绝密 / 机密 / 秘密。选"公开 / 内部 / 秘密 /
 * 机密"是因为企业内控需要这四档， 但<strong>不要在文档或界面上声称它是法定定密</strong> —— 那是一句会引来麻烦的话。
 *
 * <p>★ <strong>声明顺序即密级高低</strong>（{@code ordinal} 递增 = 越来越敏感）。 判定"能不能看"时用的是"资源的密级 ≤
 * 我的上限"，所以这个顺序是<em>语义的一部分</em>， 不是排版：往中间插一个级别会让所有历史比较悄悄变味。要加就在两端加。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code PUBLIC / INTERNAL / SECRET /
 * CONFIDENTIAL}）， 标识符 = 线上值 = 库存值三者同名。展示文案（"公开 / 内部 / 秘密 / 机密"）只出现在前端 label 映射里，
 * <strong>不进标识符、不进线上值、不进库</strong> —— 机器码稳定、可比较、可排序；文案随时可改而不影响契约与数据。
 */
public enum ClassificationLevel {
  PUBLIC,
  INTERNAL,
  SECRET,
  CONFIDENTIAL;

  /** 这个密级是否**不高于**给定的上限（即"持有该上限的人能看它"）。 */
  public boolean within(ClassificationLevel ceiling) {
    return ceiling != null && this.ordinal() <= ceiling.ordinal();
  }

  /**
   * 取两者中更**敏感**的那个。
   *
   * <p>用于密级的**传播**：分段继承文档、回答取所有引用的最大值（{@code 13 §2}）。 传播一律"就高不就低" —— 反过来会把一次越权变成一次静默的信息泄漏。
   */
  public ClassificationLevel max(ClassificationLevel other) {
    if (other == null) {
      return this;
    }
    return this.ordinal() >= other.ordinal() ? this : other;
  }
}
