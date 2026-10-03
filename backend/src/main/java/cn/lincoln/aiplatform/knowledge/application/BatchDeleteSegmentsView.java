package cn.lincoln.aiplatform.knowledge.application;

import java.util.List;

/**
 * 批量删除分段的回执（契约 {@code BatchDeleteSegmentsResult}）。
 *
 * <p>★ 它<strong>刻意分成两半</strong>：{@code deleted} 与 {@code failed}。 批量动作是"尽力而为"语义 —— 一个 id
 * 不存在不该让整批失败（用户选了一屏，其中一个已被别人删掉， 就整批白做，是最让人恼火的一种"严格"）。
 *
 * <p>★ 有 {@code failed} 就<strong>必须逐条给出可读原因</strong>：只回一个数字会让用户去猜"哪个没删掉、为什么"。
 *
 * @param deleted 真正删掉的条数
 * @param failed 没删掉的（含 id 与可读原因）
 */
public record BatchDeleteSegmentsView(int deleted, List<Failure> failed) {

  /** 单条失败：id + 可读原因。 */
  public record Failure(String id, String reason) {}
}
