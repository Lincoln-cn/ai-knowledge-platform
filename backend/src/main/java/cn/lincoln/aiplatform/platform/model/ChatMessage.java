package cn.lincoln.aiplatform.platform.model;

/**
 * 一条对话消息（{@code #168}：多轮上下文的最小形状）。
 *
 * <h3>★ 为什么要有这个类型，而不是把历史拼进一段字符串</h3>
 *
 * <p>本仓的 {@code ChatService.promptOf} 只拼「当前问题 + 参考资料」，历史一个字都没进去 —— 于是「多轮指代」在实现上不可能成立（{@code #168}）。
 *
 * <p>★ 而补历史有两种做法，代价完全不同：
 *
 * <ul>
 *   <li><b>拼成一段字符串</b>（便宜）：模型分不清"用户上一轮问的"与"参考资料里写的" —— 那正是反向提示注入（{@code
 *       #84}）最喜欢的形状：文档里写一句"忽略上面的要求"， 拼进去之后与用户的话长得一样；
 *   <li><b>带 role 边界传过去</b>（本类型）：{@code user} / {@code assistant} 各自成条， 上游按协议区分。★ 这是唯一不制造新注入面的做法。
 * </ul>
 *
 * @param role 谁说的
 * @param content 内容（不可空；空串没有信息量，调用方应先过滤）
 */
public record ChatMessage(Role role, String content) {

  /** 消息角色。★ 只有这两种进上游——{@code system} 目前由"提示词"承担，不在这条通道里。 */
  public enum Role {
    /** 用户。 */
    USER,
    /** 助手（上一轮的回答）。 */
    ASSISTANT
  }

  /**
   * @return 上游协议里的角色名（{@code user} / {@code assistant}）
   */
  public String wireRole() {
    return role == Role.USER ? "user" : "assistant";
  }
}
