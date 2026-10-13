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
 * <h3>★ 它现在唯一的消费者是服务层的 {@code list}</h3>
 *
 * <p>{@code #78} 的边界拍的是「<strong>不加 HTTP 端点、不改契约</strong>」：契约闸门 {@code ContractDriftTest}
 * 是<strong>双向</strong>的 —— "后端有、契约没有 ⇒ 直接失败"， 而登记完没有任何页面消费它会变成"零消费"的三缺一（{@code #79} 扫的正是这个）。
 * 所以本批的"最小管理入口" = <strong>Java 服务层的方法</strong>，这个视图是它的返回形状。
 *
 * <p>★ 因此这里<strong>故意不</strong>对齐任何前端契约类型（没有 {@code frontend/src/api/**} 的对应物）：
 * 等真有页面消费它时再定契约，那时才知道要下发哪几个字段。
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
        pair.classification(),
        pair.classificationRank(),
        pair.origin(),
        pair.sourceRef(),
        pair.hitCount());
  }
}
