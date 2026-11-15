package cn.lincoln.aiplatform.conversation.domain;

/**
 * 记忆的种类（{@code #296}，迁移 {@code V101}）。
 *
 * <p>它与 {@link MemoryLevel} 是两个正交的维度，不能互相推导：
 *
 * <ul>
 *   <li>{@code level} 决定<strong>什么时候召回</strong>（会话层只在当前会话）；
 *   <li>{@code kind} 决定<strong>做梦时怎么合并</strong>（同 kind 才允许合并，否则"他是个谨慎的人"会被并进"预算上限 300 万"）。
 * </ul>
 *
 * <p>★ 一个反例说明为什么两者不能合并成一个枚举："这次谈的是 A 项目的预算"是 {@code FACT} 但只在会话层有意义； "他习惯先要结论"是 {@code PREFERENCE}
 * 但跨所有会话 —— 同一 kind 会出现在不同 level。
 */
public enum MemoryKind {

  /** 事实：可以核对的一句话（"预算是 300 万"）。 */
  FACT("fact"),

  /** 偏好：表达与组织上的倾向（"先结论后依据"）。 */
  PREFERENCE("preference"),

  /** 约束：不能违反的边界（"不要用英文缩写"）。 */
  CONSTRAINT("constraint"),

  /** 目标：他在推进的事（"在做安全生产合规改造"）。 */
  GOAL("goal"),

  /** 事件：发生过的事（"上周问过消防法处罚条款"）。 */
  EVENT("event"),

  /** 摘要：一段对话的提炼（做梦产出，通常带 {@code source_message_ids}）。 */
  SUMMARY("summary"),

  /**
   * 会话约定：这一次会话里说定的做法（"按 2025 版算""用中文回答"）。
   *
   * <p>★ 它<strong>只许出现在会话层</strong>（迁移 {@code V102} 的 {@code chk_cv_memory_convention_scope}
   * 把这条约束钉在库里）：一个"只在这次会话成立的 约定"被升到 {@code USER} 层，下一次对话就会按一个早已结束的约定办事，而用户完全不知道。
   *
   * <p>★ 与 {@link #EVENT} 的区别：{@code EVENT} 是"发生过的事"（历史），{@code CONVENTION}
   * 是"说定的做法"（规则）。混在一起会让"忘掉一条旧事件"和"违反一条约定"变得无法区分。
   */
  CONVENTION("convention");

  private final String code;

  MemoryKind(String code) {
    this.code = code;
  }

  /**
   * 落库用的短码（与迁移里的 {@code chk_cv_memory_kind} 逐字一致）。
   *
   * @return 短码
   */
  public String code() {
    return code;
  }

  /**
   * 从库里读回来时用：不认识的值抛异常，而不是静默归成 {@link #FACT}。
   *
   * <p>★ 静默归类会让"未知种类"永远不报错，而它恰恰是新旧版本不一致的早期信号。
   *
   * @param code 短码
   * @return 种类
   */
  public static MemoryKind of(String code) {
    for (MemoryKind kind : values()) {
      if (kind.code.equals(code)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("未知的记忆种类：" + code);
  }
}
