package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 访问凭证的仓储（领域端口）—— 实现在 {@code assistant.infrastructure}。
 *
 * <p>★ 与 {@link AssistantRepository} 同一条口径：领域用一个<strong>窄接口</strong>表达需要什么， Spring Data / JPA
 * 的类型留在适配器里。
 */
public interface AccessCredentialRepository {

  AccessCredential save(AccessCredential credential);

  Optional<AccessCredential> findById(UUID id);

  /**
   * ★ 按 token 解析 —— 公开面的<strong>唯一</strong>检索路径。
   *
   * <p>软删过的凭证<strong>查不到</strong>（与 {@code as_assistant} 同口径）：一条被软删的链接 不该因为"有人还留着旧的 URL"而继续可解析。
   */
  Optional<AccessCredential> findByToken(String token);

  /** 凭证管理页按应用列出（契约 {@code listCredentials}）。含已撤销的 —— 契约要下发 {@code revokedAt}。 */
  List<AccessCredential> findByAssistantId(UUID assistantId);
}
