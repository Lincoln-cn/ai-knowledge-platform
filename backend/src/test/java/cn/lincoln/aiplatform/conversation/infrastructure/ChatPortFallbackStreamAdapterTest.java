package cn.lincoln.aiplatform.conversation.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code #199}：兜底流式适配器必须把一次性补全的 usage 报给端口，而不是静默丢成 0。 */
class ChatPortFallbackStreamAdapterTest {

  private static final UUID MODEL = UUID.randomUUID();
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  @Test
  void reportsUsageBeforeChunksAndDone() {
    FakeChat chat = new FakeChat(new ChatPort.Completion("答案", 7, 3));
    RecordingCallback callback = new RecordingCallback();

    new ChatPortFallbackStreamAdapter(chat).stream(MODEL, "问题", TIMEOUT, callback);

    assertEquals(List.of("usage:7:3", "chunk:答案", "end:COMPLETE"), callback.events);
    assertTrue(chat.usedUsageMethod);
  }

  @Test
  void doesNotFabricateZeroWhenUpstreamHasNoUsage() {
    FakeChat chat = new FakeChat(ChatPort.Completion.withoutUsage("答案"));
    RecordingCallback callback = new RecordingCallback();

    new ChatPortFallbackStreamAdapter(chat).stream(MODEL, "问题", TIMEOUT, callback);

    assertFalse(
        callback.events.stream().anyMatch(event -> event.startsWith("usage:")),
        callback.events.toString());
    assertEquals(List.of("chunk:答案", "end:COMPLETE"), callback.events);
  }

  @Test
  void conversationUsesTheRoleAwareUsageMethod() {
    FakeChat chat = new FakeChat(new ChatPort.Completion("多轮答案", 5, 2));
    RecordingCallback callback = new RecordingCallback();
    List<ChatMessage> messages =
        List.of(
            new ChatMessage(ChatMessage.Role.USER, "第一问"),
            new ChatMessage(ChatMessage.Role.ASSISTANT, "第一答"),
            new ChatMessage(ChatMessage.Role.USER, "第二问"));

    new ChatPortFallbackStreamAdapter(chat).streamConversation(MODEL, messages, TIMEOUT, callback);

    assertEquals(List.of("usage:5:2", "chunk:多轮答案", "end:COMPLETE"), callback.events);
    assertTrue(chat.usedConversationUsageMethod);
    assertEquals(3, chat.lastMessages.size());
  }

  /** 只实现用量出口；旧出口被调用即失败，防止测试替身把两条路混在一起。 */
  private static final class FakeChat implements ChatPort {

    private final Completion completion;
    private boolean usedUsageMethod;
    private boolean usedConversationUsageMethod;
    private List<ChatMessage> lastMessages = List.of();

    private FakeChat(Completion completion) {
      this.completion = completion;
    }

    @Override
    public Optional<UUID> pickChatModel() {
      return Optional.of(MODEL);
    }

    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      throw new AssertionError("兜底流式不应再走无用量出口 complete()");
    }

    @Override
    public Completion completeWithUsage(UUID modelId, String prompt, Duration timeout) {
      usedUsageMethod = true;
      return completion;
    }

    @Override
    public String completeConversation(UUID modelId, List<ChatMessage> messages, Duration timeout) {
      throw new AssertionError("兜底流式不应再走无用量出口 completeConversation()");
    }

    @Override
    public Completion completeConversationWithUsage(
        UUID modelId, List<ChatMessage> messages, Duration timeout) {
      usedConversationUsageMethod = true;
      lastMessages = messages;
      return completion;
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      throw new AssertionError("本测试不涉及结构化输出");
    }
  }

  private static final class RecordingCallback implements ChatStreamPort.Callback {

    private final List<String> events = new ArrayList<>();

    @Override
    public void onChunk(String chunk) {
      events.add("chunk:" + chunk);
    }

    @Override
    public void onUsage(int promptTokens, int completionTokens) {
      events.add("usage:" + promptTokens + ":" + completionTokens);
    }

    @Override
    public void onEnd(ChatStreamPort.End end, String detail) {
      events.add("end:" + end);
    }
  }
}
