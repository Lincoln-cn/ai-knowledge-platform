package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 的访问凭证仓储。
 *
 * <p>★ 与 {@link JpaAssistantRepository} 同一条口径：<strong>不同时继承</strong>领域的 {@code
 * AccessCredentialRepository}（双继承会让 Spring Data 的类型漏进领域层）。
 *
 * <p>★ 三个查询方法都带 {@code deletedAtIsNull}：软删过的凭证<strong>查不到</strong> —— 一条被软删的链接不该因为"有人还留着旧
 * URL"而继续可解析。
 */
public interface JpaAccessCredentialRepository extends JpaRepository<AccessCredential, UUID> {

  Optional<AccessCredential> findByTokenAndDeletedAtIsNull(String token);

  List<AccessCredential> findByAssistantIdAndDeletedAtIsNull(UUID assistantId);
}
