package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.Permissions;
import cn.lincoln.aiplatform.identity.domain.RefreshToken;
import cn.lincoln.aiplatform.identity.domain.RefreshTokenRepository;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import cn.lincoln.aiplatform.identity.infrastructure.JwtTokenService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证：登录、刷新（轮换 + 重放检测）、登出、当前用户。
 *
 * <p>★ 这是 {@code 08 §4.2.3} 说的"只有两处必须自己写"里的<strong>第一处</strong>： 官方没有"双令牌 + 轮换"的开箱能力（Spring
 * Authorization Server 能做到，但为"内网账号密码" 引入完整的 OAuth2 授权服务器概念偏重）。边界很明确：<em>只写签发与轮换这一层薄服务</em>，
 * 验签与解析仍然全走官方 {@code JwtDecoder}。
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** 刷新令牌的随机字节数。256 位 —— 这个长度下"猜中"不是一条现实的攻击路径。 */
  private static final int REFRESH_TOKEN_BYTES = 32;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /** 统一的登录失败文案。 */
  private static final String BAD_CREDENTIALS = "账号或密码不正确";

  private final UserRepository users;
  private final RoleRepository roles;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;

  public AuthService(
      UserRepository users,
      RoleRepository roles,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService) {
    this.users = users;
    this.roles = roles;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
  }

  /**
   * 登录。
   *
   * <p>★ <strong>不区分"账号不存在"与"密码错误"</strong>：区分它们等于对外提供一个用户名探测器 （逐个试，能登录失败的方式不同）。这对内网系统也是个便宜的默认。
   * 代价是用户看到的是"账号或密码不正确"这种略含糊的提示 —— 用户知道密码，所以不影响他。
   *
   * <p>★ 但"账号被停用"是可以区分的、也应该区分：它发生在**密码校验通过之后**， 所以只有知道密码的人才会看到，不构成信息泄露，而提示能明确告诉用户去联系谁。
   */
  @Transactional
  public LoginResult login(String username, String rawPassword) {
    User user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS));

    if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS);
    }
    if (!user.canLogin()) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, statusMessage(user.status()), "请联系管理员处理账号状态");
    }

    Role role = requireRole(user);

    user.recordLogin(Instant.now());
    users.save(user);

    IssuedRefresh refresh = issueRefreshToken(user.id());
    refreshTokens.save(refresh.entity());

    log.info("用户 {} 登录成功（角色 {}）", user.username(), role.code());
    return new LoginResult(
        jwtTokenService.issueAccessToken(user, role),
        refresh.plainValue(),
        user.mustChangePassword());
  }

  /**
   * 用刷新令牌换一对新令牌（**轮换**）。
   *
   * <p>★ 重放检测在这里：一个已经被轮换掉的令牌再次出现，意味着它被复制过 （或从浏览器里被捞走了）。此时正确的反应不是"拒绝这一次"，而是
   * <strong>撤销该用户全部会话</strong> —— 因为对方手里那个令牌是有效的， 放过它等于把会话送出去一半。
   *
   * <p>代价也要说清：这会让"正常的并发刷新"（例如两个标签页同时刷新）把用户登出。 前端的 {@code lib/http.ts:47-48}
   * 已经把并发刷新收敛成一次（"刷新令牌只允许并发一次， 其余请求排队等结果"），所以这个代价在契约层面已经被规避掉了。
   */
  @Transactional
  public LoginResult refresh(String rawRefreshToken) {
    RefreshToken token =
        refreshTokens
            .findByTokenHash(sha256(rawRefreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌无效，请重新登录"));

    Instant now = Instant.now();

    if (token.revokedAt() != null) {
      refreshTokens.revokeAllActiveByUserId(token.userId(), now);
      log.warn("★ 侦测到刷新令牌重放：tokenId={} userId={} —— 已撤销该用户全部会话", token.id(), token.userId());
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌已被使用，为安全已登出全部会话", "请重新登录");
    }

    if (!token.isUsable(now)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
    }

    User user =
        users
            .findById(token.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录"));

    if (!user.canLogin()) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, statusMessage(user.status()), "请联系管理员处理账号状态");
    }

    Role role = requireRole(user);

    IssuedRefresh next = issueRefreshToken(user.id());
    refreshTokens.save(next.entity());

    // 旧令牌标记为"已被 next 替换"，不是简单撤销 —— 有了这条链，
    // 上面那段重放检测才能区分"被轮换掉"与"被显式登出"
    token.rotateTo(next.entity().id(), now);
    refreshTokens.save(token);

    return new LoginResult(
        jwtTokenService.issueAccessToken(user, role), next.plainValue(), user.mustChangePassword());
  }

  /**
   * 登出。
   *
   * <p>★ 语义是"撤销该用户<strong>全部</strong>有效刷新令牌"，而不是"只登出这一台设备"。 契约的 {@code POST /auth/logout}
   * 不带令牌参数（{@code frontend/src/api/identity.ts:79}）， 所以服务端拿不到"是哪一台"。要做单设备登出，得让请求带上 refresh token ——
   * 那是一次 契约变更，等真有人提这个需求再说。
   */
  @Transactional
  public void logout(UUID userId) {
    refreshTokens.revokeAllActiveByUserId(userId, Instant.now());
  }

  @Transactional(readOnly = true)
  public CurrentUserView currentUser(UUID userId) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在", "它可能已被删除"));

    Role role = requireRole(user);
    return new CurrentUserView(
        user.id(), user.username(), user.displayName(), role.name(), Permissions.of(role.code()));
  }

  // ────────────────────────── 内部 ──────────────────────────

  private Role requireRole(User user) {
    return roles
        .findById(user.roleId())
        .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "账号的角色不存在", "请联系管理员重新分配角色"));
  }

  private static String statusMessage(UserStatus status) {
    return switch (status) {
      case 停用 -> "该账号已停用";
      case 待审核 -> "该账号待审核，尚未开通";
      case 正常 -> "账号状态异常";
    };
  }

  /** 明文与实体一起返回 —— 明文只在这一次响应里出现，之后服务端只有哈希。 */
  private IssuedRefresh issueRefreshToken(UUID userId) {
    byte[] random = new byte[REFRESH_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(random);
    String plainValue = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

    Instant now = Instant.now();
    RefreshToken entity =
        new RefreshToken(
            UUID.randomUUID(),
            userId,
            sha256(plainValue),
            now.plus(jwtTokenService.properties().refreshTtl()),
            now);
    return new IssuedRefresh(plainValue, entity);
  }

  /**
   * sha256 十六进制。
   *
   * <p>★ 这里用 sha256 而不是 bcrypt 是**有意的**：刷新令牌是 256 位随机值， 没有"可猜的密码空间"，不需要慢哈希；而每次刷新都要按哈希查一行 —— 用
   * bcrypt 会让这个查询无法走索引（每次都得全表扫一遍、逐个重算）。
   */
  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }

  private record IssuedRefresh(String plainValue, RefreshToken entity) {}
}
