package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 通用问答的「默认对话模型」必须真的被用上（`#1.8` 收尾发现）。
 *
 * <p>★ 之前这一格是<strong>死配置</strong>：设置页能选、能存、能回显，而全链路没有一处读它 —— {@code pickModelId}
 * 只认"应用绑定的模型"，通用问答没有应用，于是永远落到 {@code pickChatModel()} （挑第一个已加载的）。实测症状：把默认模型改成某个桩模型后，流里的 {@code
 * model} 帧仍是 deepseek-flash，看起来"设置没保存"，其实是一直没被读。
 *
 * <p>★ 两条断言缺一不可：一条钉"配了就真用"，一条钉"配了但不能用时不硬调"。 只钉第一条会把"配错一次全线失败"放进来 —— 模型可以被卸载/停用，到那时照配置硬调，
 * 报错还是"模型不存在"，与真正的原因（管理员没点加载）差得很远。
 */
class ChatTurnWriterDefaultModelTest {

  private static final UUID USER_ID = UUID.fromString("0c000000-0000-4000-8000-0000000000b1");
  private static final UUID CONFIGURED = UUID.fromString("0c000000-0000-4000-8000-0000000000b2");
  private static final UUID AUTO_PICKED = UUID.fromString("0c000000-0000-4000-8000-0000000000b3");

  @Test
  void configuredDefaultModelIsActuallyUsedForGeneralChat() {
    ChatPort chatModels = mock(ChatPort.class);
    when(chatModels.isChatModelUsable(CONFIGURED)).thenReturn(true);
    when(chatModels.pickChatModel()).thenReturn(Optional.of(AUTO_PICKED));

    ChatTurnWriter.PreparedTurn turn =
        writer(chatModels, CONFIGURED).prepare(null, null, "你好", USER_ID);

    assertThat(turn.modelId()).as("设置里配了默认对话模型 ⇒ 通用问答必须用它，而不是顺手挑一个").isEqualTo(CONFIGURED);
  }

  @Test
  void unusableConfiguredModelFallsBackInsteadOfFailingEveryTurn() {
    ChatPort chatModels = mock(ChatPort.class);
    when(chatModels.isChatModelUsable(CONFIGURED)).thenReturn(false);
    when(chatModels.pickChatModel()).thenReturn(Optional.of(AUTO_PICKED));

    ChatTurnWriter.PreparedTurn turn =
        writer(chatModels, CONFIGURED).prepare(null, null, "你好", USER_ID);

    assertThat(turn.modelId()).as("配的模型卸载了 ⇒ 退回自动挑一个，而不是让每一轮问答都失败").isEqualTo(AUTO_PICKED);
  }

  /** 通用问答那一档：没有应用、没有既有会话（`conversationId` 为空 ⇒ 新建一条）。 */
  private ChatTurnWriter writer(ChatPort chatModels, UUID configuredModel) {
    ConversationRepository conversations = mock(ConversationRepository.class);
    when(conversations.findLatestByUserAndAssistant(any(), any())).thenReturn(Optional.empty());
    when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(conversations.recordMessage(any())).thenReturn(true);

    ChatPolicyResolver policy = mock(ChatPolicyResolver.class);
    when(policy.defaultModelId()).thenReturn(configuredModel);

    ChatTurnWriter writer =
        new ChatTurnWriter(
            conversations, mock(MessageRepository.class), mock(AppBindingPort.class), chatModels);
    writer.setPolicyResolver(policy);
    return writer;
  }
}
