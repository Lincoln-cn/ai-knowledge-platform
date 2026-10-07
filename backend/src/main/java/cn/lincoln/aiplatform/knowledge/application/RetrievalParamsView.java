package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;

/**
 * 检索参数（契约 {@code RetrievalParams}）—— {@code GET|PUT /knowledge-bases/:kbId/retrieval} 的回包。
 *
 * <p>★ 字段与契约一字不差（{@code mode / threshold / topN / noRefStrategy}）。 新库恒有值（库层四列 {@code not null
 * default}）—— 所以不会出现"设置面板刚建好的库弹 404" （mock 的注释把这条写明了：回默认值，不报不存在）。
 *
 * <p>★ 查询理解那三个字段（第 12 周 · 0.4.0，{@code 10 §8.1}）也是<strong>恒有值</strong>的：
 * 契约里它们是可选字段（老前端不读也照常工作），但<strong>后端回包必须给默认值而不是省略</strong> —— 本项目已栽过"前端因缺字段回落到硬编码值而被悄悄带偏"（{@code 08
 * §4.6}）。可选的是<em>调用方</em>，不是回包。
 *
 * @param queryCount 查询理解条数：0 = 不做，1 = 只改写（{@code 10 §3.1.1}）
 * @param decomposeEnabled 是否拆子问题
 * @param maxSubQuestions 子问题数上限
 */
public record RetrievalParamsView(
    RetrievalMode mode,
    double threshold,
    int topN,
    NoRefStrategy noRefStrategy,
    int queryCount,
    boolean decomposeEnabled,
    int maxSubQuestions) {}
