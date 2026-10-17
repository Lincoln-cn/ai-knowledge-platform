package cn.lincoln.aiplatform.conversation.domain;

/**
 * 消息级赞踩（契约 {@code ChatMessage.feedback}，取值 {@code 'UP' | 'DOWN'}）。
 *
 * <p>★ <strong>"未评价"用 {@code null} 表达，不是第三个枚举值</strong>：契约里那条字段是 {@code MessageFeedback |
 * null}（{@code api/conversations.ts:31}）。给它加一个 {@code NONE} 取值， 会让"没评价"有两种写法（{@code null} 与 {@code
 * NONE}），而读端只认一种 ⇒ 一种静默的不一致。
 *
 * <p>★ 与 {@code /feedbacks} 那族（反馈处理闭环，属 V0.2）<strong>不是一回事</strong>： 那是一个有状态机的业务流程（待处理 / 处理中 / 已解决
 * + 指派 + 沉淀到知识库）， 而这里只是"用户对这条回答的评价"。两者共用一个词，但处置完全不同。
 *
 * <p>★ 落库口径：{@code varchar(8)} + {@code chk_msg_feedback}（{@code V35}）—— 与本仓其它枚举同一条做法（应用层枚举 + 库层
 * CHECK，不建 PG 类型）。
 */
public enum MessageFeedback {
  UP,
  DOWN;

  /**
   * 解析契约取值（{@code 'UP'} / {@code 'DOWN'} / {@code null}）。
   *
   * <p>★ 刻意接受 {@code null} 并返回 {@code null}（= 取消评价）：契约里"取消"就是传 {@code null}，
   * 所以这不是"容错"，而是<strong>语义的一部分</strong> —— 写成"null 报错"会让取消赞这个动作无法表达。
   *
   * @param raw 契约里的原始取值
   * @return 枚举；{@code null} 表示取消评价
   * @throws IllegalArgumentException 取了一个不在 {@code UP}/{@code DOWN} 里的值
   */
  public static MessageFeedback parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    for (MessageFeedback value : values()) {
      if (value.name().equals(trimmed)) {
        return value;
      }
    }
    // ★ 消息里带合法取值清单：这条异常会直接变成 400 的可读原因，光写"取值不合法"等于没说怎么办
    throw new IllegalArgumentException("feedback 取值不合法：" + raw + "（合法值：UP / DOWN / null）");
  }
}
