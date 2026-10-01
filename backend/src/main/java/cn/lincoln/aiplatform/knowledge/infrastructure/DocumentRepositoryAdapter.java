package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的文档仓储适配成 domain 的 {@link DocumentRepository}。
 *
 * <p>★ 存在的理由与 {@code KnowledgeBaseRepositoryAdapter} 相同：框架类型不进领域层。
 *
 * <p>★ 「软删过滤」是恒定谓词；「按库过滤」是本批列表的主谓词。 total 与列表共用同一份谓词（{@code 08 §4.4.3} 纪律 1）。
 */
@Component
public class DocumentRepositoryAdapter implements DocumentRepository {

  private final JpaDocumentRepository jpa;

  public DocumentRepositoryAdapter(JpaDocumentRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Document> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Document save(Document document) {
    return jpa.save(document);
  }

  @Override
  public List<Document> findByKbId(UUID kbId, int offset, int limit) {
    // 领域给 offset/limit；Spring Data 要「第几页、每页几条」。limit 恒 > 0（调用方已归一化）
    return jpa.findAll(filter(kbId), PageRequest.of(offset / limit, limit, sort())).getContent();
  }

  @Override
  public long countByKbId(UUID kbId) {
    // ★ 与 findByKbId 共用同一份谓词 —— 两处各拼一遍必然漂移
    return jpa.count(filter(kbId));
  }

  @Override
  public Map<DocumentStatus, Long> countByStatus(UUID kbId) {
    // ★ 分组计数在库里做（见 JpaDocumentRepository 的注释）：取回来再数会在分页上限处静默算错
    Map<DocumentStatus, Long> counts = new EnumMap<>(DocumentStatus.class);
    for (Object[] row : jpa.countGroupedByStatus(kbId)) {
      counts.put((DocumentStatus) row[0], ((Number) row[1]).longValue());
    }
    return counts;
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Sort sort() {
    return Sort.by(Sort.Direction.DESC, "createdAt");
  }

  /** 软删 + 所属库。★ 列表与计数的唯一谓词来源。 */
  private static Specification<Document> filter(UUID kbId) {
    return (root, query, cb) ->
        cb.and(cb.isNull(root.get("deletedAt")), cb.equal(root.get("kbId"), kbId));
  }
}
