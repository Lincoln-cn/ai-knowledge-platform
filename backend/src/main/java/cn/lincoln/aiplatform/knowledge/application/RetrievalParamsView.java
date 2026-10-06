package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;

/**
 * 检索参数（契约 {@code RetrievalParams}）—— {@code GET|PUT /knowledge-bases/:kbId/retrieval} 的回包。
 *
 * <p>★ 字段与契约一字不差（{@code mode / threshold / topN / noRefStrategy}）。 新库恒有值（库层四列 {@code not null
 * default}）—— 所以不会出现"设置面板刚建好的库弹 404" （mock 的注释把这条写明了：回默认值，不报不存在）。
 */
public record RetrievalParamsView(
    RetrievalMode mode, double threshold, int topN, NoRefStrategy noRefStrategy) {}
