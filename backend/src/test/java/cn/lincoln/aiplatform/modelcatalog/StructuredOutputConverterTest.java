package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 官方结构化输出转换器的<strong>行为特性</strong>（{@code 11 §9.2} 的手法：把"我们对框架的假设"写成断言）。
 *
 * <h3>为什么这个类存在</h3>
 *
 * <p>多跳判定原来是<strong>自研解析</strong>（剥 {@code ```json} 围栏、容忍字段别名 {@code next_query}、从废话里抠出 JSON）。那正是
 * {@code 11 §2.7} 说"实现结构化输出时先看官方，不要又写一层"的位置。改用官方 {@code BeanOutputConverter}
 * 之后，<strong>"我们的容错能不能删"必须由读数决定，而不是由印象决定</strong> —— 所以把下面这些形状做成断言，其中每一条都对应一处被删掉的自研代码：
 *
 * <ul>
 *   <li>围栏：官方转换器自己处理 ⇒ 我们的"剥围栏"是多余的
 *   <li>缺字段：包装类型得到 {@code null}（这是 {@code HopDecision} "字段缺失 ≠ false" 的前提）
 *   <li>坏 JSON：抛运行时异常（适配器据此转成一次可读的调用失败，让调用方降级）
 * </ul>
 *
 * <p>★ 用本类自己的 {@link Sample} 而不是多跳的 {@code HopDecision.Payload}：{@code modelcatalog}
 * <strong>不依赖</strong> {@code retrieval}（{@code 01 §3.2} 依赖矩阵），而这个类钉的是<strong>转换器</strong>的行为，
 * 与具体业务形状无关。
 */
class StructuredOutputConverterTest {

  /** 与多跳判定同形的两个字段：包装 Boolean + String。 */
  record Sample(Boolean sufficient, String nextQuery) {}

  private final BeanOutputConverter<Sample> converter = new BeanOutputConverter<>(Sample.class);

  /** 格式说明必须带上字段名与 schema —— 这是"提示词里不再手写 JSON 契约"之后，模型知道形状的唯一来源。 */
  @Test
  void formatCarriesSchemaAndFieldNames() {
    assertThat(converter.getFormat()).contains("sufficient").contains("nextQuery");
    assertThat(converter.getJsonSchema()).contains("sufficient");
  }

  @Test
  void readsPlainJson() {
    Sample sample = converter.convert("{\"sufficient\": true, \"nextQuery\": \"再查一次\"}");

    assertThat(sample.sufficient()).isTrue();
    assertThat(sample.nextQuery()).isEqualTo("再查一次");
  }

  /** ★ 围栏：这条断言就是"删掉自研剥围栏"的依据。 */
  @Test
  void readsJsonWrappedInMarkdownFence() {
    Sample sample =
        converter.convert("```json\n{\"sufficient\": false, \"nextQuery\": \"换个词再查\"}\n```");

    assertThat(sample.sufficient()).isFalse();
    assertThat(sample.nextQuery()).isEqualTo("换个词再查");
  }

  /** ★ 缺字段 ⇒ {@code null}，不是 {@code false} —— 多跳判定据此把这次判定判成"不可用"。 */
  @Test
  void missingFieldYieldsNullInsteadOfDefault() {
    Sample sample = converter.convert("{\"nextQuery\": \"再查一次\"}");

    assertThat(sample.sufficient()).isNull();
  }

  /** 不是 JSON ⇒ 抛运行时异常（适配器转成"这一次调用失败"，调用方据此降级，而不是收下一个默认值）。 */
  @Test
  void malformedOutputFails() {
    assertThatThrownBy(() -> converter.convert("这句话里没有 JSON")).isInstanceOf(RuntimeException.class);
  }

  /**
   * ★ 前后有废话（但里面是 JSON）：<strong>官方转换器不认</strong> —— 这是从自研解析换过来时让掉的一条容错。
   *
   * <p>让掉它是划算的：说明模型该输出什么形状本来就由 {@code getFormat()} 负责，模型不照做就是一次失败 ⇒ 调用方降级（{@code 10
   * §3.4.6}）。把这条读出来写在案上，比"以为它还容错着"强。
   */
  @Test
  void proseAroundJsonIsNotTolerated() {
    assertThatThrownBy(
            () -> converter.convert("好的，结果如下：{\"sufficient\": true, \"nextQuery\": null} 希望有帮助"))
        .isInstanceOf(RuntimeException.class);
  }
}
