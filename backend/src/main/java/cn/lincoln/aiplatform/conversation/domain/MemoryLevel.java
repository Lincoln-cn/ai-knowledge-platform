package cn.lincoln.aiplatform.conversation.domain;

/**
 * 记忆的层级（{@code #296}，迁移 {@code V101}）。
 *
 * <p>用户 2026-11-16 明确要求分层：<strong>用户 → 用户的对话 → 通用人格化 → 跨会话</strong>。 分层的意义不在"分类好看"，而在两个都会出错的地方：
 *
 * <ul>
 *   <li><strong>召回时机</strong>：{@link #CONVERSATION} 只在<strong>当前会话</strong>召回；
 *       其余三层在<strong>所有会话</strong>召回。一个不分层的池子做不到"这条只在这次会话里有效"。
 *   <li><strong>生命周期</strong>：会话删了，{@link #CONVERSATION} 那批就该跟着失效； 而"这个人是谁"不该因为删了一次会话就没了。
 * </ul>
 *
 * <p>★ 命名上不用 {@code USER} / {@code CONVERSATION} / {@code PROFILE} 这种混合语义的名字：
 * 前两个是"范围"，后两个是"聚合产物"。这里统一成"记忆属于哪一层"。
 */
public enum MemoryLevel {

  /** 这个人是谁：部门、角色、职责范围。变化最慢，跨所有会话可见。 */
  USER("user"),

  /** 这一次会话在谈什么。{@code scope_id} = 会话 id，**只在当前会话召回**。 */
  CONVERSATION("conversation"),

  /** 表达偏好、关注领域、禁忌。由多次对话聚合而来，跨所有会话可见。 */
  PERSONA("persona"),

  /** 跨会话的事实与结论（"上次说的那个预算上限"）。跨所有会话可见。 */
  CROSS_SESSION("cross_session");

  private final String code;

  MemoryLevel(String code) {
    this.code = code;
  }

  /** 落库用的短码（与迁移里的 {@code chk_cv_memory_level} 逐字一致）。 */
  public String code() {
    return code;
  }

  /** 是否只在当前会话内召回。 */
  public boolean conversationScoped() {
    return this == CONVERSATION;
  }

  /** 从库里读回来时用：不认识的值抛异常，而不是静默当成某一层。 */
  public static MemoryLevel of(String code) {
    for (MemoryLevel level : values()) {
      if (level.code.equals(code)) {
        return level;
      }
    }
    throw new IllegalArgumentException("未知的记忆层级：" + code);
  }
}
