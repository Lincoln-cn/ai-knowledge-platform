package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.UUID;

/**
 * 实体扩召回的读端口：给定一个问题和知识库，返回「问题里提到的实体所出现过的段」。
 *
 * <p>★ 这是 0.6.2 的第一层。它要回答的问题只有一句话： <strong>问题里提到的机构/文档，在哪几段里被提到？</strong> 这些段随后作为**一路候选**参与 RRF
 * （检索链路本来就有"多路融合"的形状，扩召回只是多一路，不是新算法）。
 *
 * <p>★ 为什么不是直接查表：{@code kb_entity} / {@code kb_entity_mention} 是形状，
 * 而调用方要的是语义（"提到过"）。把表结构暴露给检索侧，等于每次改表都要改检索。
 *
 * <p>★ 失败语义（三条，都是刻意的）：
 *
 * <ul>
 *   <li>抽不出实体 ⇒ 返回空列表。这是**正常结果**（问题里本来就没有机构名），不是错误。
 *   <li>查库失败 ⇒ 抛 {@code RuntimeException}。**不许吞成空列表** —— "没有命中"与"没查成"读数一样，
 *       而后者会让扩召回静默失效，正是本项目反复记账的形状。
 *   <li>顺序：按段的确定性顺序（{@code segment_id} 升序）返回。名次由 RRF 决定，不在这里预排 —— 在这里排序会让"一路候选"偷偷带上权重。
 * </ul>
 */
public interface EntityRecallPort {

  /**
   * 问题里提到的实体所出现过的段。
   *
   * @param kbId 知识库 id（{@code null} 返回空列表）
   * @param question 用户问题
   * @return 段 id（去重、按 id 升序）；没有命中返回空列表，不返回 {@code null}
   */
  List<UUID> segmentsMentionedIn(UUID kbId, String question);
}
