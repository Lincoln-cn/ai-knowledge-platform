package cn.lincoln.aiplatform.knowledge.infrastructure;

import java.util.List;

/**
 * 噪声剥离产物（第 6 周 · 0.2.1 批 2）—— 干净 Markdown + 剥离计数。
 *
 * <p>★ {@code removedNoiseLines} 是 {@code 12 §2.4} 的<strong>可观测纪律</strong>：剥离掉多少行必须能报出来 ——
 * 否则「为什么这份文档少了一页内容」没人能解释（与「失败样本留存」同一性质）。
 *
 * @param markdown 剥离后的 Markdown
 * @param removedNoiseLines 本次剥掉的噪声行数（页眉页脚/页码/目录/版权；空白折叠不计）
 * @param removedLines 被剥掉的原始行（调试与误杀排查用，顺序保持）
 */
public record StripResult(String markdown, int removedNoiseLines, List<String> removedLines) {

  public StripResult {
    markdown = markdown == null ? "" : markdown;
    removedLines = removedLines == null ? List.of() : List.copyOf(removedLines);
  }

  public static StripResult of(String markdown, int removedNoiseLines, List<String> removedLines) {
    return new StripResult(markdown, removedNoiseLines, removedLines);
  }
}
