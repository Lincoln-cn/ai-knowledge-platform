package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.application.ChatSettingsService;
import cn.lincoln.aiplatform.conversation.application.ChatSettingsView;
import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageKind;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 通用问答也要有开场白（{@code #286} 的延伸）。
 *
 * <h3>★ 为什么单独一个测试</h3>
 *
 * <p>专用应用的开场白由应用自己配（{@code as_assistant.prologue}），路径一直存在。通用问答没有应用 ⇒ 从来没人给它落第一句话 ——
 * 用户点开通用问答只看到一句"开始提问"，不知道这里是什么、能拿它做什么。 这不是少一段文案，而是**两种业务形态里有一种没有被实现**。
 *
 * <p>判据两条：
 *
 * <ol>
 *   <li>没配置时落的是**平台默认文案**（不是空 —— "留空"不等于"不显示"）；
 *   <li>配置了就用配置的那段（管理员改得动才是"可配置"）。
 * </ol>
 */
class GeneralConversationPrologueTest {

  private static final UUID USER_ID = UUID.fromString("0b000000-0000-4000-8000-0000000000a1");

  /** 组装一个"通用问答 + 指定开场白设置值"的假环境。 */
  private static MessageRepository setup(ChatSettingsService settings, String configured) {
    ConversationRepository conversations = mock(ConversationRepository.class);
    when(conversations.findLatestByUserAndAssistant(USER_ID, null)).thenReturn(Optional.empty());
    when(conversations.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));

    MessageRepository messages = mock(MessageRepository.class);
    when(messages.listByConversation(any())).thenReturn(List.of());

    ChatSettingsView view = mock(ChatSettingsView.class);
    when(view.prologue()).thenReturn(configured);
    when(settings.get()).thenReturn(view);

    // appId / appBindings 传 null：通用问答这一档不碰应用；openings 传 null 同理 ——
    // ★ 它们为 null 却仍能跑通，本身就是"两条路已经分开"的证据。
    ConversationListService service =
        new ConversationListService(conversations, null, messages, null, settings);
    service.create(USER_ID, null);
    return messages;
  }

  @Test
  void blankSettingFallsBackToThePlatformDefault() {
    ChatSettingsService settings = mock(ChatSettingsService.class);
    MessageRepository messages = setup(settings, null);

    ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
    verify(messages).save(saved.capture());

    assertThat(saved.getValue().kind()).as("开场白是 PROLOGUE，不是一次回答").isEqualTo(MessageKind.PROLOGUE);
    assertThat(saved.getValue().content())
        .as("★ 留空 = 用平台默认文案；若这里变成空串，用户打开通用问答又只剩一句「开始提问」")
        .isEqualTo(ChatSettingsService.DEFAULT_PROLOGUE);
  }

  @Test
  void configuredSettingWinsOverTheDefault() {
    ChatSettingsService settings = mock(ChatSettingsService.class);
    MessageRepository messages = setup(settings, "  这是这家公司自己的介绍。  ");

    ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
    verify(messages).save(saved.capture());

    assertThat(saved.getValue().content())
        .as("★ 可配置：管理员填的那段必须生效，并且去掉首尾空白")
        .isEqualTo("这是这家公司自己的介绍。");
  }
}
