package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `#190`：已有会话的应用归属以 conversation.assistant_id 为准，请求体 appId 不得漂移。 */
class ChatTurnWriterBindingTest {

  private static final UUID CONVERSATION_ID =
      UUID.fromString("0b000000-0000-4000-8000-0000000000a1");
  private static final UUID USER_ID = UUID.fromString("0b000000-0000-4000-8000-0000000000a2");
  private static final UUID APP_A = UUID.fromString("0b000000-0000-4000-8000-0000000000a3");
  private static final UUID APP_B = UUID.fromString("0b000000-0000-4000-8000-0000000000a4");
  private static final UUID MODEL_A = UUID.fromString("0b000000-0000-4000-8000-0000000000a5");
  private static final UUID MODEL_B = UUID.fromString("0b000000-0000-4000-8000-0000000000a6");

  @Test
  void appIdFromRequestBodyCannotReplaceTheConversationOwnerApp() {
    Conversation conversation = new Conversation(CONVERSATION_ID, USER_ID, APP_A, "已有会话");
    ConversationRepository conversations = mock(ConversationRepository.class);
    when(conversations.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
    when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(conversations.recordMessage(any())).thenReturn(true);

    MessageRepository messages = mock(MessageRepository.class);
    AppBindingPort appBindings = mock(AppBindingPort.class);
    when(appBindings.findVisibleById(APP_A))
        .thenReturn(
            Optional.of(
                new AppBindingPort.AppBinding(
                    APP_A, MODEL_A, List.of(), true, false, "A 角色", List.of(), null)));
    when(appBindings.findVisibleById(APP_B))
        .thenReturn(
            Optional.of(
                new AppBindingPort.AppBinding(
                    APP_B, MODEL_B, List.of(), true, false, "B 角色", List.of(), null)));

    ChatTurnWriter writer =
        new ChatTurnWriter(conversations, messages, appBindings, mock(ChatPort.class));

    ChatTurnWriter.PreparedTurn turn = writer.prepare(CONVERSATION_ID, APP_B, "换一个应用试试", USER_ID);

    assertThat(turn.appId()).as("会话 A 的 appId 不能被请求体里的 B 覆盖").isEqualTo(APP_A);
    assertThat(turn.modelId()).as("模型必须取会话 A 的应用模型，不能用 B 的").isEqualTo(MODEL_A);
    assertThat(turn.rolePrompt()).isEqualTo("A 角色");
  }
}
