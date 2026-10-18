package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
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
 * ★★ 会话创建幂等 + 占位标题（`02 §2.6`）的判据。
 *
 * <h3>两个真实症状（都实测过）</h3>
 *
 * <ol>
 *   <li><b>空会话堆积</b>：每点一次「新建会话」就真建一行空会话。点 5 次，列表里 5 个同名 「新会话」⇒
 *       用户的原话是<b>「新会话没了」「像刷新了一下」</b>（其实是同名项太多，认不出哪个是刚建的）。
 *   <li><b>标题永远停在占位值</b>：前端「新建会话」先建空会话、再发消息，而「会话已存在」 那条路原本<b>不改标题</b> ⇒ 会话有 2 条消息、标题仍叫「新会话」。
 * </ol>
 *
 * <h3>判据边界</h3>
 *
 * <p>★ 只复用「<b>最近一条</b>是空的」：若最近一条已有消息，说明用户真的在开新话题， 这时必须新建 —— 去翻更早的空会话会让「新建」跳到很久以前的会话上。
 */
@SpringBootTest
@Testcontainers
class ConversationCreateIdempotentTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID USER = UUID.fromString("40000000-0000-4000-8000-0000000000a1");
  private static final UUID APP = UUID.fromString("40000000-0000-4000-8000-0000000000b1");

  @Autowired ConversationListService conversations;

  @Autowired ConversationRepository repository;

  @Test
  void creatingTwiceWithNoMessagesReturnsTheSameConversation() {
    UUID first = conversations.create(USER, null).id();
    UUID second = conversations.create(USER, null).id();

    assertThat(second)
        .as("★★ 已有空会话时必须复用 —— 这是「点 5 次新建就有 5 个同名『新会话』」的根治。" + "若这里两个 id 不同，说明每次点击都在新建")
        .isEqualTo(first);
  }

  @Test
  void anEmptyConversationIsReusedButOneWithMessagesIsNot() {
    UUID empty = conversations.create(USER, APP).id();

    // 把这条会话的 message_count 变成 1（模拟用户已经问了第一句）
    Conversation conversation = repository.findById(empty).orElseThrow();
    repository.recordMessage(conversation.id());

    UUID next = conversations.create(USER, APP).id();

    assertThat(next)
        .as("★ 最近一条已有消息 ⇒ 必须新建（用户真的在开新话题）。" + "若这里仍等于旧 id，说明实现去翻了更早的空会话，『新建』会跳到老会话上")
        .isNotEqualTo(empty);
  }

  @Test
  void newConversationStartsWithThePlaceholderTitle() {
    UUID fresh = conversations.create(UUID.randomUUID(), null).id();

    assertThat(repository.findById(fresh).orElseThrow().title())
        .as("★ 新建时是占位标题，首个问题到来后由 ChatTurnWriter 替换（见 ChatTurnTitleTest）")
        .isEqualTo(Conversation.PLACEHOLDER_TITLE);
  }

  @Test
  void genericAndAppConversationsAreNotMixed() {
    UUID generic = conversations.create(USER, null).id();
    UUID appScoped = conversations.create(USER, APP).id();

    assertThat(appScoped).as("★ 复用必须限定「同应用」：通用问答的空会话不能被应用会话复用（反之亦然）—— 否则会串库").isNotEqualTo(generic);
  }
}
