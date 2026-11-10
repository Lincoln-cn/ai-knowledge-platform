package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实体别名表（0.6.3）：同一个真实组织在文档里会有多种叫法。
 *
 * <p>★ 为什么要有它：{@code kb_entity} 是按**名字**去重的（{@code where not
 * exists}），所以「财务部」与「财务资产部」在库里是两行。检索时需要知道它们是同一个， 否则问「财务部负责什么」只能召回写了「财务部」的那几段 ——
 * 而制度文档里更常见的写法是「财务资产部」。
 *
 * <p>★ **合并采用别名表，不改原行**：「财务部」那一行仍然在，只是多了一行别名指向「财务资产部」。理由与 {@code ConversationShareService}
 * 的「撤销不删行」同族 —— 已经落库的实体 id 被引用在 {@code kb_entity_mention} 与 {@code kb_relation} 里，改 id 就是改历史。
 *
 * <p>★ **别名不参与实体抽取**，只参与召回：抽取阶段按名字查出真实实体，召回阶段把问题里出现的别名映射回规范名。两件事分开，否则一次错误的合并会同时污染实体表与关系表。
 */
public interface EntityAliasRepository {

  /**
   * 把这个库里出现的别名映射回规范实体 id。
   *
   * @param kbId 库标识
   * @param aliases 待解析的别名（通常是问题里抽到的实体名）
   * @return 别名 → 规范实体 id；查不到的别名不会出现在结果里（不是 null）
   */
  Map<String, UUID> canonicalIds(UUID kbId, List<String> aliases);

  /**
   * 一条别名。
   *
   * @param entityId 规范实体 id
   * @param aliasName 别名
   * @param source 来源：{@code IMPORT} / {@code RULE} / {@code MANUAL}
   * @param confidence 置信度（{@code MANUAL} 为 1.0，规则抽取的低于它）
   */
  record Alias(UUID entityId, String aliasName, String source, double confidence) {}
}
