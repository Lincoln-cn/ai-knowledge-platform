package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.lincoln.aiplatform.conversation.application.ChatMode;
import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageFeedback;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
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
 * ★★ S3「重新生成」的判据 —— <strong>契约「覆盖原答案、不新增消息对」</strong>的落库读数。
 *
 * <h3>它补的是什么</h3>
 *
 * <p>契约里 {@code mode: 'REGENERATE'` + `targetMessageId} 一直在（{@code api/stream-types.ts:172}：
 * 「{@code REGENERATE} <strong>覆盖</strong> {@code targetMessageId}
 * 对应的原答案（<strong>不新增消息对</strong>）」）， 而 {@code ChatRequest} 的 javadoc 逐字写着"刻意不含 {@code mode}/{@code
 * targetMessageId}"。 本轮补上。
 *
 * <h3>★★ 四条判据，每条都对应一种"看起来对但错"的形状</h3>
 *
 * <ol>
 *   <li><b>不新增消息对</b> —— 消息总数不变，且 {@code assistantMessageId} 就是被覆盖的那条。 ★
 *       写成"新增一条"最省事，而界面上只是多了一问一答，<b>没人会发现</b>；
 *   <li>★★ <b>问题取自"target 之前最近一条 USER 消息"</b> —— 造<strong>两轮对话</strong> （问 A、答 A′、问 B、答 B′），对
 *       {@code A′} 重生成 ⇒ 问题必须是 <b>A</b>，不是 B。 ★ 取错（取会话第一条 / 取最后一条）的症状都只是"重新生成答得不对"，界面上看不出；
 *   <li>★★ <b>旧赞踩必须清空</b> —— {@code feedback} 指向的是<strong>被覆盖掉的那条回答</strong>。
 *       不清的症状是「新答案带着旧评价」：用户赞过、重生成、界面仍显示"已赞"， 而那条新答案他根本没看过。★ 这是本批（{@code
 *       V35}）<strong>新引入</strong>的列， 也最容易漏；
 *   <li><b>正文/引用/失败原因/用量一起清</b> —— 留着任何一样都会让"生成中"那一格 显示上一轮的东西。
 * </ol>
 *
 * <h3>★ 事务口径</h3>
 *
 * <p>与 {@code MessagePersistenceTest} 同一条：<strong>不开测试级事务</strong>， 每次调用在自己的事务里提交 ⇒
 * 下面的"重新读回来"是**真的从库里读**。
 */
@SpringBootTest
@Testcontainers
class RegenerateTurnTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID OWNER = UUID.fromString("1f000000-0000-4000-8000-0000000000aa");
  private static final UUID STRANGER = UUID.fromString("1f000000-0000-4000-8000-0000000000bb");

  @Autowired JdbcTemplate jdbc;

  @Autowired ConversationRepository conversations;

  @Autowired MessageRepository messages;

  @Autowired ChatService chat;

  @Autowired cn.lincoln.aiplatform.assistant.domain.AssistantRepository assistants;

  @Test
  void regenerateOverwritesInsteadOfAddingAMessagePair() {
    UUID conversationId = seedConversation();
    UUID firstAnswer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");
    int before = messageCount(conversationId);

    ChatService.StartedTurn started =
        chat.startTurn(
            new ChatService.ChatRequest(null, conversationId, "", OWNER),
            ChatMode.REGENERATE,
            firstAnswer);

    assertThat(started.turn().assistantMessageId())
        .as("★★ 契约「不新增消息对」：返回的助手消息 id 必须就是**被覆盖的那条**")
        .isEqualTo(firstAnswer);
    assertThat(messageCount(conversationId))
        .as("★★ 消息总数必须不变 —— 写成『新增一条』最省事，而界面上只是多了一问一答，没人会发现")
        .isEqualTo(before);
    assertThat(started.question()).as("★ 重新生成的问题来自库里（那一条回答之前最近的用户消息）").isEqualTo("报销标准是多少");
  }

  @Test
  void regenerateUsesTheNearestPrecedingUserMessageNotTheLastOne() {
    UUID conversationId = seedConversation();
    // ── 第一轮：问 A → 答 A′ ──
    UUID firstAnswer = seedTurn(conversationId, "第一个问题：报销标准是多少", "A 的答案");
    // ── 第二轮：问 B → 答 B′ ──
    seedTurn(conversationId, "第二个问题：怎么申请出差", "B 的答案");

    // ★★ 对**第一轮的回答**重新生成 ⇒ 问题必须是 A（第一个问题），不是 B
    ChatService.StartedTurn started =
        chat.startTurn(
            new ChatService.ChatRequest(null, conversationId, "", OWNER),
            ChatMode.REGENERATE,
            firstAnswer);

    assertThat(started.question())
        .as(
            "★★ 必须取『target **之前**最近的一条 USER 消息』。"
                + "取『会话第一条』或『最后一条』都会在这里红 —— 而它们在生产上的症状"
                + "只是『重新生成答得不对』，界面上完全看不出")
        .isEqualTo("第一个问题：报销标准是多少")
        .doesNotContain("怎么申请出差");
  }

  @Test
  void regenerateClearsTheOldFeedback() {
    UUID conversationId = seedConversation();
    UUID answer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");
    // 先赞一下，再重新生成
    rate(answer, MessageFeedback.UP);
    assertThat(feedbackColumnOf(answer)).as("★ 前置：赞必须真的落库了").isEqualTo("UP");

    chat.startTurn(
        new ChatService.ChatRequest(null, conversationId, "", OWNER), ChatMode.REGENERATE, answer);

    assertThat(feedbackColumnOf(answer))
        .as(
            "★★ 旧赞踩必须清空 —— 它指向的是**被覆盖掉的那条回答**。"
                + "不清的症状是『新答案带着旧评价』：用户赞过、重生成、界面仍显示已赞，"
                + "而那条新答案他根本没看过。★ 这是 V35 新引入的列，最容易漏")
        .isNull();
  }

  @Test
  void regenerateClearsEverythingElseFromThePreviousRun() {
    UUID conversationId = seedConversation();
    UUID answer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");

    chat.startTurn(
        new ChatService.ChatRequest(null, conversationId, "", OWNER), ChatMode.REGENERATE, answer);

    Message reloaded = messages.findById(answer).orElseThrow();
    assertThat(reloaded.status())
        .as("★ 重生成之后那一行必须回到生成中（否则前端会以为不用等）")
        .isEqualTo(MessageStatus.GENERATING);
    assertThat(reloaded.content()).as("★ 旧正文必须清掉（留着会显示上一轮的答案）").isEmpty();
    assertThat(reloaded.references()).as("★ 引用快照属于旧答案，必须清（新答案要重新检索）").isNull();
    assertThat(reloaded.tokens()).as("★ 用量清零：保留累加会让『这条答案花了多少』同时算上两次生成（成本读数就错了）").isNull();
  }

  /**
   * ★★ 会话**绑了应用**时的重新生成 —— 补的是原有 7 条用例漏掉的那一支。
   *
   * <h3>为什么这一支必须被本地回归守住</h3>
   *
   * <p>那 7 条全部传 `appId = null` ⇒ `conversation.assistantId()` 为 null ⇒ `prepareRegenerate` 走 `app
   * == null` 那一支 ⇒ **永远不读 `Assistant.kbIds`** （一个懒加载集合）。
   *
   * <p>★ 而真栈上正是这一支炸过：重新生成直接 **500**， `LazyInitializationException: Cannot lazily initialize
   * collection of role '…Assistant.kbIds'` —— 根因是 `prepareRegenerate` 漏了 `@Transactional`（同类的
   * `prepare` 有它）。
   *
   * <p>⇒ 这条用例的价值不是"多一条断言"，而是**把"有关联数据"那一支纳入本地回归**： 替身造的场景如果比真实场景简单，漏掉的恰好是这种"关联"。
   */
  @Test
  void regenerateWorksWhenTheConversationIsBoundToAnApp() {
    seedKnowledgeBase();
    UUID appId = seedAppWithKb();
    UUID conversationId = seedConversation(appId);
    UUID answer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");

    // ★★ 身份必须显式给：equireVisibleApp 走 AccessScopePort（JWT 驱动），
    //   非 HTTP 测试里它是 nobody() ⇒ 会报「应用不存在」，而那不是应用的问题。
    ChatService.StartedTurn started =
        cn.lincoln.aiplatform.support.SecurityContexts.asUser(
            OWNER,
            ClassificationLevel.CONFIDENTIAL,
            () ->
                chat.startTurn(
                    new ChatService.ChatRequest(appId, conversationId, "", OWNER),
                    ChatMode.REGENERATE,
                    answer));

    assertThat(started.turn().assistantMessageId())
        .as("★ 绑了应用时重生成也必须覆盖同一条（这一支会读 Assistant.kbIds —— 懒加载集合，事务一关就炸）")
        .isEqualTo(answer);
    assertThat(started.turn().kbIds())
        .as("★ 重新生成沿用会话绑的应用的库（不跟请求体漂移）⇒ 必须拿到那个库")
        .containsExactly(KB_ID);
  }

  // ────────────────────────── 两条拒绝路径 ──────────────────────────

  @Test
  void someoneElsesAnswerCannotBeRegenerated() {
    UUID conversationId = seedConversation();
    UUID answer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");

    var refused =
        catchThrowableOfType(
            BusinessException.class,
            () ->
                chat.startTurn(
                    new ChatService.ChatRequest(null, conversationId, "", STRANGER),
                    ChatMode.REGENERATE,
                    answer));

    assertThat(refused).as("★ 别人的回答不许重生成 —— 与 HistoryService 同一条：看不见一律 404").isNotNull();
  }

  @Test
  void continueModeIsRejectedInsteadOfSilentlyBehavingLikeNew() {
    UUID conversationId = seedConversation();
    UUID answer = seedTurn(conversationId, "报销标准是多少", "第一次的答案");
    int before = messageCount(conversationId);

    var refused =
        catchThrowableOfType(
            BusinessException.class,
            () ->
                chat.startTurn(
                    new ChatService.ChatRequest(null, conversationId, "继续", OWNER),
                    ChatMode.CONTINUE,
                    answer));

    assertThat(refused)
        .as(
            "★★ CONTINUE 必须**显式拒绝**，不许静默当 NEW —— "
                + "静默降级会新增一条消息，与契约『接着已有内容续写』直接矛盾，"
                + "而界面上它看起来完全正常（这正是 silent-failure 的形状）")
        .isNotNull();
    assertThat(refused.getMessage()).contains("尚未实现");
    assertThat(messageCount(conversationId)).as("★ 而且**一条消息都不许新增**（拒绝必须是纯粹的拒绝）").isEqualTo(before);
  }

  // ────────────────────────── 工具 ──────────────────────────

  private Boolean successOf(String version) {
    return jdbc.queryForObject(
        "select success from flyway_schema_history where version = ?", Boolean.class, version);
  }

  private int messageCount(UUID conversationId) {
    return jdbc.queryForObject(
        "select count(*) from cv_message where conversation_id = ?", Integer.class, conversationId);
  }

  private String feedbackColumnOf(UUID messageId) {
    return jdbc.queryForObject(
        "select feedback from cv_message where id = ?", String.class, messageId);
  }

  /** 本类专属的库 id（断言"沿用会话的应用的库"要用它）。 */
  private static final UUID KB_ID = UUID.fromString("1f000000-0000-4000-8000-0000000000dd");

  /** 本类专属的模型 id（库里 `embedding_model_id` 是必填外键）。 */
  private static final UUID EMBEDDING_MODEL =
      UUID.fromString("1f000000-0000-4000-8000-0000000000ee");

  /**
   * 造一条真的 `kb_knowledge_base` 行。
   *
   * <p>★ 必须真造：`as_assistant_kb.knowledge_base_id` 上有外键指向它 （实测：只给一个随机 UUID 会 `violates foreign key
   * constraint`）。 ★ 列清单抄自 `PublicChatHttpTest` 的同一段 —— 那是本仓"造库"的既有口径。
   */
  private void seedKnowledgeBase() {
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, 'INTERNAL', ?, 'PRIVATE', now(), now())",
        KB_ID,
        "重生成夹具库",
        EMBEDDING_MODEL,
        OWNER);
  }

  /** 造一个已发布、绑了一个库的应用（★ `kbIds` 是懒加载集合 —— 正是原本漏掉的那一支）。 */
  private UUID seedAppWithKb() {
    UUID appId = UUID.randomUUID();
    var assistant =
        new cn.lincoln.aiplatform.assistant.domain.Assistant(
            appId,
            "重生成夹具应用",
            "🤖",
            "回答制度问题",
            cn.lincoln.aiplatform.assistant.domain.AssistantType.SIMPLE,
            EMBEDDING_MODEL,
            "你是制度助手",
            OWNER,
            OWNER);
    assistant.bind(List.of(KB_ID), List.of(), OWNER);
    assistants.save(assistant);
    return appId;
  }

  /** 造一条**绑了应用**的会话。 */
  private UUID seedConversation(UUID appId) {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, OWNER, appId, "重生成夹具（绑应用）"));
    return id;
  }

  private UUID seedConversation() {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, OWNER, null, "重生成夹具"));
    return id;
  }

  /**
   * 造一轮「问 → 答」并把它收尾（{@code GENERATED}）。
   *
   * @return 那条**助手**消息的 id（重生成的目标）
   */
  private UUID seedTurn(UUID conversationId, String question, String answer) {
    Message user = Message.user(UUID.randomUUID(), conversationId, question);
    messages.save(user);
    Message assistant = new Message(UUID.randomUUID(), conversationId, "");
    assistant.appendText(answer);
    assistant.complete(new TokenUsage(10, 20), 100L);
    messages.save(assistant);
    return assistant.id();
  }

  private void rate(UUID messageId, MessageFeedback value) {
    Message message = messages.findById(messageId).orElseThrow();
    message.rate(value);
    messages.save(message);
  }

  /** 迁移闸门（★ 防"以为跑了 V35 其实没跑"）。 */
  @Test
  void migrationsAreApplied() {
    assertThat(successOf("35")).as("V35（feedback 列）必须成功").isEqualTo(Boolean.TRUE);
    assertThat(successOf("33")).as("★ 更早的迁移不许被碰坏").isEqualTo(Boolean.TRUE);
  }

  /** 只为让"未使用的 import"检查闭嘴：本类不直接用密级。 */
  @SuppressWarnings("unused")
  private static final ClassificationLevel UNUSED_CLASSIFICATION = ClassificationLevel.INTERNAL;

  /** 同理：本类不直接构造 PreparedTurn，但类型出现在签名上。 */
  @SuppressWarnings("unused")
  private static final Class<?> UNUSED_TURN = ChatTurnWriter.PreparedTurn.class;
}
