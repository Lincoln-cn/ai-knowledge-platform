package cn.lincoln.aiplatform.platform.retrieval;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 问答对召回（检索的<strong>第五路</strong>）—— SPI 在 {@code platform}、实现在 {@code knowledge}。
 *
 * <h3>★ 它为什么要单开一个端口，而不是并进 {@link SegmentSearchPort}</h3>
 *
 * <p>两者的<strong>返回形状不同</strong>：分段检索的每个候选都是"一段"，可以进 RRF 按排名融合； 问答对<strong>可以没有出处</strong>（ {@code
 * segment_id} 为空的手工标定）—— 没有段就没有"第几名"， 硬塞进 {@code List<Scored>} 是在用 {@code null} 冒充一个有排名的候选。所以这里返回的是
 * <strong>带相似度与模式标志的实体</strong>，由 pipeline 决定怎么用：
 *
 * <ul>
 *   <li>有出处（{@code segmentId != null}）⇒ 那一段作为候选进融合，且该路的 id 用 {@code qa}（渠道可见）；
 *   <li>没有出处 ⇒ 进"答案参考"（{@code qaAnswers}），<strong>不进引用栏</strong>（引用必须是能点开核对的原文）；
 *   <li>{@code directAnswer} ⇒ 只有它命中且分数过阈值时才<strong>原样作答</strong>，跳过融合与生成。
 * </ul>
 *
 * <h3>★★ 密级上限<strong>不在入参里</strong></h3>
 *
 * <p>{@link Query} 只带"问题 + 域名 + 阈值 + 上限条数"。当前用户的密级上限由<strong>实现自己</strong>从 {@code AccessScope} 取（与
 * {@code JdbcSegmentSearchQuery#clearanceRank} 同口径）。
 *
 * <p>★ 理由不是"少传一个参数"，而是：<strong>一个能被调用方漏传的越权闸门，等于没有闸门</strong>。 让它由调用方算再传进来，就有"某条新路径忘了传 ⇒ 传 {@code
 * null} ⇒ 不过滤"的形状，而那是一个静默越权（{@code 13 §6}）。 未认证时上限为 {@code null} ⇒ 谓词恒假 ⇒ 一条都不返回（fail-closed）。
 */
public interface QaRecallPort {

  /**
   * 按问句召回问答对。
   *
   * @param query 查询条件
   * @return 命中（按分数降序）；没有命中 / 词元为空 / 域为空 一律返回空列表，<strong>不返回 null</strong>
   */
  List<Match> recall(Query query);

  /**
   * 查询条件。
   *
   * @param question 问句（空白 ⇒ 调用方不该调它；实现遇到空白返回空列表）
   * @param kbIds 本次可检索的知识库（★ <strong>已经过可见性判定</strong>，见 {@link SegmentSearchPort#visibleKbIds}）——
   *     问答对只挂在库上，没有会话域那一支
   * @param minScore 相似度下限（0..1）。★ 这是<strong>相似度</strong>不是"命中条数"：低于它的即使词元有交集也不算命中
   * @param limit 最多返回多少条（实现要给一个内部上限，防止词元多的问句把候选撑爆）
   */
  record Query(String question, Collection<UUID> kbIds, double minScore, int limit) {}

  /**
   * 一条命中。
   *
   * @param id 问答对 id
   * @param question 标定的问法（★ 回答时要说清"这是按哪句话匹配上的"，不能只给答案）
   * @param answer 标定的答案
   * @param segmentId 出处分段；{@code null} = 手工标定、无出处
   * @param directAnswer 是否"命中即直答"（见 {@code kb_qa_pair.direct_answer}）
   * @param classification 密级（引用栏要显示；★ 它 ≤ 调用方的上限，由实现保证）
   * @param score 相似度（0..1）
   */
  record Match(
      UUID id,
      String question,
      String answer,
      UUID segmentId,
      boolean directAnswer,
      ClassificationLevel classification,
      double score) {}
}
