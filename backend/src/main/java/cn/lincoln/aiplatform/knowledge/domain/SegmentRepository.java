package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 分段仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ 分段是子实体（{@code 02 §5.1}）：没有软删列，随文档软删级联。 仓储的查询条件里<strong>不出现 {@code deletedAt}</strong> ——
 * 那是聚合根的事。
 *
 * <p>★ 列表查询把数据范围（{@link ScopeFilter}）<strong>拼进查询</strong>（{@code 08 §4.4.3} 纪律 1）， 不是查完再筛 —— 后者会让
 * {@code total} 与页内条数对不上。
 */
public interface SegmentRepository {

  Optional<Segment> findById(UUID id);

  Segment save(Segment segment);

  /** 批量保存（入库即分块时一次写入整篇的分段）。 */
  List<Segment> saveAll(List<Segment> segments);

  /** 按文档有序查出（{@code ordinal} 升序）。★ 入库比对与文档级重分块都走这里。 */
  List<Segment> findByDocumentId(UUID documentId);

  /** 按文档删除（重分块前先清旧的 —— 幂等重入的实现方式）。 */
  void deleteByDocumentId(UUID documentId);

  /** 删除单条（契约 {@code deleteSegment}）。★ 子实体没有软删列 ⇒ 真删。 */
  void delete(Segment segment);

  /**
   * 分页查询（数据范围拼进查询）。
   *
   * @param scope 当前请求的可见范围 —— 不可见库的分段不得返回
   * @param documentId 限定文档；null 表示不筛
   * @param kbId 限定知识库；null 表示不筛
   * @param onlyOversized 只看超长分段（SEG-I1）；null 表示不筛
   */
  List<Segment> search(
      ScopeFilter scope, UUID documentId, UUID kbId, Boolean onlyOversized, int offset, int limit);

  /** 与 {@link #search} 同条件的总数。★ 过滤之后的数，不能是表行数。 */
  long countSearch(ScopeFilter scope, UUID documentId, UUID kbId, Boolean onlyOversized);
}
