package cn.lincoln.aiplatform.platform.feedback;

import java.util.UUID;

/**
 * 反馈沉淀为知识库问答对的跨模块端口。
 *
 * <p>反馈模块不允许直接依赖知识库模块（{@code ModuleBoundaryTest} 的边界规则）； 本端口放在 {@code
 * platform}，由知识库模块实现。反馈模块只负责“用户选了哪个知识库”， 不复制问答对、不判断知识库可见性与密级。
 */
public interface FeedbackPromotionPort {

  /**
   * 把一条已办理的反馈沉淀为问答对。
   *
   * <p>实现方必须以 {@code feedback:<feedbackMessageId>} 为幂等键：同一反馈重复沉淀时不得新增第二条。
   *
   * @param knowledgeBaseId 目标知识库
   * @param feedbackMessageId 被赞踩的消息 id（反馈主键）
   * @param conversationId 反馈所属会话，用于取密级 ceiling
   * @param question 用户问题
   * @param answer 被采纳的回答
   */
  void promote(
      UUID knowledgeBaseId,
      UUID feedbackMessageId,
      UUID conversationId,
      String question,
      String answer);
}
