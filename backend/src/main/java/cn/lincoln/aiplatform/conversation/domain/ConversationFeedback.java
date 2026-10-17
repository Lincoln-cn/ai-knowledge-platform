package cn.lincoln.aiplatform.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 会话评分（设计册 {@code 05 §6.2}：{@code POST /conversations/{id}/feedback}）。
 *
 * <h3>它与 {@code Message#feedback()} 的分工</h3>
 *
 * <p>两者粒度不同、消费方不同：
 *
 * <ul>
 *   <li>{@code cv_message.feedback} = <strong>逐条答案</strong>的赞踩（{@code UP}/{@code DOWN}），
 *       给用户回看与"这条答得好不好"；
 *   <li>本条 = <strong>整场会话</strong>的 1–5 分评分 + 可选评论，给管理端统计 （{@code 04:233} 的「反馈列表（只读）」）。
 * </ul>
 *
 * <p>★ 设计册 {@code 02:415} 把 {@code Feedback} 定成一个<strong>聚合</strong>
 * （消息、会话、用户、赞踩、评分、评论），所以它是独立一张表而不是 {@code cv_conversation} 上的两列 ——
 * 管理端要能把评分当成<strong>一条条反馈</strong>列出来， 一列 {@code rating smallint} 列不出来。
 *
 * <h3>口径</h3>
 *
 * <ul>
 *   <li>{@code score} 必须是 1–5。★ 应用层先给可读的 400；DB 的 {@code chk_conv_feedback_score}
 *       兜住绕过应用层的写入（两道都要有：一道给人看，一道给数据）；
 *   <li>一个用户对一场会话<strong>只有一条</strong>评分 ⇒ 再次提交是<strong>覆盖</strong>。 设计册里"提交评分"只有"提交"、没有"追加"；
 *   <li>★ <strong>不做"撤销评分"</strong> —— 设计册没写这个行为。多做一个没被要求的状态， 等于让统计口径多一种可能。
 * </ul>
 */
@Entity
@Table(name = "cv_conversation_feedback")
public class ConversationFeedback {

  /** 允许的分数下界。 */
  public static final int MIN_SCORE = 1;

  /** 允许的分数上界。 */
  public static final int MAX_SCORE = 5;

  /** 评论长度上限（与迁移里的 {@code varchar(500)} 同源）。 */
  public static final int MAX_COMMENT_LENGTH = 500;

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "conversation_id", nullable = false)
  private UUID conversationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "score", nullable = false)
  private short score;

  @Column(name = "comment")
  private String comment;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 需要。 */
  protected ConversationFeedback() {}

  /**
   * 新建一条评分。
   *
   * @param id 主键
   * @param conversationId 会话
   * @param userId 评分人（★ 从 JWT 取，不接受请求体传）
   * @param score 1–5
   * @param comment 可选评论
   * @param now 时间
   */
  public ConversationFeedback(
      UUID id, UUID conversationId, UUID userId, int score, String comment, Instant now) {
    this.id = id;
    this.conversationId = conversationId;
    this.userId = userId;
    this.createdAt = now;
    this.updatedAt = now;
    apply(score, comment, now);
  }

  /**
   * 覆盖评分（同一用户再次提交）。
   *
   * <p>★ 不新增行：{@code uq_conv_feedback_user} 保证"一人一场会话一条"， 而"提交评分"在设计册里只有提交、没有追加。
   *
   * @param score 1–5
   * @param comment 可选评论
   * @param now 时间
   */
  public void resubmit(int score, String comment, Instant now) {
    apply(score, comment, now);
    this.updatedAt = now;
  }

  private void apply(int score, String comment, Instant now) {
    if (score < MIN_SCORE || score > MAX_SCORE) {
      // ★ 归一到同一个出口：调用方拿到的是 400 + 可读文案（见 MessageFeedbackService 的做法）
      throw new IllegalArgumentException("评分需为 " + MIN_SCORE + "–" + MAX_SCORE);
    }
    if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
      throw new IllegalArgumentException("评论最多 " + MAX_COMMENT_LENGTH + " 字");
    }
    this.score = (short) score;
    this.comment = comment == null || comment.isBlank() ? null : comment.strip();
  }

  /**
   * @return 主键
   */
  public UUID id() {
    return id;
  }

  /**
   * @return 会话
   */
  public UUID conversationId() {
    return conversationId;
  }

  /**
   * @return 评分人
   */
  public UUID userId() {
    return userId;
  }

  /**
   * @return 1–5
   */
  public int score() {
    return score;
  }

  /**
   * @return 可选评论（可能为 {@code null}）
   */
  public String comment() {
    return comment;
  }

  /**
   * @return 首次提交时间
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * @return 最近一次提交时间
   */
  public Instant updatedAt() {
    return updatedAt;
  }
}
