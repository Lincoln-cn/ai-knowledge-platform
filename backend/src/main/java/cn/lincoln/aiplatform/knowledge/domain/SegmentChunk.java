package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 分块产物（第 7 周 · 0.2.2）。
 *
 * <p>★ 每块都要带 {@code ordinal + titlePath + isTable/isCode/oversizedRisk}（{@code 12 §3.2} 规则 4） ——
 * 后面的结构化前缀、检索过滤、质量观测都用它。
 *
 * @param ordinal 在文档中的顺序（从 0 起）
 * @param content 原文（Markdown 片段；前缀绝不进 content，{@code 12 §4.2} 纪律 1）
 * @param title 分段标题（{@code 12 §3.4}）
 * @param titlePath 章节路径 {@code H1 > H2 > H3}（前缀主体）
 * @param isTable 表格块（整表一块，不跨切）
 * @param isCode 代码围栏块（整块保留）
 * @param oversizedRisk 被 L4 硬切过的标记
 */
public record SegmentChunk(
    int ordinal,
    String content,
    String title,
    String titlePath,
    boolean isTable,
    boolean isCode,
    boolean oversizedRisk) {

  public int charCount() {
    return content == null ? 0 : content.length();
  }
}
