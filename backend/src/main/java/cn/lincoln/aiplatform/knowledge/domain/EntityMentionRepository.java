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

  /** 一条待写入的关联。 */
  record Mention(UUID entityId, UUID kbId, UUID documentId, UUID segmentId) {}
}
