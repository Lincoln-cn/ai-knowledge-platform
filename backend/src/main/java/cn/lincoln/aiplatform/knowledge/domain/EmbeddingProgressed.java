package cn.lincoln.aiplatform.knowledge.domain;

import java.util.UUID;

/**
 * 向量化进度事件（第 8 周 · 0.3.0 · {@code 03 §3.2} 流水线里的 {@code EV3}）。
 *
 * <p>★ 它携带的是 <strong>事实</strong>：「这篇文档已经完成了 N 段、共 M 段」—— 不是「请去做某事」的指令。
 * 事实型事件可以被重放、可以被多个消费者各取所需；指令型事件重放会重复干活（{@code 03 §3.3} 讨论的就是这件事）。
 *
 * <p>★ 进度值来自文档聚合的<strong>当前事实</strong>（覆盖写之后的数），不是调用方传进来的数： 一次重投的旧事件不该把进度"讲回去"（{@code
 * Document#reportEmbeddingProgress} 的回退规则）。
 *
 * @param documentId 文档 id
 * @param processedSegments 已完成的段数（事实）
 * @param segmentCount 分段总数（事实）
 */
public record EmbeddingProgressed(UUID documentId, int processedSegments, int segmentCount) {

  /** 是否已全部完成 —— 消费者的常见判据，放在事件上避免各处重算。 */
  public boolean completed() {
    return segmentCount > 0 && processedSegments == segmentCount;
  }
}
