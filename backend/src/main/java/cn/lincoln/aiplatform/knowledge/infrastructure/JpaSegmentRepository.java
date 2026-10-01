package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Segment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的分段仓储。
 *
 * <p>★ 不同时继承 domain 的 {@code SegmentRepository} —— 双继承会让 {@code Pageable} 漏进领域层（与 {@code
 * JpaDocumentRepository} 同一条判断）。
 */
public interface JpaSegmentRepository
    extends JpaRepository<Segment, UUID>, JpaSpecificationExecutor<Segment> {}
