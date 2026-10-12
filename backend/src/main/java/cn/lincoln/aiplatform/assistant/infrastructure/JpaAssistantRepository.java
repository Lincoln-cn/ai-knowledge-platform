package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的应用仓储。
 *
 * <p>★ 纯 Spring Data 接口，<strong>不同时继承</strong>领域的 {@code AssistantRepository} —— 双继承会让 {@code
 * Pageable} / {@code Specification} 漏进领域层（与 {@code JpaKnowledgeBaseRepository} / {@code
 * JpaModelRepository} 同一条判断）。
 *
 * <p>列表查询将用 {@link JpaSpecificationExecutor}（范围谓词与筛选合进同一条 SQL，{@code 08 §4.4.3} 纪律 1）—— 本批先只用到
 * {@code findById} / {@code save} 与一次条件查询（幂等键）。
 */
public interface JpaAssistantRepository
    extends JpaRepository<Assistant, UUID>, JpaSpecificationExecutor<Assistant> {}
