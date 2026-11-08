package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.application.ChatHistoryService;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.application.MessageFeedbackService;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageKind;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort.AppBinding;
import cn.lincoln.aiplatform.platform.assistant.AppOpeningPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.web.PageResult;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ {@code #286}：应用开场白必须是会话里<strong>真正的第一条助手消息</strong>。
 *
 * <h3>它守的三条判据（逐条来自 Issue 原文）</h3>
 *
 * <ol>
 *   <li><b>开场白作为助手第一条消息</b> —— 落库、刷新仍在、{@code GET /conversations/{id}/messages} 返回它（本类用 {@link
 *       ChatHistoryService} 读的就是那个端点背后的同一条路径）；
 *   <li><b>{@code turnCount} 不含它</b> —— 开场白<strong>不</strong>调 {@code recordMessage}， 所以 {@code
 *       cv_conversation.message_count} 仍是 0；它也<strong>不</strong>推进 {@code last_message_at}；
 *   <li><b>反馈 / 引用 / 重新生成都不碰它</b> —— {@code refs} 为 {@code null}（从没经过检索）、 {@code rate} 与 {@code
 *       prepareRegenerate} 两条入口都拒（前端的按钮隐藏之外的第二道网）。
 * </ol>
 *
 * <h3>★ 为什么用真库（Testcontainers）而不是纯单测</h3>
 *
 * <p>"开场白有没有落进 {@code cv_message}"是 SQL 读数：{@code kind} 列是 {@code V87} 新加的， 而"不计数"这件事的权威在 {@code
 * cv_conversation.message_count} —— 一个假仓储只能证明调用顺序， 不能证明 {@code insert}
 * 真的成功（枚举映射写错会在起容器时炸，那也正是本类要的读数）。
 */
@SpringBootTest
@Testcontainers
class PrologueMessageTest {

  private static final String PROLOGUE = "您好，我是制度助手，可以帮您查考勤、报销与合同模板。";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired ConversationListService conversations;

  @Autowired ChatHistoryService history;

  @Autowired MessageFeedbackService feedback;

  @Autowired ChatTurnWriter turnWriter;

  @Autowired ConversationRepository repository;

  @Autowired MessageRepository messages;

  @MockitoBean AppBindingPort appBindings;

  @MockitoBean AppOpeningPort openings;

  /** 每个用例自己的用户与应用 —— 共享容器的类之间不互相污染。 */
  private final UUID user = UUID.randomUUID();

  private final UUID app = UUID.randomUUID();

  /**
   * 本类只验"开场白落不落、怎么落"，不验应用可见性。
   *
   * <p>★ {@code app} 是随机 UUID，库里没有对应 {@code as_assistant} 行；真实适配器会返回空 ⇒ 被断言成"应用不存在"。这里替换两个端口，让
   * {@code app} 可见且有开场白。
   */
  @BeforeEach
  void allowAppAndGiveItAPrologue() {
    when(appBindings.findVisibleById(any()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    new AppBinding(
                        invocation.getArgument(0),
                        null,
                        List.of(),
                        false,
                        false,
                        "",
                        List.of(),
                        RetrievalParams.platformDefaults())));
    // ★ 默认没有开场白：个别用例再覆盖成有值 —— 这样"空开场白不造消息"本身也被同一条路径覆盖
    when(openings.prologueOf(any())).thenReturn(Optional.empty());
  }

  // ────────────────────────── ① 落库 + 读数 ──────────────────────────

  @Test
  void createWritesThePrologueAsTheFirstAssistantMessage() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));

    UUID conversationId = conversations.create(user, app).id();

    List<Message> rows = messages.listByConversation(conversationId);
    assertThat(rows).as("★★ 新建会话必须带一条开场白消息 —— 这是 #286 的核心判据（不是空态文案，是对话流里的一条）").hasSize(1);
    Message prologue = rows.get(0);
    assertThat(prologue.kind()).as("★ 必须能区分开场白与模型回答").isEqualTo(MessageKind.PROLOGUE);
    assertThat(prologue.role().name()).as("★ 它是助手角色（显示在回答那一侧）").isEqualTo("ASSISTANT");
    assertThat(prologue.content()).as("★ 正文就是应用配置的那句").isEqualTo(PROLOGUE);
    assertThat(prologue.status())
        .as("★ 它不是模型生成的 ⇒ 直接是终态，不许是 GENERATING（否则界面上永远转圈）")
        .isEqualTo(MessageStatus.GENERATED);
    assertThat(prologue.references()).as("★ 开场白没有经过检索 ⇒ 引用为 null").isNull();
  }

  @Test
  void thePrologueIsReturnedByTheHistoryReadPathWithItsKind() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));
    UUID conversationId = conversations.create(user, app).id();

    PageResult<cn.lincoln.aiplatform.conversation.application.ChatMessageView> page =
        history.list(conversationId, user, -1, 20, null);

    assertThat(page.list())
        .as("★ 刷新页面 / 换设备后仍能看到它 —— 这就是 GET /conversations/{id}/messages 的那条读路径")
        .hasSize(1);
    assertThat(page.list().get(0).kind()).as("★ 读路径必须把 kind 带出来").isEqualTo(MessageKind.PROLOGUE);
    assertThat(page.list().get(0).content()).isEqualTo(PROLOGUE);
    assertThat(page.list().get(0).refs()).as("★ 引用为空（判据 ① 的第三处断言）").isNull();
    assertThat(page.list().get(0).feedback()).as("★ 未评价").isNull();
  }

  // ────────────────────────── ② turnCount 不含它 ──────────────────────────

  @Test
  void thePrologueDoesNotCountAsATurn() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));
    UUID conversationId = conversations.create(user, app).id();

    assertThat(repository.findById(conversationId).orElseThrow().messageCount())
        .as("★★ turnCount 必须仍是 0 —— 开场白不是一轮问答；若这里变成 1，会话列表会显示「1 轮」而没有问答发生")
        .isZero();
    assertThat(messages.countByConversation(conversationId)).as("★ 消息表里确实有那一行").isEqualTo(1);
  }

  // ────────────────────────── ③ 幂等：不重复落 ──────────────────────────

  @Test
  void creatingAgainReusesTheEmptyConversationWithoutDuplicatingThePrologue() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));

    UUID first = conversations.create(user, app).id();
    UUID second = conversations.create(user, app).id();

    assertThat(second).as("★ 带开场白的会话仍是「空会话」（开场白不计数）⇒ 应当复用，而不是再建一个").isEqualTo(first);
    assertThat(messages.listByConversation(first))
        .as("★★ 重复点「新建会话」不能多出一条开场白 —— 界面上只显示一条，库里堆两条是看不见的重复")
        .hasSize(1);
  }

  // ────────────────────────── ④ 空开场白不造消息 ──────────────────────────

  @Test
  void anEmptyPrologueCreatesNoMessage() {
    when(openings.prologueOf(app)).thenReturn(Optional.of("   "));

    UUID conversationId = conversations.create(user, app).id();

    assertThat(messages.listByConversation(conversationId))
        .as("★★ 开场白为空（或全空白）⇒ 一条消息都不造 —— 否则每个空开场白的应用都多一个空气泡")
        .isEmpty();
  }

  @Test
  void aGenericConversationHasNoPrologue() {
    UUID conversationId = conversations.create(user, null).id();

    assertThat(messages.listByConversation(conversationId)).as("★ 通用问答没有应用 ⇒ 没有开场白").isEmpty();
  }

  // ────────────────────────── ⑤ 两个"不许碰它"的入口 ──────────────────────────

  @Test
  void thePrologueCannotBeRated() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));
    UUID conversationId = conversations.create(user, app).id();
    UUID prologueId = messages.listByConversation(conversationId).get(0).id();

    assertThatThrownBy(() -> feedback.rate(prologueId, user, "UP"))
        .as("★★ 判据 ①「反馈按钮不出现在它上面」的领域侧第二道网 —— 任何入口拿开场白 id 评价都必须 400")
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("开场白")
        .extracting(e -> ((BusinessException) e).code())
        .isEqualTo(ErrorCode.BAD_REQUEST);
  }

  /**
   * ★★ 这条用例的断言<strong>必须能区分两种 400</strong>：
   *
   * <p>开场白前面没有用户提问，所以即使去掉"开场白不许重新生成"那道守卫， {@code preceding(...)} 也会抛一个 400（「这条回答前面没有用户提问」）—— 只断言
   * {@code code == BAD_REQUEST} 的话，**删掉守卫它照样绿**。所以这里钉的是<strong>文案</strong>。
   */
  @Test
  void thePrologueCannotBeRegenerated() {
    when(openings.prologueOf(app)).thenReturn(Optional.of(PROLOGUE));
    UUID conversationId = conversations.create(user, app).id();
    UUID prologueId = messages.listByConversation(conversationId).get(0).id();

    assertThatThrownBy(() -> turnWriter.prepareRegenerate(prologueId, user))
        .as("★★ 判据 ①「开场白不冒充模型回答」—— 重新生成会把它覆盖成模型输出，必须被拒")
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("开场白")
        .extracting(e -> ((BusinessException) e).code())
        .isEqualTo(ErrorCode.BAD_REQUEST);
  }
}
