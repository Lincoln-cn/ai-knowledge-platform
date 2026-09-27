package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.RefreshTokenRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记录"必须活下来"的安全状态变更。
 *
 * <p>★ <strong>这个类存在的唯一理由是事务回滚</strong>，值得讲清楚：
 *
 * <p>{@link AuthService#login} 与 {@link AuthService#refresh} 都是事务方法，而它们在记录完
 * 安全状态之后<strong>要抛异常</strong>（登录失败要回 401、重放要回 401）。 在同一个事务里，"写状态 + 抛异常"是一对矛盾 —— 异常会把那次写入一起回滚掉。结果是：
 *
 * <ul>
 *   <li>登录失败计数<strong>永远是 0</strong> ⇒ 账号永远锁不上（等保那条要求形同虚设）
 *   <li>侦测到重放后"撤销该用户全部会话"<strong>被回滚</strong> ⇒ 攻击者手里那个令牌仍然有效
 * </ul>
 *
 * <p>两处都极难通过测试发现，只要测试没有把"失败三次 ⇒ 锁定"和"重放 ⇒ 全部失效"这两条 端到端串起来跑（最初确实没有，直到这两条断言双双失败）。
 *
 * <p>所以这里的方法一律用 {@link Propagation#REQUIRES_NEW}：它开启一个独立事务， 提交之后才返回，此后上层无论抛什么异常都影响不到它。
 */
@Service
public class AuthSecurityRecorder {

  private final UserRepository users;
  private final RefreshTokenRepository refreshTokens;

  public AuthSecurityRecorder(UserRepository users, RefreshTokenRepository refreshTokens) {
    this.users = users;
    this.refreshTokens = refreshTokens;
  }

  /**
   * 记一次登录失败，返回**更新后**的用户（调用方据此判断是否已触发锁定）。
   *
   * <p>★ 独立事务：见类注释。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public User recordLoginFailure(UUID userId, Instant now, int maxAttempts, Duration lockDuration) {
    User user =
        users.findById(userId).orElseThrow(() -> new NoSuchElementException("用户不存在：" + userId));
    user.recordFailedLogin(now, maxAttempts, lockDuration);
    return users.save(user);
  }

  /**
   * 侦测到刷新令牌重放时，撤销该用户的全部有效会话。
   *
   * <p>★ 独立事务：这个调用后面紧跟着一个 {@code throw}，共用一个事务等于没撤销。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revokeAllSessionsAfterReplay(UUID userId, Instant now) {
    refreshTokens.revokeAllActiveByUserId(userId, now);
  }
}
