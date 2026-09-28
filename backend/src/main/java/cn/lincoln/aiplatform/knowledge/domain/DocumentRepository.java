package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ 列表查询把「所属库 + 软删」拼进查询，{@code total} 与页内条数<strong>同口径</strong> —— 与 {@code
 * KnowledgeBaseRepository} 同一条纪律（{@code 08 §4.4.3}）。
 *
 * <p>★ 本批不按 dataScope 筛文档：文档的可见性<strong>继承库</strong>（库不可见 ⇒ 文档不可见）， 所以范围判定发生在「找到库」那一步，不在文档列表上再写一遍。
 */
public interface DocumentRepository {

  Optional<Document> findById(UUID id);

  Document save(Document document);

  /** 按库分页查文档（创建时间倒序）。★ 软删的不进列表。 */
  List<Document> findByKbId(UUID kbId, int offset, int limit);

  /** 与 {@link #findByKbId} 同条件的总数。★ 过滤之后的数，不能是表行数。 */
  long countByKbId(UUID kbId);
}
