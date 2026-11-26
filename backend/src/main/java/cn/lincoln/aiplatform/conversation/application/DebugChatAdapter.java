package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.platform.conversation.DebugChatPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link DebugChatPort} 的实现：把对话侧自带的调试能力（{@link ChatService#debugCompare}）映射成
 * 跨模块形状。
 *
 * <p>★ 不把 {@link ChatService} 自己改成实现这个端口：{@code DebugAnswer} 里带着 {@code SourceRef} 与
 * {@code tokens}，而端口的 record 是另一个形状 —— 直接实现会让两个 record 互相打架，或者逼着端口
 * 搬走对话域的类型（那就把边界问题从 {@code assistant} 挪到了 {@code platform}）。适配器只做
 * 「同一件事换一个宽窄合适的形状」，正是它该做的事。
 */
@Component
class DebugChatAdapter implements DebugChatPort {

  private final ChatService chat;

  DebugChatAdapter(ChatService chat) {
    this.chat = chat;
  }

  @Override
  public DebugComparison debugCompare(UUID appId, String question, String draftRolePrompt) {
    ChatService.DebugComparison cmp = chat.debugCompare(appId, question, draftRolePrompt);
    return new DebugComparison(
        answerOf(cmp.saved()), cmp.draftRan() ? answerOf(cmp.draft()) : null, cmp.draftRan());
  }

  private static DebugAnswer answerOf(ChatService.DebugAnswer answer) {
    return answer == null ? null : new DebugAnswer(answer.answer(), answer.elapsedMs());
  }
}
