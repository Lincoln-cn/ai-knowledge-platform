package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的文档仓储。
 *
 * <p>★ 不同时继承 domain 的 {@code DocumentRepository} —— 双继承会让 {@code Pageable} 漏进领域层（与 {@code
 * JpaKnowledgeBaseRepository} 同一条判断）。
 */
public interface JpaDocumentRepository
    extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {}
