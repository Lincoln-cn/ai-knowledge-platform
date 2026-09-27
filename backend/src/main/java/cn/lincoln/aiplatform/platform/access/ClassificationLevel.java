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
 * <p>★ 枚举名用中文，因为库里与契约里存的就是这四个中文字面量（同 {@code UserStatus} 的理由）——
 * 库、代码、契约三处是同一个字面量，中间少一层映射就少一处可能对不上的地方。
 */
public enum ClassificationLevel {
  公开,
  内部,
  秘密,
  机密;

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
