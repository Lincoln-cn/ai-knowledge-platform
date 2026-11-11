package cn.lincoln.aiplatform.knowledge.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 实体关系的读写端口。
 *
 * <p>★ 写口（{@code insertAll}）与读口（{@code segmentIdsOf}）是<strong>不同批次</strong>加进来的， 这个顺序本身是刻意的：
 *
 * <ul>
 *   <li>0.6.4 只加写口 —— 那批的目标是"关系抽到了要落地"，不是"关系能被查询"。先加一个没人调用的 {@code listOf}，
 *       只会让"关系已经可查"这件事看起来像是完成了（{@code §11.32.3} 第 1 条教训）。
 *   <li>读口等到 2026-11-11 才加 —— 它的消费方是 {@code RelationRecallPort}（检索链路多一路候选）。 两者同一批进，口径同 {@code
 *       WorkflowRecordRepository} 的 javadoc。
 * </ul>
 *
 * <p>★ 读口只暴露 {@code segmentIdsOf}（段 id），<strong>不暴露关系的完整行</strong>： 检索侧要的语义是"哪几段有与这个问题里的实体相关的
 * 关系陈述"，不是"关系表长什么样"。 把表结构暴露给检索侧，等于每次改表都要改检索（同 {@code EntityRecallPort} 的理由）。
 *
 * <p>本接口不做去重、不做消歧、不做权限判断 —— 那些在调用方或更上游。
 */
public interface EntityRelationRepository {

  /**
   * 一批关系。重复的（同一 from/to/type）由数据库的唯一约束吞掉，这里不做内存去重： 同一段里「综合部隶属财务资产部」出现两次是同一个事实，唯一键正好表达这一点。
   *
   * @return 实际写入的行数（被唯一约束吞掉的不计入）
   */
  int insertAll(UUID kbId, List<Candidate> relations);

  /**
   * 这些实体参与过关系陈述的那几段。
   *
   * <p>★ 返回的是<strong>关系的原文段</strong>，不是「关联实体出现的段」。这两种语义的差别见 {@code
   * platform.retrieval.RelationRecallPort} 的 javadoc：后者会引入大量无关段落。
   *
   * <p>★ 失败语义：查库失败抛异常，<strong>不吞成空列表</strong>； 没有关系、或 {@code segmentId} 为空的行（存量回填那批没有段落）直接跳过，
   * 返回空列表是<strong>正常结果</strong>。
   *
   * @param kbId 知识库 id（{@code null} 返回空列表）
   * @param entityIds 实体 id（空集合返回空列表）
   * @return 段 id（去重、升序）；没有命中返回空列表，不返回 {@code null}
   */
  List<UUID> segmentIdsOf(UUID kbId, Collection<UUID> entityIds);

  /**
   * 与给定实体有关系的其他实体 id（一跳邻居）。
   *
   * <p>★ 为什么需要它：{@link #segmentIdsOf} 只回答「<b>这些</b>实体参与过的关系段」。问题问 A、而答案写在「B 与 C 的关系」那一段时，一跳永远找不到 ——
   * 这正是 `§11.36.1` 的 C 项要补的那一跳。
   *
   * <p>★ 只返回<b>另一端</b>的 id，不返回关系类型：这一版要的是「还能往哪儿走」，类型留给排序时的语义判断（再说 四类关系里三类都表示「谁管谁」，对召回方向没有区分度）。
   *
   * <p>★ 两端都算：实体在关系的 {@code from_entity} 还是 {@code to_entity}，对「还有哪些相关实体」没有区别。
   *
   * <p>★ 失败语义同 {@link #segmentIdsOf}：查库失败抛异常，<strong>不吞成空列表</strong>。
   *
   * @param kbId 知识库 id（{@code null} 返回空列表）
   * @param entityIds 起点实体（空集合返回空列表）
   * @return 去重后的邻居 id；没有邻居返回空列表，不返回 {@code null}
   */
  List<UUID> neighborEntityIds(UUID kbId, Collection<UUID> entityIds);

  /**
   * 待写入的一条关系。
   *
   * <p>{@code fromEntity} / {@code toEntity} 是**已经入库**的实体 id，不是名字 ——
   * 两端必须是实体，否则这条边在图里没有端点。调用方负责把名字解析成 id， 解析不出来的就不该走到这里（那说明这条关系的一半不在库里）。
   *
   * <p>{@code documentId} / {@code segmentId} 是取证位置，可以为 null（存量回填那批没有段落时），
   * 但为空就说明这条边**无法核对**，调用方应当尽量给出。
   */
  record Candidate(
      UUID fromEntity,
      UUID toEntity,
      String type,
      UUID documentId,
      UUID segmentId,
      double confidence) {}
}
