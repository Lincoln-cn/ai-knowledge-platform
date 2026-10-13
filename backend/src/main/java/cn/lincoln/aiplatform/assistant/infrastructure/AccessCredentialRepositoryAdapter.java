package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import cn.lincoln.aiplatform.assistant.domain.AccessCredentialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的凭证仓储适配成领域的 {@link AccessCredentialRepository}。
 *
 * <p>★ 本适配器薄得几乎只有一行 —— 那正是它该有的样子：领域端口与 Spring Data 的差异在这里收口， 而"哪些行算存在"（软删口径）也在这里一次性说清。
 */
@Component
public class AccessCredentialRepositoryAdapter implements AccessCredentialRepository {

  private final JpaAccessCredentialRepository jpa;

  public AccessCredentialRepositoryAdapter(JpaAccessCredentialRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public AccessCredential save(AccessCredential credential) {
    return jpa.save(credential);
  }

  @Override
  public Optional<AccessCredential> findById(UUID id) {
    return jpa.findById(id).filter(credential -> !credential.isDeleted());
  }

  @Override
  public Optional<AccessCredential> findByToken(String token) {
    if (token == null || token.isBlank()) {
      // ★ 空 token 直接不查：库里那条 chk_as_credential_token_length 是兜底，而空串查库毫无意义
      return Optional.empty();
    }
    return jpa.findByTokenAndDeletedAtIsNull(token);
  }

  @Override
  public List<AccessCredential> findByAssistantId(UUID assistantId) {
    return jpa.findByAssistantIdAndDeletedAtIsNull(assistantId);
  }
}
