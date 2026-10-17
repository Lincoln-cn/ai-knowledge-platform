package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationFeedback;
import cn.lincoln.aiplatform.conversation.domain.ConversationFeedbackRepository;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话评分的<strong>写入用例</strong>（契约 {@code submitConversationFeedback}： {@code POST
 * /conversations/{conversationId}/feedback}，body {@code { score, comment? }}）。
 *
 * <h3>它补的是 S3 的最后一条</h3>
 *
 * <p>{@code ROADMAP §2.1} 的 S3 一行里写着「会话评分」；设计册把形状定死在 {@code 05 §6.2}：{@code POST
 * /conversations/{id}/feedback}，聚合口径见 {@code 02:415} （{@code Feedback}：消息、会话、用户、赞踩、评分、评论；行为「提交评分」）。
 *
 * <p>★ 本类<strong>不是</strong>猜出来的形状 —— 我一开始以为要"问用户要一个契约"， 但设计册早就写了。★ 这条教训与 {@code REGENERATE}
 * 那次完全一样： <strong>先读契约与设计册，再问人</strong>。
 *
 * <h3>★★ 归属判定：看不见 ⇒ 404（不是 403）</h3>
 *
 * <p>与 {@link MessageFeedbackService} / {@link ChatHistoryService} <strong>逐字同一条</strong> （{@code
 * 07 §3.3} 纪律 2）："不存在"与"不属于我"走同一个出口、同一段文案 —— 403 等于告诉对方"这个 id 是存在的"。
 *
 * <h3>★ 再提交是覆盖，不是追加</h3>
 *
 * <p>设计册里"提交评分"只有提交、没有追加；{@code uq_conv_feedback_user} 保证一人一场会话一条。 ⇒
 * 同一用户第二次提交<strong>改那一条</strong>（{@code created_at} 不动、{@code updated_at} 变）。
 */
@Service
public class ConversationFeedbackService {

  private final ConversationRepository conversations;
  private final ConversationFeedbackRepository feedbacks;

  public ConversationFeedbackService(
      ConversationRepository conversations, ConversationFeedbackRepository feedbacks) {
    this.conversations = conversations;
    this.feedbacks = feedbacks;
  }

  /**
   * 提交（或覆盖）一场会话的评分。
   *
   * @param conversationId 会话
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @param score 1–5
   * @param comment 可选评论
   * @return 落库后的那条评分
   * @throws BusinessException 会话不存在 / 不属于当前用户（404）· 分数或评论越界（400）
   */
  @Transactional
  public ConversationFeedback submit(UUID conversationId, UUID userId, int score, String comment) {
    // ★★ 越界校验必须在**构造实体之前**、且用 BusinessException 直接抛。
    //   实测踩过：第一版只把校验留在实体的 `apply()` 里、并试图在 `save()` 外面兜
    //   `IllegalArgumentException` —— 而 `apply()` 是在**构造/覆盖两个分支里**调用的，
    //   那两个分支都在 `try` 之外 ⇒ 越界会以 `IllegalArgumentException` 逃出去 ⇒ **500**。
    //   判据 `outOfRangeScoreIs400WithTheLegalRange` 当场抓到（实测 "Expecting actual throwable to be
    //   an instance of BusinessException but was: IllegalArgumentException"）。
    BusinessException invalid = null;
    if (score < ConversationFeedback.MIN_SCORE || score > ConversationFeedback.MAX_SCORE) {
      invalid =
          new BusinessException(
              ErrorCode.BAD_REQUEST,
              "评分需为 " + ConversationFeedback.MIN_SCORE + "–" + ConversationFeedback.MAX_SCORE,
              "请给 1–5 分");
    } else if (comment != null && comment.length() > ConversationFeedback.MAX_COMMENT_LENGTH) {
      invalid =
          new BusinessException(
              ErrorCode.BAD_REQUEST,
              "评论最多 " + ConversationFeedback.MAX_COMMENT_LENGTH + " 字",
              "可以精简一下，或先不写评论");
    }
    if (invalid != null) {
      throw invalid;
    }

    Conversation conversation =
        conversations
            .findById(conversationId)
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在", "它可能已被删除，或者不在你的可见范围内"));

    // ★ 截到**微秒**再落库：PG 的 `timestamptz` 只到微秒，不截会让"内存里的值"与"读回来的值"差几位纳秒
    //   ⇒ 判据 `overwriteKeepsCreatedAtAndMovesUpdatedAt` 会因为没有落库缺陷而红（实测
    //   `expected: …766633100Z but was: …766633Z`）。★ 截在写入侧，让两边天然一致。
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    ConversationFeedback feedback =
        feedbacks
            .findByConversationAndUser(conversationId, userId)
            .map(
                existing -> {
                  // ★ 覆盖：一人一场会话一条（设计册只有"提交评分"，没有"追加"）
                  existing.resubmit(score, comment, now);
                  return existing;
                })
            .orElseGet(
                () ->
                    new ConversationFeedback(
                        UUID.randomUUID(), conversationId, userId, score, comment, now));

    return feedbacks.save(feedback);
  }
}
