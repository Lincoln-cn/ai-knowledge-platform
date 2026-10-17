package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatHistoryService;
import cn.lincoln.aiplatform.conversation.application.ChatMessageView;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.platform.web.PageResult;
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
 * ★★ {@code A2}（数据真实性 ·「<strong>刷新页面不重置</strong>」）的<strong>读取侧读数</strong>。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>{@code ROADMAP §2.2} 把 A2 称作"<strong>最容易被绕过、也最能说明问题的一条</strong>"：
 * <em>"任意时刻的进度与状态必须与数据库实际状态一致，刷新页面不重置。 前端现在的进度是 mock 推进的 —— 这一条通过，就等于『后端真的接管了状态』。"</em>
 *
 * <p>★ 在此之前，A2 只有**写侧**的读数（{@code #108} 的单调更新、{@code MessageStateMachineTest} 的状态机），
 * 而**"刷新"这个动作走的是读侧** —— 一条消息在生成到一半时被重新读出来， 必须是 <strong>{@code GENERATING} +
 * 已写的那部分正文</strong>，而不是"空白"或"已完成"。 那两者在界面上完全不可分：一个显示光标在闪，一个显示空白答案。
 *
 * <h3>★★ 判据的形状：写一半 → 关掉读的那个上下文 → 重新读</h3>
 *
 * <p>与 {@code MessagePersistenceTest} 同一条口径：<strong>不开测试级事务</strong> ⇒ {@link
 * ChatHistoryService#list} 的每一次调用都在自己的事务里，读的是**库里的真值** （Hibernate 一级缓存不跨事务存活）。★ 若只查同一个持久化上下文，
 * "改了内存但没落库"的实现会完全通过。
 *
 * <h3>四条断言（各对应一种"刷新后会骗人"的形状）</h3>
 *
 * <ol>
 *   <li><b>状态仍是 {@code GENERATING}</b> —— 变成 {@code GENERATED} 就是"界面以为答完了"；
 *   <li><b>已写的正文还在</b> —— 丢正文就是"刷新后答案消失了"（用户会以为白等了）；
 *   <li><b>引用快照还在</b> —— {@code refs} 是 A4 的载体，刷新后丢它等于"溯源按钮消失"；
 *   <li>★ <b>会话预览里的最后一条也对得上</b> —— 列表页与聊天页读的是同一个库，不该各自说一套。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class ConversationStateSurvivesReloadTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID OWNER = UUID.fromString("1e000000-0000-4000-8000-0000000000aa");

  @Autowired ConversationRepository conversations;

  @Autowired MessageRepository messages;

  @Autowired ChatHistoryService history;

  @Test
  void aGeneratingMessageStillReadsAsGeneratingWithItsPartialTextAfterAReload() {
    UUID conversationId = seedConversation();
    Message assistant = new Message(UUID.randomUUID(), conversationId, "");
    // ★ 写到一半：状态仍是 GENERATING（终态由生成线程落库）
    assistant.appendText("差旅住宿标准为每人每天");
    messages.save(assistant);

    PageResult<ChatMessageView> page = history.list(conversationId, OWNER, -1, 20, null);

    assertThat(page.list()).as("★ 前置：这条消息必须真的被读出来（否则下面每条断言都是空跑）").hasSize(1);
    ChatMessageView read = page.list().get(0);
    assertThat(read.id()).isEqualTo(assistant.id());

    assertThat(read.status())
        .as("★★ A2：生成中的消息重新读出来必须仍是 GENERATING —— " + "若读成 GENERATED，界面就会显示『答完了』，而实际上连接还在跑")
        .isEqualTo(MessageStatus.GENERATING);

    assertThat(read.content())
        .as("★★ A2：已写的那部分正文必须还在 —— 丢正文就是『刷新后答案消失』，用户会以为白等了")
        .isEqualTo("差旅住宿标准为每人每天");
  }

  @Test
  void referencesWrittenBeforeTheReloadAreStillThere() {
    UUID conversationId = seedConversation();
    Message assistant = new Message(UUID.randomUUID(), conversationId, "");
    assistant.appendText("按制度第 3 条……");
    // ★ 引用在流早期就落库（refs 帧先于 delta），所以"写到一半"时它应当已经在
    assistant.bindReferences(
        java.util.List.of(
            new cn.lincoln.aiplatform.conversation.domain.SourceRef(
                1,
                "第 3 条",
                "报销制度.pdf",
                "制度库",
                0.91,
                1,
                "差旅住宿标准为每人每天 500 元。",
                "seg-1",
                "doc-1",
                "kb-1",
                cn.lincoln.aiplatform.platform.access.ClassificationLevel.INTERNAL)));
    messages.save(assistant);

    ChatMessageView read = history.list(conversationId, OWNER, -1, 20, null).list().get(0);

    assertThat(read.refs()).as("★★ A4/A2：引用快照必须随消息一起读回来 —— 刷新后丢它等于『溯源按钮消失』").hasSize(1);
    assertThat(read.refs().get(0)).as("★ 而且内容真的在（不是只留了一个空壳占位）").containsEntry("title", "第 3 条");
  }

  @Test
  void aFinishedTurnReadsBackAsFinishedAfterAReload() {
    // ★ 反向对照：一条**已经收尾**的消息刷新后必须仍是终态。
    //   没有这一条，一个"永远返回 GENERATING"的坏实现也能让上面两条绿。
    UUID conversationId = seedConversation();
    Message assistant = new Message(UUID.randomUUID(), conversationId, "");
    assistant.appendText("完整答案");
    assistant.complete(null, 12L);
    messages.save(assistant);

    ChatMessageView read = history.list(conversationId, OWNER, -1, 20, null).list().get(0);

    assertThat(read.status())
        .as("★ 反向对照：收尾过的消息刷新后必须是 GENERATED（防『永远 GENERATING』的假绿）")
        .isEqualTo(MessageStatus.GENERATED);
    assertThat(read.content()).isEqualTo("完整答案");
  }

  // ────────────────────────── 工具 ──────────────────────────

  private UUID seedConversation() {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, OWNER, null, "A2 刷新不重置夹具"));
    return id;
  }
}
