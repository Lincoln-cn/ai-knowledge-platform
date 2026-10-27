package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort.AppBinding;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.web.PageResult;
import cn.lincoln.aiplatform.support.PostgresImage;
import cn.lincoln.aiplatform.support.SecurityContexts;
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
 * ★★ 会话列表 / 新建会话（契约 {@code GET} 与 {@code POST /conversations}）的判据。
 *
 * <h3>它补的是一个让 A2 在<strong>界面层面</strong>不成立的洞</h3>
 *
 * <p>真栈闸门的诊断逐字读到（{@code check_reload_keeps_state_e2e.py}）：
 *
 * <pre>
 * [诊断] 会话页 url = …/#/conversation?appId=…
 * [诊断] body 前 200 字 = '…历史会话\n接口不存在：api/v1/conversations\n重试\n新建会话\n开始提问吧…'
 * </pre>
 *
 * <p>⇒ 会话页加载时调 {@code GET /conversations} 拿**历史会话**，而那个端点**不存在** ⇒ 侧栏永远空 ⇒
 * <strong>刷新之后没有会话可恢复</strong> ⇒ {@code ROADMAP §2.2} 的 A2「刷新页面不重置」在**界面层面物理上做不到**。 ★
 * 服务层那一半一直是对的（{@code ConversationStateSurvivesReloadTest} 已证明）。 ★ 而「新建会话」按钮调的是 {@code POST
 * /conversations} —— 同样不存在。
 *
 * <h3>四条判据</h3>
 *
 * <ol>
 *   <li><b>列表只给"我的"</b> —— 会话是私人对话（{@code 07 §3.1}）。★ 这一条最重要：
 *       放宽成"按数据范围给全部可见"会让**别人的对话出现在我的侧栏**，比"看不到"严重得多；
 *   <li><b>按最近活动倒序</b> —— 侧栏默认就该"我刚聊过的在最上面"，否则用户要在列表里翻；
 *   <li><b>新建会话真的落库且归属正确</b> —— 那条"新建会话"按钮的落点；
 *   <li>★ <b>{@code page=-1} 取最后一页</b> —— 与历史消息同一条约定（不另立一套）。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class ConversationListTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /**
   * ★★ 每个用例一个**独立归属人**。
   *
   * <p>为什么：同一个 Testcontainers 容器在**类内所有用例间共享** ⇒ 上一个用例造的会话会算进 下一个用例的 `total`（实测 `expected: 3 but
   * was: 4`）。 ★ 不清库来"修"它：清库会掩盖真实的数据隔离问题；而独立归属人反而让"只看自己的"更硬。
   */
  private static UUID newOwner() {
    return UUID.randomUUID();
  }

  @Autowired ConversationListService list;

  @Autowired ConversationRepository conversations;

  @Autowired MessageRepository messages;

  @MockitoBean AppBindingPort appBindings;

  /**
   * 本类验“列表只给我自己的 / 按域分离 / 新建落库”，不验应用可见性。
   *
   * <p>★ 应用夹具是随机 UUID，库里没有 {@code as_assistant} 行；生产实现会按“不存在或看不见”返回空并抛“应用不存在”。 这里替换端口，让任意 appId
   * 都返回一个绑定。
   */
  @BeforeEach
  void allowAnyAppIsVisible() {
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
  }

  @Test
  void listReturnsOnlyMyOwnConversations() {
    UUID owner = newOwner();
    UUID mine = seedConversation(owner, "我的会话");
    seedConversation(UUID.randomUUID(), "别人的会话");

    PageResult<ConversationListService.ConversationListItem> page =
        list.list(owner, null, null, null, 1, 20);

    assertThat(page.list())
        .as("★★ 会话是**私人对话** ⇒ 列表只能有我的。放宽成『按数据范围给全部可见』" + "会让别人的对话出现在我的侧栏 —— 那比『看不到』严重得多")
        .hasSize(1);
    assertThat(page.list().get(0).id()).isEqualTo(mine);
    assertThat(page.list().get(0).ownerId()).as("★ 归属人必须是当前用户").isEqualTo(owner);
  }

  @Test
  void listIsOrderedByRecentActivity() {
    UUID owner = newOwner();
    UUID older = seedConversation(owner, "早一点的");
    UUID newer = seedConversation(owner, "刚聊过的");
    // ★ 让 newer 真的有"最近活动"：给它加一条消息
    messages.save(Message.user(UUID.randomUUID(), newer, "刚刚问的"));

    PageResult<ConversationListService.ConversationListItem> page =
        list.list(owner, null, null, null, 1, 20);

    assertThat(page.list().get(0).id()).as("★ 侧栏默认就该『我刚聊过的在最上面』，否则用户要在列表里翻").isEqualTo(newer);
    assertThat(page.list())
        .extracting(ConversationListService.ConversationListItem::id)
        .as("★ 对照：两条都在（不是只剩一条）")
        .contains(older, newer);
  }

  @Test
  void createPersistsTheConversationWithTheCallerAsOwner() {
    UUID owner = newOwner();
    ConversationListService.ConversationListItem created = list.create(owner, null);

    assertThat(created.ownerId()).as("★★ 新建会话的归属人必须是调用者本人 —— 它是『这条会话只有我能看』的唯一依据").isEqualTo(owner);
    assertThat(conversations.findById(created.id()))
        .as("★ 必须真的落库（『新建会话』按钮的落点，不能只在返回值里存在）")
        .isPresent();
    assertThat(list.list(owner, null, null, null, 1, 20).list())
        .as("★ 而且建完立刻出现在列表里（否则用户点完看不出发生了什么）")
        .extracting(ConversationListService.ConversationListItem::id)
        .contains(created.id());
  }

  @Test
  void genericOnlyExcludesApplicationConversations() {
    // ★★ 用户原话：「应用的对话是不是不应该出现在这里」——「这里」= 通用问答页。
    //
    //   实测症状：通用页列出「法务专家」的会话，点它之后 URL 带上该应用的 appId，
    //   历史列表随即被收窄，用户看到「新会话和第三个会话也没了」。
    //   ⇒ 通用页一开始就不该有应用会话。
    UUID owner = newOwner();
    UUID app = UUID.randomUUID();
    UUID generic = list.create(owner, null).id();
    UUID appScoped = list.create(owner, app).id();

    var page = list.list(owner, null, true, null, 1, 50);

    assertThat(page.list().stream().map(item -> item.id()))
        .as("★ genericOnly=true 时只返回 assistantId 为 null 的会话（应用会话一条都不许有）")
        .contains(generic)
        .doesNotContain(appScoped);
  }

  @Test
  void withoutGenericOnlyTheListStillCoversAllDomains() {
    // ★ 反向：不传 genericOnly 仍是「全部」——工作台「最近会话」靠这条语义
    //   （它要的是跨应用的"最近在忙什么"，不是某一个域）。
    UUID owner = newOwner();
    UUID app = UUID.randomUUID();
    UUID generic = list.create(owner, null).id();
    UUID appScoped = list.create(owner, app).id();

    var page = list.list(owner, null, null, null, 1, 50);

    assertThat(page.list().stream().map(item -> item.id()))
        .as("不传 genericOnly ⇒ 通用与应用都要出现")
        .contains(generic, appScoped);
  }

  @Test
  void negativeOnePageMeansTheLastPage() {
    UUID owner = newOwner();
    for (int i = 0; i < 5; i++) {
      seedConversation(owner, "会话 " + i);
    }

    PageResult<ConversationListService.ConversationListItem> last =
        list.list(owner, null, null, null, -1, 2);

    assertThat(last.page())
        .as("★ 5 条 / 每页 2 ⇒ 最后一页是第 3 页（与历史消息 `page=-1` 同一条约定，不另立一套）")
        .isEqualTo(3);
    assertThat(last.list()).as("★ 最后一页只剩 1 条").hasSize(1);
    assertThat(last.total()).as("★ total 是**全部**条数，不是本页条数").isEqualTo(5L);
  }

  // ────────────────────────── 工具 ──────────────────────────

  private UUID seedConversation(UUID owner, String title) {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, owner, null, title));
    return id;
  }

  /** 只为让"未使用的 import"检查闭嘴：本类不直接用这两个类型。 */
  @SuppressWarnings("unused")
  private static final List<Class<?>> UNUSED =
      List.of(ChatService.class, SecurityContexts.class, ClassificationLevel.class);
}
