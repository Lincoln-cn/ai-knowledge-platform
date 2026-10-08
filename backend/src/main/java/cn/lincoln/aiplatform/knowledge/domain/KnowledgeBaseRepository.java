package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 知识库仓储（接口定义在 {@code domain}，实现放 {@code infrastructure}）。
 *
 * <p>★ 列表查询把 {@link ScopeFilter} <strong>拼进查询</strong>（{@code 08 §4.4.3} 纪律 1），不是查完再筛 —— 后者会让
 * {@code total} 与页内条数对不上。
 *
 * <p>★ {@link #search} 与 {@link #countSearch} 必须共用同一份谓词。 两处各拼一遍必然漂移，症状是「列表 3 条、total 却是
 * 10」这种没人能解释的分页。
 */
public interface KnowledgeBaseRepository {

  Optional<KnowledgeBase> findById(UUID id);

  /** 按幂等键 + 归属人查既有库（幂等重放）。 */
  Optional<KnowledgeBase> findByClientRequestIdAndOwnerId(String clientRequestId, UUID ownerId);

  KnowledgeBase save(KnowledgeBase base);

  /**
   * 分页查询（按创建时间倒序）。
   *
   * @param scope 当前请求的可见范围 —— <strong>必须拼进查询</strong>
   * @param category 分类名；null 表示不筛
   * @param enabled 启用位；null 表示不筛
   * @param mine 只看自己名下的；false 表示按 {@code scope} 给全部可见的
   * @param keyword 名称/描述模糊匹配；null 或空白表示不筛
   */
  /**
   * 一个知识库的**实时计数**（{@code #35}）。
   *
   * <p>★ 为什么不读 {@code kb_knowledge_base} 上那四个冗余列：**它们从来没被写过** —— 列表页的卡片因此永远显示
   * 0，而没有任何地方报错。修法是"读数来自库"（本项目对进度、检索已经是这个口径）： 一条聚合 SQL 出四个值，读的时候算，**不可能漂**。
   *
   * <p>★ 口径：只算**未软删**文档，以及这些文档名下的分段（文档软删后，它的分段不该再算进卡片）。
   *
   * @param documentCount 未软删文档数
   * @param segmentCount 这些文档名下的分段数
   * @param charCount 这些分段的字数之和
   * @param hasVectorizedContent 是否存在已向量化的分段（决定"向量模型是否锁定"）
   */
  Counts countsFor(UUID kbId);

  /** 实时计数（见 {@link #countsFor}）。 */
  record Counts(
      int documentCount, int segmentCount, long charCount, boolean hasVectorizedContent) {}

  /** 按可见范围分页列出知识库。★ 范围谓词在查询里（{@code 08 §4.4.3} 纪律 1），不是取回来再筛。 */
  List<KnowledgeBase> search(
      ScopeFilter scope,
      String category,
      Boolean enabled,
      boolean mine,
      String keyword,
      int offset,
      int limit);

  /** 与 {@link #search} 同条件的<strong>总数</strong>。★ 过滤之后的数，不能是表行数。 */
  long countSearch(
      ScopeFilter scope, String category, Boolean enabled, boolean mine, String keyword);

  /** 可见范围内的分类计数（{@code GET /knowledge-bases/categories} 用）。 */
  List<Object[]> countByCategory(ScopeFilter scope);
}
