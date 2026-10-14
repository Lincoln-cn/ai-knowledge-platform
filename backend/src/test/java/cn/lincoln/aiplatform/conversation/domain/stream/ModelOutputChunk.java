package cn.lincoln.aiplatform.conversation.domain.stream;

import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * <strong>假模型</strong>：模型输出流的一个元素（本批完全不接真模型）。
 *
 * <p>契约（{@code frontend/src/api/stream-types.ts}）说得很清楚，模型输出流要提供三样东西： <strong>增量文本 + 终态 +
 * tokens</strong>。本文件就是这三样东西在测试里的<strong>唯一</strong>来源 —— 于是"装配器把模型输出流缝成事件序列"这件事可以在<strong>不起
 * Spring、不起容器、不连真模型</strong>的前提下断言 （{@code ROADMAP §4.7.4} 第 1 条：先用假模型把六条数值与形状写成契约测试）。
 *
 * <h3>★ 为什么是 sealed + switch，而不是"一个带 kind 字段的类"</h3>
 *
 * <p>同一个理由与 {@link StreamEvent} 一样：sealed 让"模型流还能给出哪几种东西"是一个<strong>封闭清单</strong>。 第 17
 * 周接真模型时若真需要第四种（例如工具调用），加一个 {@code permits} 成员会让所有分派点编译期暴露。
 *
 * <p>★ <strong>假模型的边界</strong>：它只吐"文本 / 用量"两类东西。<strong>"停止 / 断开 / 超时"不由模型流产生</strong> ——
 * 它们是<strong>链路</strong>的三个外部事件（用户点了停止 / socket 断了 / 定时器到期），所以那三条路径在测试里 直接调 {@link
 * StreamAssembler#finish}，而不是伪造一个"模型说它超时了"的块。把链路事件塞进模型流， 会让"谁该负责终止"这件事在假模型里就定错一次。
 */
sealed interface ModelOutputChunk permits ModelOutputChunk.Partial, ModelOutputChunk.Usage {

  /** 一段增量文本（真的模型在流式返回时会给出很多个）。 */
  record Partial(String text) implements ModelOutputChunk {}

  /** 一次模型调用的用量（{@code 10 §8.4}：查询理解 / 多跳判定 / 生成<strong>各报一次</strong>）。 */
  record Usage(TokenUsage usage) implements ModelOutputChunk {}

  // ────────────────────────── 夹具工厂（测试用） ──────────────────────────

  /**
   * 生成阶段吐出的 chunk 序列：{@code texts} 各一段 + 最后一条生成用量。
   *
   * <p>★ 返回<strong>多段</strong>而不是一段：{@code delta} 是"逐字增量"， 一段整体文本会让"每个 chunk 各发一条
   * delta"与"最后发一次"在断言里看起来一模一样。
   *
   * @param texts 逐段文本
   * @return 模型输出流（按顺序）
   */
  static List<ModelOutputChunk> generated(String... texts) {
    List<ModelOutputChunk> chunks = new ArrayList<>();
    for (String text : texts) {
      chunks.add(new Partial(text));
    }
    return List.copyOf(chunks);
  }

  /**
   * 同上，但把生成那一次的用量也带上（真实链路的形状：文本块在前，用量在末尾一起回来）。
   *
   * @param texts 逐段文本
   * @param generationUsage 生成那一次调用的用量
   * @return 模型输出流（按顺序）
   */
  static List<ModelOutputChunk> generated(String[] texts, TokenUsage generationUsage) {
    List<ModelOutputChunk> chunks = new ArrayList<>(generated(texts));
    chunks.add(new Usage(generationUsage));
    return List.copyOf(chunks);
  }
}
