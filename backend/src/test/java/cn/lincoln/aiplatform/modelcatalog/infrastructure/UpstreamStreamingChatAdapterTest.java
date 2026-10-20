package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** `#185` 的生产端口：ChatService 传下来的 thinking 必须继续传给 ChatModelAdapter。 */
class UpstreamStreamingChatAdapterTest {

  @Test
  void forwardsThinkingFlagToTheModelAdapter() {
    ChatModelAdapter chat = mock(ChatModelAdapter.class);
    UUID modelId = UUID.randomUUID();
    when(chat.streamChat(eq(modelId), anyList(), eq(true), any(), any())).thenReturn(true);
    ChatStreamPort.Callback callback = mock(ChatStreamPort.Callback.class);

    new UpstreamStreamingChatAdapter(chat)
        .stream(modelId, "问题", true, Duration.ofSeconds(5), callback);

    verify(chat).streamChat(eq(modelId), anyList(), eq(true), eq(Duration.ofSeconds(5)), any());
    verify(callback).onEnd(ChatStreamPort.End.COMPLETE, null);
  }
}
