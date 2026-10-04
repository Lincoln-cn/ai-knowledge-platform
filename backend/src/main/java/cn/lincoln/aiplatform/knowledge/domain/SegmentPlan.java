package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;

/**
 * 分块计划的产物（第 9 周 · 0.3.1）。
 *
 * <p>★ 比"一串分段"多了一个 {@code removedNoiseLines}：{@code 12 §2.4} 要求剥离掉的噪声**必须记数**， 并在「切分预览」里可见 ——
 * 否则"为什么这份文档少了一页内容"没人能解释 （与 {@code 03 §3.5} 的"失败样本留存"同一性质：删掉的东西要看得见）。
 *
 * @param segments 有序分段计划
 * @param removedNoiseLines 噪声剥离掉了多少行（页眉/页脚/页码/目录；0 = 没有噪声）
 */
public record SegmentPlan(List<PlannedSegment> segments, int removedNoiseLines) {}
