package canaryfixture.retrieval;

import canaryfixture.conversation.internal.ConversationInternal;

/**
 * 金丝雀样本：<strong>故意</strong>违反"L3（检索）不得依赖 L4（问答）"这条边。
 *
 * <p>★ 这是本项目"把约定变成构建失败"的操作方式（{@code ROADMAP §4.7.2} 纪律 4）：
 * 先写一个<em>坏</em>的样本，再断言闸门在它身上报错。若闸门写错（规则写反、包名打错、 断言方向颠倒），这个测试会绿 —— 那时它就是一个告警，而不是一条自动发现。
 *
 * <p>⚠️ 不要"顺手修好"这个类。它红了才说明闸门真的在工作。
 */
public class IllegalDependent {

  /** 一个真实的引用，确保越界边出现在字节码里（未被调用的 import 会被优化掉）。 */
  public Class<?> peekUpstreamInternal() {
    return ConversationInternal.class;
  }
}
