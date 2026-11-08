package cn.lincoln.aiplatform.conversation.domain;

/**
 * 消息种类（{@code #286} · 迁移 {@code V87} 的 {@code cv_message.kind}）。
 *
 * <p>★ 为什么不复用 {@link MessageRole}：开场白<strong>是</strong>助手角色（它显示在回答那一侧），
 * 「谁说的」与「这句话是怎么来的」是两件正交的事。把开场白做成第三个角色会让所有 {@code role != USER} 的判断都要重新想一遍，而那些判断分布在渲染、引用、反馈三处。
 *
 * <p>★ 为什么不用 {@link MessageStatus} 冒充：开场白的状态是 {@code GENERATED} （它从来没有"生成中"这个阶段），与正常回答共用同一个终态 ——
 * 状态机不该为一种 根本不参与生成的条目开分支。
 */
public enum MessageKind {

  /** 正常消息：用户提问，或模型真实生成的回答。 */
  ANSWER,

  /**
   * 应用开场白：会话创建时从应用配置复制过来的第一句欢迎语。
   *
   * <p>★ 它不是模型生成的回答 —— 「重新生成」「引用溯源」「赞/踩」都不该处理它 （见 {@link Message#restart()} / {@link
   * Message#rate(MessageFeedback)} 的守卫）。
   */
  PROLOGUE
}
