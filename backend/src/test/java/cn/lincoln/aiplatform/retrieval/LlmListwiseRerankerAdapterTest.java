package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.retrieval.application.LlmListwiseRerankerAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmListwiseRerankerAdapter} 的窗口判据。
 *
 * <h3>★ 为什么要单独立一条</h3>
 *
 * <p>这一格曾经写成 {@code Math.min(Math.min(topK, properties.listwiseTopK()), candidateIds.size())} ——
 * 于是界面上把「listwise 窗口」从 5 调到 20，调用方按 20 传进来，它又被启动配置压回 5，而且<b>不报错</b>：读数看起来只是"没什么变化"。 与 {@code #378}
 * 同一族（同一件事在两处各算一遍，其中一处永远赢）。
 *
 * <p>判据取在提示词上：窗口是 20 还是 5，只有第 20 段进不进得去这个差别能看出来，而第 20 段进不去时模型照样给得出一份漂亮顺序。
 */
class LlmListwiseRerankerAdapterTest {

  @Test
  void windowComesFromTheCallerNotTheStartupConfig() {
    RecordingChatPort chat = new RecordingChatPort();
    // 启动配置只给 5：旧实现会用它把窗口压到 5，于是 [19] 根本进不了提示词。
    LlmListwiseRerankerAdapter adapter = new LlmListwiseRerankerAdapter(chat, props(5));

    List<UUID> ids = new ArrayList<>();
    Map<UUID, String> texts = new LinkedHashMap<>();
    for (int i = 0; i < 20; i++) {
      UUID id = UUID.fromString(String.format("51000000-0000-4000-8000-%012d", i));
      ids.add(id);
      texts.put(id, "第 " + i + " 段正文");
    }

    adapter.rerank("问句", ids, texts, 20);

    assertThat(chat.lastPrompt)
        .as("★★ 调用方按库里的 20 传进来时窗口就是 20 —— 这条红了就是又被启动配置压回 5 了")
        .contains("20 段候选资料")
        .contains("[19]");
  }

  private static RerankProperties props(int listwiseTopK) {
    // (enabled, topK, modelPath, tokenizerPath, listwiseEnabled, listwiseTopK, forwardBatch,
    // maxLength)
    return new RerankProperties(false, 10, "m.onnx", "t.json", true, listwiseTopK, 5, 512);
  }

  /** 只负责记下提示词、回一份"从最相关到最不相关"的顺序。 */
  private static final class RecordingChatPort implements ChatPort {

    private String lastPrompt;

    @Override
    public Optional<UUID> pickChatModel() {
      return Optional.of(UUID.fromString("52000000-0000-4000-8000-000000000001"));
    }

    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      lastPrompt = prompt;
      StringBuilder order = new StringBuilder();
      for (int i = 0; i < 20; i++) {
        if (i > 0) {
          order.append(',');
        }
        order.append(i);
      }
      return order.toString();
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      throw new UnsupportedOperationException("listwise 重排不走结构化输出");
    }
  }
}
