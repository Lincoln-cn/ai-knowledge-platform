package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.RefreshToken;
import cn.lincoln.aiplatform.identity.domain.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data 实现，同时满足 {@link RefreshTokenRepository} 这个 domain 接口。 */
public interface JpaRefreshTokenRepository
    extends JpaRepository<RefreshToken, UUID>, RefreshTokenRepository {

  @Override
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * 批量撤销。
   *
   * <p>★ 用一条 {@code update} 而不是"查出来逐个改"：这条路径会在 <strong>侦测到重放</strong>时被调用 ——
   * 那时要的是"立刻、原子地把该用户所有会话掐断"， 逐行改会引入"改到一半"的窗口。
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :at "
          + "where t.userId = :userId and t.revokedAt is null")
  void revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("at") Instant at);
}
