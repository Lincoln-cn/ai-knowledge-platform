package cn.lincoln.aiplatform.knowledge.application;

/**
 * 库级重新向量化的回执（契约 {@code ReembedKbResult}）。
 *
 * <p>★ 只有 {@code queued} 一个数，没有 {@code total}：这个动作是<strong>排队</strong>语义， 真正的进度由 {@code GET
 * /knowledge-bases/{id}/ingestion-progress} 报（进度只在库里，刷新不重置）。 回执再带一个"总数"就多了一份可能与进度对不上的数字 ——
 * 两处各算一遍，迟早不一致。
 *
 * @param queued 本次排队重算的文档数（跳过：未解析、分段失败、无分段、正在向量化中的）
 */
public record ReembedKbView(int queued) {}
