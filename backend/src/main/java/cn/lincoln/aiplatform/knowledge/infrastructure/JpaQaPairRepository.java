package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.QaPair;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的问答对仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code QaPairRepository} —— 双继承会让 {@code Pageable} / {@code
 * Specification} 这些框架类型漏进领域层（与 {@code JpaKnowledgeBaseRepository} / {@code JpaSegmentRepository}
 * 同一条判断）。
 */
public interface JpaQaPairRepository
    extends JpaRepository<QaPair, UUID>, JpaSpecificationExecutor<QaPair> {}
