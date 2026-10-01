package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的文档仓储。
 *
 * <p>★ 不同时继承 domain 的 {@code DocumentRepository} —— 双继承会让 {@code Pageable} 漏进领域层（与 {@code
 * JpaKnowledgeBaseRepository} 同一条判断）。
 */
public interface JpaDocumentRepository
    extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

  /**
   * 按状态分组计数（库级进度聚合用，{@code 03 §3.4}）。
   *
   * <p>★ 在库层分组，而不是"把文档都取回来在内存里数"：后者会在分页/上限处**静默算错** （页大小 100、库里有 340 篇时，进度条永远显示 100/340 那种）。
   *
   * <p>★ 软删文档不计入 —— 它们对用户已经不存在，算进去会让进度条永远到不了头。
   */
  @Query(
      "select d.status, count(d) from Document d"
          + " where d.kbId = :kbId and d.deletedAt is null"
          + " group by d.status")
  List<Object[]> countGroupedByStatus(@Param("kbId") UUID kbId);
}
