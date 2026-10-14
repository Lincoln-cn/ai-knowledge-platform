package cn.lincoln.aiplatform.conversation.domain;

/**
 * 一次问答消耗的 token（SSE 契约里 {@code done} 事件的 {@code tokens} 字段）。
 *
 * <h3>★★ 口径：它是<strong>该次问答全部模型调用的总和</strong>，不是"最后那次生成"（{@code 10 §8.4}）</h3>
 *
 * <p>查询理解、多跳判定与生成是<strong>三次</strong>（乃至更多）模型调用。若 {@code done} 只报生成那一次， {@code analytics} 的 token
 * 统计与成本口径就会<strong>全部偏低</strong>，而且偏低幅度随策略开关变化 —— 那是最难查的一类账。所以汇总这件事必须有一个<strong>唯一的载体</strong>：本
 * record 的 {@link #plus}。 调用方各写一遍"加上去"的写法，就会出现"某一处只加了 {@code completion}"这种不会报错的偏差。
 *
 * <h3>字段</h3>
 *
 * <p>三个字段与契约的 {@code {prompt, completion, total}} 逐字对应。{@code total} <strong>由前两者导出</strong>
 * （{@code prompt + completion}）而不是单独传进来：契约里 {@code total} 是同一个 payload 的第三个字段， 允许它独立取值就等于允许 {@code
 * total != prompt + completion}，而那种不一致没有任何一处会报错。
 *
 * <p>★ 两个分量都<strong>不许为负</strong>：负数只能来自"减了一下"的实现，而 token 是只增的计数器。
 *
 * @param prompt 提示词 token 数
 * @param completion 生成 token 数
 */
public record TokenUsage(int prompt, int completion) {

  public TokenUsage {
    if (prompt < 0 || completion < 0) {
      throw new IllegalArgumentException(
          "token 数不能为负（只增的计数器）：prompt=" + prompt + ", completion=" + completion);
    }
  }

  /**
   * "一次模型调用都没发生"的口径：两个分量都是 0。
   *
   * <p>★ 空和（单位元）就是 0，不是 {@code null}。契约里 {@code tokens} 是 {@code done} 的<strong>必填</strong>字段； 一个
   * {@code null} 到了前端会变成 {@code undefined}，页面读 {@code total} 时静默拿到 {@code NaN}。
   *
   * @return 全零用量
   */
  public static TokenUsage zero() {
    return new TokenUsage(0, 0);
  }

  /**
   * 汇总（{@code 10 §8.4} 的"全部模型调用之和"）。
   *
   * @param other 另一次调用的用量（{@code null} = 没有可加的，返回 {@code this}）
   * @return 两个分量各自相加
   */
  public TokenUsage plus(TokenUsage other) {
    if (other == null) {
      return this;
    }
    return new TokenUsage(this.prompt + other.prompt, this.completion + other.completion);
  }

  /** 契约里的 {@code total} —— 由两个分量导出（见类注释）。 */
  public int total() {
    return prompt + completion;
  }
}
