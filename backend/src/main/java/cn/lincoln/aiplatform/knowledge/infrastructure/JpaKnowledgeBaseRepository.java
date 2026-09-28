package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的知识库仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code KnowledgeBaseRepository} —— 双继承会让 {@code Pageable} /
 * {@code Specification} 这些框架类型漏进领域层（与 {@code JpaUserRepository} / {@code JpaModelRepository}
 * 同一条判断）。
 *
 * <p>★ 列表查询用 {@link JpaSpecificationExecutor}：空条件就不拼谓词， 且 dataScope 范围谓词能和筛选条件合进同一条 SQL（{@code 08
 * §4.4.3} 纪律 1）。
 */
public interface JpaKnowledgeBaseRepository
    extends JpaRepository<KnowledgeBase, UUID>, JpaSpecificationExecutor<KnowledgeBase> {}
