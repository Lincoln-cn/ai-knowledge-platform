package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import cn.lincoln.aiplatform.modelcatalog.infrastructure.ChatModelAdapter;
import cn.lincoln.aiplatform.modelcatalog.infrastructure.UpstreamStreamingChatAdapter;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * {@code #142}：端口层的 {@code onUsage} 必须由生产流式适配器转给模型适配器。
 *
 * <p>★ 网关已经能读出 usage；剩下容易漏的一段是「适配器有没有把回调传下去」。修复前它只传 {@code callback::onChunk}，于是 usage 在这里断掉。
 */
class UpstreamStreamingChatAdapterUsageTest {

  @Test
  void forwardsUsageCallbackAndChunksToThePort() {
    ChatModelAdapter chat = mock(ChatModelAdapter.class);
    doAnswer(
            invocation -> {
              Consumer<String> onChunk = invocation.getArgument(4);
              BiConsumer<Integer, Integer> onUsage = invocation.getArgument(5);
              onChunk.accept("答");
              onUsage.accept(137, 55);
              return true;
            })
        .when(chat)
        .streamChat(any(UUID.class), anyList(), anyBoolean(), any(Duration.class), any(), any());

    UpstreamStreamingChatAdapter adapter = new UpstreamStreamingChatAdapter(chat);
    RecordingCallback callback = new RecordingCallback();

    adapter.streamConversation(
        UUID.randomUUID(),
        List.of(new ChatMessage(ChatMessage.Role.USER, "问")),
        true,
        Duration.ofSeconds(5),
        callback);

    assertThat(callback.chunks).containsExactly("答");
    assertThat(callback.usage)
        .as("★★ #142：适配器必须把 onUsage 传下去，否则生产上 done.tokens 仍是 0")
        .isEqualTo("137/55");
    assertThat(callback.end).isEqualTo(ChatStreamPort.End.COMPLETE);
  }

  private static final class RecordingCallback implements ChatStreamPort.Callback {

    private final List<String> chunks = new ArrayList<>();
    private String usage;
    private ChatStreamPort.End end;

    @Override
    public void onChunk(String chunk) {
      chunks.add(chunk);
    }

    @Override
    public void onUsage(int promptTokens, int completionTokens) {
      usage = promptTokens + "/" + completionTokens;
    }

    @Override
    public void onEnd(ChatStreamPort.End end, String detail) {
      this.end = end;
    }
  }
}
