package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.UUID;

/**
 * 实体与分段的关联端口（对应表 {@code kb_entity_mention}，迁移 {@code V95}）。
 *
 * <p>★ 为什么需要它：{@code kb_entity} 只记「这个库里有哪些实体」，不知道实体出现在哪一段。 而扩召回的整个动作是「问题里提到财务资产部 → 找出提到它的段 →
 * 把那些段放进候选」。 没有这一格，命中实体名之后拿不到段。
 *
 * <p>★ 与 {@code #51} 同族的理由：一条命中如果**不可溯源**，排错比没有更糟 —— 有了这张表，"为什么这一段被召回"就能回答"因为它提到了财务资产部"，而不是"模型觉得像"。
 *
 * <p>★ 写入幂等（{@code on conflict do nothing}）：文档重新解析会再抽一遍，同一实体在同一段里出现 几次是同一个事实，不该变成多行。
 */
public interface EntityMentionRepository {

  /**
   * 记一批「实体出现在哪一段」。
   *
   * @param mentions 待写入的关联
   * @return 真正新插入的条数
   */
  int insertAll(List<Mention> mentions);

  /** 某个实体出现在哪些段（扩召回要用）。 */
  List<UUID> segmentIdsOf(UUID entityId);

  /**
   * 每个实体被多少段提到过（键 = 实体 id）。
   *
   * <p>★ 为什么要在库里数、而不是界面拿实体列表后逐个问：实体页要按"提得多"排序， 逐个问就是 N+1 次往返。一次 {@code group by} 换掉 N 次查询。
   *
   * <p>★ 没有记录的实体<b>不出现在 map 里</b>（不是 0）—— 调用方要按 {@code getOrDefault(id, 0)} 读，
   * 这一点与"库里没有这个实体"是两件事，别混。
   */
  java.util.Map<UUID, Integer> countsOf(UUID kbId);

  /**
   * 每个实体分布在多少篇文档里（跨文档数）。
   *
   * <p>★ 为什么它比「被多少段提到」更能说明问题：一段里重复出现只记一次（唯一键是 {@code (entity_id,
   * segment_id)}），所以段数只回答"散得有多开"，而跨文档数回答 "这个名字是不是真的指一个东西" —— 出现在 6 篇不同法规里的「财政部」几乎不可能是
   * 抽取噪声，而只在一段里出现一次的「可以并处」几乎一定是。
   *
   * @param kbId 知识库 id（{@code null} 返回空 map）
   */
  java.util.Map<UUID, Integer> documentsOf(UUID kbId);

  /** 一条待写入的关联。 */
  record Mention(UUID entityId, UUID kbId, UUID documentId, UUID segmentId) {}
}
