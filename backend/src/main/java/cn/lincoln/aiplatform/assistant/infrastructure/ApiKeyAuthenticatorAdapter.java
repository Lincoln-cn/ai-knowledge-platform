package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.security.ApiKeyAuthenticator;
import cn.lincoln.aiplatform.platform.security.ApiKeyIdentity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code X-API-Key} 的查表实现（{@code #195} 使用侧）。
 *
 * <p>★ 只认 {@code enabled = true} 的行；停用后旧的明文 Key 立即不能再通过鉴权，但记录保留（页面承诺"旧 Key 保留可单独停用"）。
 *
 * <p>★ 每次成功鉴权都回写 {@code last_used_at}：管理页要显示"最近使用"，而这个值只能在使用侧写。失败（未知/停用）<strong>不</strong>回写。
 *
 * <p>★ 应用已软删 ⇒ 返回空：否则删掉应用后，它的 Key 仍能换到一个"所有者身份"。
 */
@Component
public class ApiKeyAuthenticatorAdapter implements ApiKeyAuthenticator {

  private final JpaAssistantApiKeyRepository keys;
  private final AssistantRepository assistants;

  public ApiKeyAuthenticatorAdapter(
      JpaAssistantApiKeyRepository keys, AssistantRepository assistants) {
    this.keys = keys;
    this.assistants = assistants;
  }

  @Override
  @Transactional
  public Optional<ApiKeyIdentity> authenticate(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return Optional.empty();
    }
    return keys.findByKeyValueAndEnabledTrue(rawKey.strip())
        .flatMap(
            key -> {
              Optional<Assistant> assistant = assistants.findById(key.assistantId());
              if (assistant.isEmpty() || assistant.get().isDeleted()) {
                return Optional.empty();
              }
              key.markUsed(Instant.now());
              keys.save(key);
              Assistant found = assistant.get();
              return Optional.of(new ApiKeyIdentity(found.id(), found.ownerId()));
            });
  }
}
