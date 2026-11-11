package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * 关系扩召回的读端口：给定一个问题和知识库，返回「问题里的实体参与过关系陈述的那几段」。
 *
 * <p>★ 这是 {@code kb_relation} 的第一个消费方。在此之前那张表的读数是：表在、写入方在、真栈 {@code count(*) =
 * 2}，而<strong>检索链路一次都不读它</strong> —— 与 {@code §11.32.3} 第 1 条教训 （「读数为 0」与「功能没接」长得一样）同一个形状。
 *
 * <p>★ 与 {@link EntityRecallPort} 的区别（两者不是一件事，别合并）：
 *
 * <ul>
 *   <li>{@code EntityRecallPort} 找的是「实体<b>被提到</b>的段」（{@code kb_entity_mention}）——
 *       一段里出现「财务资产部」这三个字就算。
 *   <li>本端口找的是「实体<b>参与关系</b>的那一段」（{@code kb_relation.segment_id}）—— 段里必须有实际的关系陈述（「综合部隶属财务资产部」）。
 * </ul>
 *
 * <p>★★ 为什么返回的是<strong>关系的原文段</strong>，而不是「关联实体出现的段」：
 *
 * <p>「问题提到 A ⇒ 找 A 的关系 B ⇒ 找 B 出现的段」这条路会引入大量无关段落。A 与 B 有关系， 不代表 B 的每一段都与问题相关（「财务资产部负责预算，预算相关文档有 200
 * 段」）。而关系陈述所在 的那一段，本身就是一个完整的语义单元，几乎必然与「A 与谁有什么关系」这类问题相关。 ★★ 2026-11-11
 * 起它是<strong>两跳</strong>（{@code §11.36.1} 的 C 项）：先取问题实体的关系段， 再把它们的<strong>一跳邻居</strong>也带上查一次 ——
 * 问题问 A、答案写在「B 与 C 的关系」那一段时， 只查一跳永远找不到。邻居只扩一跳、不递归：三跳以上在这个规模上没有证据支持吃回本（{@code §11.27.5} 的触发条件）。
 *
 * <p>★ 失败语义（与 {@code EntityRecallPort} 同口径）：
 *
 * <ul>
 *   <li>问题里抽不出实体、或实体没有任何关系 ⇒ 返回空列表。这是<strong>正常结果</strong>，不是错误。
 *   <li>查库失败 ⇒ 抛 {@code RuntimeException}。**不许吞成空列表**。
 *   <li>顺序：按段的 {@code segment_id} 升序。名次交给 RRF，在这里预排等于给这一路偷偷加权重。
 * </ul>
 */
public interface RelationRecallPort {

  /**
   * 问题里的实体参与过关系陈述的那几段。
   *
   * @param kbId 知识库 id（{@code null} 返回空列表）
   * @param question 用户问题
   * @return 段 id（去重、按 id 升序）；没有命中返回空列表，不返回 {@code null}
   */
  List<UUID> segmentsWithRelations(UUID kbId, String question);
}
