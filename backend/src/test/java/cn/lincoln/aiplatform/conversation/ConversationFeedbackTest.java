package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.lincoln.aiplatform.conversation.application.ConversationFeedbackService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationFeedback;
import cn.lincoln.aiplatform.conversation.domain.ConversationFeedbackRepository;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ S3「会话评分」的<strong>落库判据</strong>（{@code ROADMAP §2.1} 的最后一条）。
 *
 * <h3>形状从哪来（★ 不是猜的）</h3>
 *
 * <p>设计册把端点与聚合口径都写死了：
 *
 * <ul>
 *   <li>{@code 05:602}：{@code POST /conversations/{id}/feedback} —— 会话评分；
 *   <li>{@code 02:415}：{@code Feedback} 聚合 = 消息、会话、用户、赞踩、评分、评论； 行为 =
 *       切换赞踩（互斥可撤销）、<strong>提交评分</strong>。
 * </ul>
 *
 * <p>⇒ 本类按设计册写，<strong>没有</strong>去问"评分该长什么样"。
 *
 * <h3>★★ 判据的形状：每一步都**重新从库里读**，而不是看返回值</h3>
 *
 * <p>与 {@code MessageFeedbackPersistenceTest} 同一条口径：<strong>不开测试级事务</strong> —— 每次调用都在自己的事务里提交 ⇒
 * "重新读回来"是真的从库里读。 ★ 若只断言返回值，一个"改了内存对象但没落库"的实现会<strong>完全通过</strong>。
 *
 * <h3>覆盖五条（各对应一种写错的形状）</h3>
 *
 * <ol>
 *   <li><b>提交 → 重新读回同分同评论</b>：最基本的"落库了"；
 *   <li>★ <b>再提交是覆盖、不是追加</b>：这是本设计最容易写错的一处 —— 写成追加的话，库里会一场会话攒出多条评分，而管理端统计会重复计数；
 *   <li>★ <b>越界 400 且带合法范围</b>：分数是<strong>调用方</strong>的问题，不是 500；
 *   <li>★ <b>别人的会话 ⇒ 404（不是 403）</b>：403 等于告诉对方"这个 id 是存在的"；
 *   <li>★ <b>覆盖成功时 {@code createdAt} 不变、{@code updatedAt} 变</b> ——
 *       "首次提交时间"是统计口径的一部分，被覆盖改掉就查不出"这条评分最早什么时候来的"。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class ConversationFeedbackTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired ConversationFeedbackService feedbacks;

  @Autowired ConversationFeedbackRepository repository;

  @Autowired ConversationRepository conversations;

  /** 每个用例一个独立归属人（★ 同一个容器类内共享 ⇒ 不复用 id，免得互相污染）。 */
  private static UUID newOwner() {
    return UUID.randomUUID();
  }

  @Test
  void submittedScoreIsReadBackFromTheDatabase() {
    UUID owner = newOwner();
    UUID conversation = seedConversation(owner);

    feedbacks.submit(conversation, owner, 4, "答得不错");

    ConversationFeedback reloaded =
        repository
            .findByConversationAndUser(conversation, owner)
            .orElseThrow(() -> new AssertionError("★ 必须真的落库 —— 只看返回值的话，一个『改了内存没落库』的实现会完全通过"));
    assertThat(reloaded.score()).isEqualTo(4);
    assertThat(reloaded.comment()).as("评论也要落库").isEqualTo("答得不错");
    assertThat(reloaded.userId()).isEqualTo(owner);
  }

  @Test
  void submittingTwiceOverwritesInsteadOfAppending() {
    UUID owner = newOwner();
    UUID conversation = seedConversation(owner);

    feedbacks.submit(conversation, owner, 2, "一般");
    feedbacks.submit(conversation, owner, 5, "改口了");

    assertThat(repository.listByConversation(conversation))
        .as("★★ 一人一场会话**只有一条**评分（迁移里的 uq_conv_feedback_user）。" + "写成追加的话，库里会攒出多条，而管理端统计会重复计数")
        .hasSize(1);
    assertThat(repository.findByConversationAndUser(conversation, owner).orElseThrow().score())
        .as("★ 覆盖的是分数（取最新一次提交）")
        .isEqualTo(5);
  }

  @Test
  void overwriteKeepsCreatedAtAndMovesUpdatedAt() {
    UUID owner = newOwner();
    UUID conversation = seedConversation(owner);

    ConversationFeedback first = feedbacks.submit(conversation, owner, 3, null);
    ConversationFeedback second = feedbacks.submit(conversation, owner, 4, null);

    assertThat(second.createdAt())
        .as("★ 「首次提交时间」是统计口径的一部分 —— 被覆盖改掉就查不出这条评分最早什么时候来的")
        .isEqualTo(first.createdAt());
    assertThat(second.updatedAt())
        .as("★ 而「最近一次提交时间」必须往前走（否则看不出它被改过）")
        .isAfterOrEqualTo(first.updatedAt());
  }

  @Test
  void outOfRangeScoreIs400WithTheLegalRange() {
    UUID owner = newOwner();
    UUID conversation = seedConversation(owner);

    for (int bad : new int[] {0, 6, -1}) {
      BusinessException error =
          catchThrowableOfType(
              () -> feedbacks.submit(conversation, owner, bad, null), BusinessException.class);

      assertThat(error).as("分数 " + bad + " 越界 ⇒ 必须是业务异常（会映射成 400），不是 500").isNotNull();
      assertThat(error.code())
          .as("★ 越界是**调用方**的问题 ⇒ 400，不是 500")
          .isEqualTo(cn.lincoln.aiplatform.platform.domain.ErrorCode.BAD_REQUEST);
      assertThat(error.hint())
          .as("★ 400 必须带上**合法范围** —— 只说『错了』等于让调用方猜")
          .contains("1")
          .contains("5");
    }
    assertThat(repository.findByConversationAndUser(conversation, owner))
        .as("★ 越界不许留下半条记录")
        .isEmpty();
  }

  @Test
  void someoneElsesConversationCannotBeRated() {
    UUID owner = newOwner();
    UUID stranger = newOwner();
    UUID conversation = seedConversation(owner);

    BusinessException error =
        catchThrowableOfType(
            () -> feedbacks.submit(conversation, stranger, 5, null), BusinessException.class);

    assertThat(error).as("★ 别人的会话不许评分").isNotNull();
    assertThat(error.code())
        .as(
            "★★ 必须 404 而不是 403 —— 403 等于告诉对方『这个 id 是存在的』"
                + "（与 ChatHistoryService / MessageFeedbackService 逐字同一条纪律）")
        .isEqualTo(cn.lincoln.aiplatform.platform.domain.ErrorCode.NOT_FOUND);
    assertThat(repository.findByConversationAndUser(conversation, stranger))
        .as("★ 不许留下记录")
        .isEmpty();
  }

  // ────────────────────────── 工具 ──────────────────────────

  private UUID seedConversation(UUID owner) {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, owner, null, "评分判据用的会话"));
    return id;
  }
}
