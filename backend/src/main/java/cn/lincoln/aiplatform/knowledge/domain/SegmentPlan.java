package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;

/**
 * 分块计划的产物（第 9 周 · 0.3.1）。
 *
 * <p>★ 比"一串分段"多了两个数：{@code removedNoiseLines} 与 {@code contextWindowChars} —— {@code 12 §2.4}
 * 要求剥离掉的噪声**必须记数**， 并在「切分预览」里可见（否则"为什么这份文档少了一页内容"没人能解释）；{@code #65} 判据 3 要求**上下文窗口并入的字符数**同样记数
 * （否则"这周答案变长了"没人能解释）。
 *
 * <p>★ 两者同一条纪律：<b>我们主动增删了进向量的材料，就必须留下可核对的读数</b>。它们的差别只在去向 —— 噪声行数进预览，窗口字数进日志。
 *
 * @param segments 有序分段计划
 * @param removedNoiseLines 噪声剥离掉了多少行（页眉/页脚/页码/目录；0 = 没有噪声）
 * @param contextWindowChars 上下文窗口并入 {@code search_text} 的字符数合计（0 = 窗口关闭或没有邻段）
 */
public record SegmentPlan(
    List<PlannedSegment> segments, int removedNoiseLines, int contextWindowChars) {

  /** 窗口关闭 / 没有邻段时最常见的形态。 */
  public SegmentPlan(List<PlannedSegment> segments, int removedNoiseLines) {
    this(segments, removedNoiseLines, 0);
  }
}
