package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 分段计划条目（第 7 周 · 0.2.2 批 2a）—— 预览与入库共用的唯一产物形态。
 *
 * <p>★ 为什么不复用 {@link SegmentChunk}：分块器只管「切」，前缀与哈希是 {@link Segmenter} 才补上的 （{@code 12 §4.2}）。混在一个
 * record 里会让调用方误以为 chunk 阶段就已经有 {@code search_text} —— 批 2b
 * 的预览端点和入库接线都必须拿<strong>本</strong>类型，不允许各自再拼一遍前缀。
 *
 * @param ordinal 在文档中的顺序（从 0 起）
 * @param content 原文（Markdown 片段；结构化前缀绝不进 content，{@code 12 §4.2} 纪律 1； 重叠区属于正文上下文，会进 content）
 * @param searchText 前缀 + 分隔 + 正文 —— 向量化与全文检索的输入
 * @param contentHash sha256 十六进制 64 位，含前缀完整组成（{@code SEG-I7}）
 * @param title 分段标题（{@code 12 §3.4}）
 * @param titlePath 章节路径 {@code H1 > H2 > H3}
 * @param isTable 表格块
 * @param isCode 代码围栏块
 * @param oversizedRisk 被 L4 硬切过的标记
 */
public record PlannedSegment(
    int ordinal,
    String content,
    String searchText,
    String contentHash,
    String title,
    String titlePath,
    boolean isTable,
    boolean isCode,
    boolean oversizedRisk) {

  public int charCount() {
    return content == null ? 0 : content.length();
  }
}
