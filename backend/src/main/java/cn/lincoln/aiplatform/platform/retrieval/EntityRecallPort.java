package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * 实体扩召回的读端口：给定一个问题和知识库，返回「问题里提到的实体所出现过的段」。
 *
 * <p>★ 这是 0.6.2 的第一层。它要回答的问题只有一句话： <strong>问题里提到的机构/文档，在哪几段里被提到？</strong> 这些段随后作为**一路候选**参与 RRF
 * （检索链路本来就有"多路融合"的形状，扩召回只是多一路，不是新算法）。
 *
 * <p>★ 2026-11-11 从 knowledge.domain 搬到这里：retrieval 模块要用它，而 knowledge 没有暴露它 ⇒ ModuleBoundaryTest 报
 * depends-on-non-exposed-type。搬端口而不是给 knowledge 开 NamedInterface —— 后者的读数是闸门绿了，前者才是依赖方向真的合法。同 #293
 * 的处置。
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

  /**
   * 一次实体扩召回的完整读数：段 + 明细（命中测试要看「问句里认出了什么」）。
   *
   * @param segments 参与 RRF 的段 —— 与 {@link #segmentsMentionedIn} 同口径、同顺序
   * @param entities 这次实际用到的实体；一个都没认出来时是空列表
   */
  record Recall(List<UUID> segments, List<Entity> entities) {}

  /**
   * 问题里认出来的一个实体。
   *
   * @param mentions 这个实体在这个库里被多少段提到（0 表示实体表里有、但没有提及记录）
   */
  record Entity(String name, String type, int mentions) {}

  /**
   * 带明细的那一版。
   *
   * <p>★ 为什么不让调用方自己再跑一遍 {@code recognizer.recognize}：那是**另一批**实体（识别器有截断、 有别名归一），于是界面会出现「显示 3
   * 个实体、实际只用了 2 个」这种新假象。判据与使用必须是同一次调用。
   *
   * <p>★ 默认实现返回**空明细**而不是抛异常：既有实现（含测试里的假实现）不必为了这个读数改动。 那样命中测试会显示「没有明细」—— 这是诚实的降级，不是静默失效，因为 {@code
   * segments} 仍然是真实召回结果。
   */
  default Recall recall(UUID kbId, String question) {
    return new Recall(segmentsMentionedIn(kbId, question), List.of());
  }
}
