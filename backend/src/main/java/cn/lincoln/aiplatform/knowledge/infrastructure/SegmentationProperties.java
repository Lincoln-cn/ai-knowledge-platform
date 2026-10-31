package cn.lincoln.aiplatform.knowledge.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分段配置（{@code application.yml} 的 {@code app.segmentation.*}）。
 *
 * <p>★ {@code segmentLength} / {@code overlap} 的默认值与前端向导 {@code CreateWizard.vue} 的 {@code segForm}
 * 默认值一致（500 / 60）—— 否则用户在预览里看到的与入库得到的不同。
 *
 * <p>★ {@code maxChars} 是向量模型字符上限的回落值。① KB 绑定的向量模型有 {@code contextWindow} 则用它（口径：把模型窗口数当字数上限，近似）；②
 * 否则回落这里的默认 1200 —— 与前端 mock 的 {@code MODEL_LIMIT = 1200} 一致，避免 mock/真栈漂移。
 *
 * @param segmentLength 分块目标长度默认值（字符）
 * @param overlap 重叠字符数默认值
 * @param maxChars 向量模型字符上限回落值
 * @param contextWindow 上下文窗口字符数默认值（{@code #65}；{@code 0} = 关闭）。★ 详见 {@code 12 §3.3.1}： <strong>只进
 *     {@code search_text}，不进 {@code content}</strong> —— 正是这一点让它允许跨章节
 */
@ConfigurationProperties(prefix = "app.segmentation")
public record SegmentationProperties(
    int segmentLength, int overlap, int maxChars, int contextWindow) {}
