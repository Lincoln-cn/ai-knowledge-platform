package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.lincoln.aiplatform.conversation.application.MessageFeedbackService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageFeedback;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ S3「赞/取消赞刷新后仍在」的<strong>落库判据</strong>（{@code ROADMAP §2.1} 的 S3 一行）。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>"刷新后仍在"这四个字意味着赞踩<strong>必须落库</strong>，不能只活在前端状态里。 本批之前契约里那条端点（{@code POST
 * /messages/{id}/feedback}）是<strong>空账</strong>： {@code /messages} 在 {@code ContractDriftTest}
 * 的未实现清单里，{@code cv_message} 没有 {@code feedback} 列， 而 {@code ChatMessageView} 的类注释写着"恒为 {@code
 * null}"。
 *
 * <h3>★★ 判据的形状：每一步都**重新从库里读**，而不是看返回值</h3>
 *
 * <p>与 {@code MessagePersistenceTest} 同一条口径：<strong>不开测试级事务</strong> —— 每次仓储/用例调用都在自己的事务里提交 ⇒
 * "重新读回来"是真的从库里读（一级缓存不跨事务存活）。 ★ 若只断言返回值，一个"改了内存对象但没落库"的实现会<strong>完全通过</strong>。
 *
 * <h3>覆盖四条（各对应一种写错的形状）</h3>
 *
 * <ol>
 *   <li><b>赞 → 重新读仍是 {@code UP}</b>：最基本的"落库了"；
 *   <li><b>踩覆盖赞</b>：契约原话「赞踩<b>互斥</b>」—— 库里只有一列，两个值不许并存；
 *   <li><b>传 {@code null} 取消</b>：契约原话「<b>可撤销</b>：type 传 null 表示取消」⇒ 回到未评价；
 *   <li>★ <b>用户消息不可评价</b>：这条不变量在应用层（PG 的 {@code CHECK} 不能跨行引用 {@code role}）。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class MessageFeedbackPersistenceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 会话的归属人（评价的归属判定要用它）。 */
  private static final UUID OWNER = UUID.fromString("1c000000-0000-4000-8000-0000000000aa");

  /** 另一个人 —— 用来证明"看不见 ⇒ 404"，而不是"谁都能改别人的消息"。 */
  private static final UUID STRANGER = UUID.fromString("1c000000-0000-4000-8000-0000000000bb");

  @Autowired JdbcTemplate jdbc;

  @Autowired ConversationRepository conversations;

  @Autowired MessageRepository messages;

  @Autowired MessageFeedbackService feedback;

  // ────────────────────────── ① 迁移接上了 ──────────────────────────

  @Test
  void migrationIsAppliedAndPreviousOneUntouched() {
    assertThat(successOf("35"))
        .as("Flyway 里没有 version=35 的成功记录 ⇒ conversation/V35 没被执行（cv_message 没有 feedback 列）")
        .isEqualTo(Boolean.TRUE);
    assertThat(successOf("33"))
        .as("★ V33（cv_message 建表）的成功记录不见了 —— 本批一个字都不许改更早的迁移（checksum 对不上，后端起不来）")
        .isEqualTo(Boolean.TRUE);
  }

  // ────────────────────────── ② 赞/踩/取消都能落库 ──────────────────────────

  @Test
  void upSurvivesAReload() {
    UUID conversationId = seedConversation();
    UUID messageId = seedAssistantMessage(conversationId, "答案正文");

    feedback.rate(messageId, OWNER, "UP");

    // ★ 重新从库里读（不是看返回值）
    assertThat(messages.findById(messageId).orElseThrow().feedback())
        .as("★★ S3「赞/取消赞刷新后仍在」：重新查出来的那一行必须带着 UP —— 只看返回值证明不了落库")
        .isEqualTo(MessageFeedback.UP);
    // ★ 再用**纯 SQL** 读一次：证明那个值真的在库里，而不是被持久化上下文满足
    assertThat(feedbackColumnOf(messageId))
        .as("★ 纯 SQL 读 cx_message.feedback —— 端口读对了但库里没有，是「改了内存没落库」的典型形态")
        .isEqualTo("UP");
  }

  @Test
  void downReplacesUpInsteadOfCoexisting() {
    UUID conversationId = seedConversation();
    UUID messageId = seedAssistantMessage(conversationId, "答案正文");

    feedback.rate(messageId, OWNER, "UP");
    feedback.rate(messageId, OWNER, "DOWN");

    assertThat(feedbackColumnOf(messageId))
        .as("★ 赞踩互斥：库里只有一列 ⇒ 踩必须**覆盖**赞，不能两个值并存")
        .isEqualTo("DOWN");
  }

  @Test
  void nullClearsTheFeedback() {
    UUID conversationId = seedConversation();
    UUID messageId = seedAssistantMessage(conversationId, "答案正文");

    feedback.rate(messageId, OWNER, "UP");
    feedback.rate(messageId, OWNER, null);

    assertThat(feedbackColumnOf(messageId))
        .as("★ 可撤销：传 null 表示取消 ⇒ 库里必须回到 NULL（不是保留上次的值）")
        .isNull();
  }

  // ────────────────────────── ③ 两条守卫 ──────────────────────────

  @Test
  void userMessagesCannotBeRated() {
    UUID conversationId = seedConversation();
    UUID userMessageId = seedUserMessage(conversationId, "我提的问题");

    // ★ 领域层抛 IllegalArgumentException，服务层把它统一成 400 —— 那是**有意的**：
    //   「只有助手消息能评价」是**请求不合法**（400），不是「找不到」（404）。
    //   ★ 若这条变成 404，前端就分不出「我点错了按钮」与「这条消息没了」。
    var refused =
        catchThrowableOfType(
            cn.lincoln.aiplatform.platform.domain.BusinessException.class,
            () -> feedback.rate(userMessageId, OWNER, "UP"));

    assertThat(refused)
        .as("★ 只有助手的回答可以被赞/踩 —— PG 的 CHECK 不能跨行引用 role ⇒ 这条只能在应用层挡")
        .isNotNull()
        .hasMessageContaining("只有助手的回答");
  }

  @Test
  void someoneElsesMessageIsNotFound() {
    UUID conversationId = seedConversation();
    UUID messageId = seedAssistantMessage(conversationId, "答案正文");

    var refused =
        catchThrowableOfType(
            cn.lincoln.aiplatform.platform.domain.BusinessException.class,
            () -> feedback.rate(messageId, STRANGER, "UP"));

    assertThat(refused)
        .as("★ 别人的消息不许评价 —— 与 HistoryService 同一条：看不见一律 404（不是 403，403 等于确认 id 存在）")
        .isNotNull();
    assertThat(refused.getMessage()).doesNotContain("403");
    assertThat(feedbackColumnOf(messageId)).as("★ 被拒的那一次**不许**在库里留下痕迹").isNull();
  }

  // ────────────────────────── 工具 ──────────────────────────

  private Boolean successOf(String version) {
    return jdbc.queryForObject(
        "select success from flyway_schema_history where version = ?", Boolean.class, version);
  }

  /** 纯 SQL 读那一列 —— 绕开 Hibernate，证明库里真的有这个值。 */
  private String feedbackColumnOf(UUID messageId) {
    return jdbc.queryForObject(
        "select feedback from cv_message where id = ?", String.class, messageId);
  }

  private UUID seedConversation() {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, OWNER, null, "赞踩夹具会话"));
    return id;
  }

  /** 造一条**已生成完**的助手消息（评价的前提：不是 GENERATING、不是 USER）。 */
  private UUID seedAssistantMessage(UUID conversationId, String content) {
    Message message = new Message(UUID.randomUUID(), conversationId, content);
    message.complete(null, 0L);
    assertThat(message.status())
        .as("★ 夹具前提：这条消息必须已经落到 GENERATED")
        .isEqualTo(MessageStatus.GENERATED);
    messages.save(message);
    return message.id();
  }

  private UUID seedUserMessage(UUID conversationId, String content) {
    Message message = Message.user(UUID.randomUUID(), conversationId, content);
    messages.save(message);
    return message.id();
  }
}
