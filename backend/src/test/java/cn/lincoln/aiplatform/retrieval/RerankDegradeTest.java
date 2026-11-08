package cn.lincoln.aiplatform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.retrieval.application.OnnxRerankerAdapter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 重排的<strong>降级</strong>测试：模型不可用时检索不许被拖垮。
 *
 * <p>★ 口径（与向量那一路<b>相反</b>，是刻意的）：重排是增益件 —— 模型文件不存在 / 初始化失败 / 推理异常 ⇒ 一律<b>返回原序</b>、只 warn 一次，绝不让
 * {@code hitTest} 抛异常。 向量是命脉件（换了向量空间就全错）所以"绝不静默回退"；重排坏了只是退回 RRF 原序，检索照样能用。
 *
 * <p>★ 这里不需要模型文件：断言的是"坏掉的时候行为对不对"，坏模型路径天然可造。
 */
class RerankDegradeTest {

  private static final UUID S1 = seg(1);
  private static final UUID S2 = seg(2);
  private static final UUID S3 = seg(3);
  private static final List<UUID> ORIGINAL = List.of(S1, S2, S3);
  private static final Map<UUID, String> TEXTS = Map.of(S1, "甲", S2, "乙", S3, "丙");

  @Test
  void missingModelFileDegradesToOriginalOrder() {
    RerankProperties missing =
        new RerankProperties(true, 5, "no/such/model.onnx", "no/such/tokenizer.json", false, 5);
    OnnxRerankerAdapter adapter = new OnnxRerankerAdapter(missing);

    // ★ 模型文件不存在 ⇒ 返回原顺序、不抛任何异常（第一次调用 = 冷启动加载路径）
    assertThatCode(
            () ->
                assertThat(adapter.rerank("问题", ORIGINAL, TEXTS, 5))
                    .as("模型缺失 ⇒ 原序")
                    .containsExactly(S1, S2, S3))
        .as("降级不许抛异常 —— 检索不能因为重排坏掉而不可用")
        .doesNotThrowAnyException();

    // ★ 第二次调用走"已缓存的加载失败"路径，同样不抛、同样原序
    //   （顺带钉住"warnOnce 不会把第二次调用变成异常"）
    assertThat(adapter.rerank("问题", ORIGINAL, TEXTS, 5))
        .as("缓存失败后的第二次调用仍是原序")
        .containsExactly(S1, S2, S3);
  }

  @Test
  void degenerateInputsReturnOriginalWithoutTouchingTheModel() {
    OnnxRerankerAdapter adapter =
        new OnnxRerankerAdapter(
            new RerankProperties(
                true, 5, "no/such/model.onnx", "no/such/tokenizer.json", false, 5));

    assertThat(adapter.rerank("问题", List.of(S1), TEXTS, 5))
        .as("单条候选没什么可排的 ⇒ 原序")
        .containsExactly(S1);
    assertThat(adapter.rerank("问题", ORIGINAL, TEXTS, 1))
        .as("topK=1（窗口只剩自己）⇒ 原序")
        .containsExactly(S1, S2, S3);
    assertThat(adapter.rerank("问题", List.of(), Map.of(), 5)).as("空候选 ⇒ 空结果").isEmpty();
    assertThat(adapter.rerank("问题", null, TEXTS, 5)).as("null 候选按契约兜住 ⇒ 不抛").isNull();
  }

  private static UUID seg(int value) {
    return UUID.fromString(String.format("40000000-0000-4000-8000-%012d", value));
  }
}
