package cn.lincoln.aiplatform.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 刷新令牌仓储。接口在 {@code domain}，实现由 {@code infrastructure} 提供。 */
public interface RefreshTokenRepository {

  /** 按 sha256 哈希找 —— 明文从不落库，所以也只能按哈希查。 */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  RefreshToken save(RefreshToken token);

  /**
   * 撤销某用户所有仍有效的令牌。
   *
   * <p>用于三处：登出（"在这个设备上退出"以外的语义先不做）、**改密**（旧会话全部失效）、 以及**侦测到重放**时撤销整条链。
   */
  void revokeAllActiveByUserId(UUID userId, Instant at);
}
