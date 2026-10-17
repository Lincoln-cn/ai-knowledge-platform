package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ A6 的前置判据（`#168`）：**「10 轮上下文能指代」依赖的历史，真的被读出来了吗？**
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>`ROADMAP §2.2` 的 A6 是「第 10 轮问『它呢』，能正确对上第 9 轮的话题」。 而在此之前的一个事实是：**历史从来没有被送进模型** ——
 * `promptOf(question, refs)` 只拼当前问题 + 参考资料，`ChatService` 连历史都不读（`#168`）。
 *
 * <p>★ 指代**本身**需要真模型才能判（那部分仍然缺读数，如实记在 `#168`）； 但**指代所依赖的前提**——"前几轮真的被取出来、按角色、按顺序、且不含本轮"—— 是可以判的。
 * 本类判的就是这个前提。
 *
 * <h3>五条判据（各对应一种写错的形状）</h3>
 *
 * <ol>
 *   <li><b>只取本轮之前的</b> —— 把本轮自己也塞进历史，模型会看到"自己重复了一遍问题"；
 *   <li><b>按时间正序</b> —— 顺序反了，模型会把因果读反；
 *   <li><b>角色正确</b> —— 全标成 `USER` 等于告诉模型"这些全是用户说的"， 而反向注入（`#84`）正是靠这种混淆；
 *   <li>★ <b>空正文的助手消息不进历史</b> —— 一条 `GENERATING`（空正文）送进去， 模型会以为"上一轮它答了个空"；
 *   <li>★ <b>硬上限</b> —— 无上限时上下文会无限长，首字延迟（B4 ≤10s）必被拖垮。
 * </ol>
 */
@SpringBootTest
@Testcontainers
class ChatHistoryBeforeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired ChatTurnWriter writer;

  @Autowired ConversationRepository conversations;

  @Autowired MessageRepository messages;

  @Test
  void historyIsEverythingBeforeThisTurnInChronologicalOrderWithRoles() {
    UUID owner = UUID.randomUUID();
    UUID conversation = seedConversation(owner);

    Message firstUser = messages.save(Message.user(UUID.randomUUID(), conversation, "第一个问题"));
    messages.save(new Message(UUID.randomUUID(), conversation, "第一个回答"));
    Message secondUser = messages.save(Message.user(UUID.randomUUID(), conversation, "第二个问题"));
    messages.save(new Message(UUID.randomUUID(), conversation, "第二个回答"));
    Message thirdUser = messages.save(Message.user(UUID.randomUUID(), conversation, "第三个问题"));

    List<ChatMessage> history = writer.historyBefore(conversation, thirdUser.id(), 20);

    assertThat(history).as("★ 只取本轮之前的四条（不含第三条用户消息）").hasSize(4);
    assertThat(history)
        .as("★ 按时间正序 —— 顺序反了模型会把因果读反")
        .extracting(ChatMessage::content)
        .containsExactly("第一个问题", "第一个回答", "第二个问题", "第二个回答");
    assertThat(history)
        .as("★★ 角色必须正确 —— 全标成 USER 等于告诉模型『这些全是用户说的』，而 #84 正是靠这种混淆")
        .extracting(ChatMessage::role)
        .containsExactly(
            ChatMessage.Role.USER,
            ChatMessage.Role.ASSISTANT,
            ChatMessage.Role.USER,
            ChatMessage.Role.ASSISTANT);
    assertThat(history)
        .as("★ 本轮自己不许出现在历史里（否则模型会看到『自己重复了一遍问题』）")
        .extracting(ChatMessage::content)
        .doesNotContain("第三个问题");
    assertThat(firstUser.id()).as("前置：第一条用户消息确实落库了").isNotNull();
  }

  @Test
  void emptyAssistantBodiesAreLeftOut() {
    UUID owner = UUID.randomUUID();
    UUID conversation = seedConversation(owner);

    messages.save(Message.user(UUID.randomUUID(), conversation, "问过了"));
    // ★ 一条还没生成完的助手消息（空正文）—— 送进模型会让它以为"上一轮它答了个空"
    messages.save(new Message(UUID.randomUUID(), conversation, ""));
    Message current = messages.save(Message.user(UUID.randomUUID(), conversation, "再问"));

    assertThat(writer.historyBefore(conversation, current.id(), 20))
        .as("★ 空正文的助手消息不进历史 —— 那不是『上一轮答了空』，而是『还没答完』")
        .hasSize(1)
        .extracting(ChatMessage::content)
        .containsExactly("问过了");
  }

  @Test
  void historyIsCappedAtTheNewestMessages() {
    UUID owner = UUID.randomUUID();
    UUID conversation = seedConversation(owner);

    for (int i = 0; i < 5; i++) {
      messages.save(Message.user(UUID.randomUUID(), conversation, "问 " + i));
      messages.save(new Message(UUID.randomUUID(), conversation, "答 " + i));
    }
    Message current = messages.save(Message.user(UUID.randomUUID(), conversation, "最后一问"));

    List<ChatMessage> history = writer.historyBefore(conversation, current.id(), 3);

    assertThat(history)
        .as("★★ 硬上限必须只留**最近的**几条 —— 没有上限时上下文会无限长，" + "而首字延迟（B4 ≤10s）必被拖垮")
        .hasSize(3);
    assertThat(history)
        .as("★ 且留下的是**最近的**（不是最早的）")
        .extracting(ChatMessage::content)
        .containsExactly("答 3", "问 4", "答 4");
  }

  // ────────────────────────── 工具 ──────────────────────────

  private UUID seedConversation(UUID owner) {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, owner, null, "历史判据用的会话"));
    return id;
  }
}
