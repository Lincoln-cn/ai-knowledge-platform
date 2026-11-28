package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.QaPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;

/**
 * 问答对视图（Issue {@code #78} 数据层留缝）。
 *
 * <p>★ 视图显式列出每个出网字段，不直接序列化实体 —— 实体上将来会长出内部状态，漏一次就是数据泄漏 （与 {@code SegmentView} / {@code
 * DocumentView} 同口径）。
 *
 * <h3>★ 它的消费者：{@code QaPairController} 的五个端点 + 知识库页的「问题」面板</h3>
 *
 * <p>★ 这里曾经写着「{@code #78} 的边界是不加 HTTP 端点、不改契约，所以故意不对齐任何前端契约类型」—— <strong>那句话已经过期了</strong>（0.9.9
 * 问答对接进检索之后，管理入口与前端面板都有了）。 保留这段说明是因为它记着一件事：<strong>视图的字段集要随着"真的有人在用"才长出来</strong>，
 * 而不是按"将来可能要用"提前铺开。{@code directAnswer} 就是按这条口径加的 —— 它现在的消费者是直答那条路。
 *
 * <p>★ {@code classificationRank} 也放进来了：它是检索谓词要用的那一列，是本表"能被 pre-filter"的 证据。测试拿它断言「库里的 rank 与 Java
 * 的 ordinal 同序」—— 而这条读数不通过界面也能核对。
 */
public record QaPairView(
    String id,
    String kbId,
    String question,
    String answer,
    String segmentId,
    boolean directAnswer,
    ClassificationLevel classification,
    Short classificationRank,
    QaPairOrigin origin,
    String sourceRef,
    long hitCount) {

  public static QaPairView from(QaPair pair) {
    return new QaPairView(
        pair.id().toString(),
        pair.kbId().toString(),
        pair.question(),
        pair.answer(),
        pair.segmentId() == null ? null : pair.segmentId().toString(),
        pair.directAnswer(),
        pair.classification(),
        pair.classificationRank(),
        pair.origin(),
        pair.sourceRef(),
        pair.hitCount());
  }
}
