package cn.lincoln.aiplatform.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆的仓储端口（{@code #296}）。
 *
 * <p>★ 每个方法都对应<strong>一条具体的查询或写入</strong>，没有"通用 find"： 记忆的读法分两种（页面上的"列出来"与对话中的"召回"），它们的条件完全不同，
 * 合成一个方法就会变成"一个方法服务两个场景、两边都不够用"。
 */
public interface MemoryRepository {

  /**
   * 保存（新写入或整条更新）。
   *
   * @param memory 记忆
   * @return 落库后的记忆
   */
  Memory save(Memory memory);

  /**
   * 按 id + 归属取一条。
   *
   * <p>★ 归属进条件而不是在 Service 里事后判断：事后判断要求"先取出来再比"， 一旦将来有人加了"先取出来做点别的"的代码，越权读就悄悄成立了。
   *
   * @param id 记忆 id
   * @param ownerId 归属用户
   * @return 记忆，不存在或不属于他给空
   */
  Optional<Memory> findById(UUID id, UUID ownerId);

  /**
   * 按去重键找同一条。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param kind 种类
   * @param dedupKey 去重键
   * @return 已存在的那条（用于"命中次数 +1"而不是再写一条）
   */
  Optional<Memory> findByDedupKey(
      UUID ownerId, MemoryLevel level, MemoryKind kind, String dedupKey);

  /**
   * 召回用的查询：按层与重要度粗筛。
   *
   * <p>★ 会话层必须带 {@code scopeId}；其余三层必须带 {@code null} —— 这个约束由实现里的断言守着， 因为"忘传
   * scopeId"与"故意跨会话"在调用方看起来一模一样。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param scopeId 会话 id（仅会话层）
   * @param minImportance 重要度下限
   * @param limit 条数上限
   * @return 候选记忆（按重要度、更新时间倒序）
   */
  List<Memory> findRecalls(
      UUID ownerId, MemoryLevel level, UUID scopeId, int minImportance, int limit);

  /**
   * 召回用的查询：<strong>一次取回四层</strong>。
   *
   * <p>★ 上一版是四次 {@link #findRecalls}（每层一次），其中三次查的是"必然空"的结果 （{@code CONVERSATION} 层此前没有写入方、{@code
   * PERSONA} 层没有自动来源）—— 因为一个 bug 而多付三次往返，是最难发现的浪费：它不报错、也不慢到能被注意到，只是每轮对话都多花几十毫秒。
   *
   * <p>★ 会话层的隔离条件仍然是硬的：{@code m.level = 'CONVERSATION' and m.scopeId = :conversationId} 与其他三层的
   * {@code or} 并列出现，<strong>不会</strong>因为合并成一条语句而丢掉。
   *
   * @param ownerId 归属用户
   * @param conversationId 当前会话（会话层只取它）
   * @param minImportance 重要度下限
   * @param limit 条数上限
   * @return 候选记忆（重要度、更新时间倒序）
   */
  List<Memory> findRecallsAcrossLevels(
      UUID ownerId, UUID conversationId, int minImportance, int limit);

  /**
   * 页面列表：某人的全部记忆（含已被取代的，前端可以折叠显示）。
   *
   * @param ownerId 归属用户
   * @param limit 条数上限
   * @return 记忆列表（按更新时间倒序）
   */
  List<Memory> findAllForOwner(UUID ownerId, int limit);

  /**
   * 软删。
   *
   * @param id 记忆 id
   * @param now 当前时间
   */
  void softDelete(UUID id, Instant now);

  /**
   * 某层的条数（页面上的"共 N 条"与做梦的输入规模）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @return 条数（不含已删、已被取代的）
   */
  long countByLevel(UUID ownerId, MemoryLevel level);

  /**
   * 找可以遗忘的（做梦的"遗忘"动作）。
   *
   * <p>★ 条件里必须有 {@code hitCount = 0}：<strong>从没被用过</strong>的记忆才是噪音； 只按时间判会把"一年前立的规矩"删掉，而它恰恰最该留着。
   *
   * @param before 这个时间之前更新的才算
   * @param maxImportance 重要度上限（低于它才考虑遗忘）
   * @param limit 一次最多处理几条
   * @return 候选记忆
   */
  List<Memory> findForgettable(Instant before, int maxImportance, int limit);

  /**
   * 同一 owner + level + kind 下、去重键相同的一批（做梦的"去重合并"）。
   *
   * @param ownerId 归属用户
   * @param level 层级
   * @param kind 种类
   * @param limit 最多取几条
   * @return 候选记忆
   */
  List<Memory> findMergeCandidates(UUID ownerId, MemoryLevel level, MemoryKind kind, int limit);

  /**
   * 取"某一层有活记忆"的归属人。
   *
   * <p>做梦任务按人重算画像时要知道"给谁算"，而它手上没有用户表 —— 从记忆反查是最短的路径。
   *
   * @param level 层级
   * @param limit 人数上限
   * @return 归属人
   */
  List<UUID> findOwnersWithLevel(MemoryLevel level, int limit);
}
