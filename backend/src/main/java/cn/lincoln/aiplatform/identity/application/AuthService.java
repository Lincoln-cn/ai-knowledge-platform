package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.PasswordPolicy;
import cn.lincoln.aiplatform.identity.domain.RefreshToken;
import cn.lincoln.aiplatform.identity.domain.RefreshTokenRepository;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RolePermissionRepository;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import cn.lincoln.aiplatform.identity.infrastructure.CaptchaProperties;
import cn.lincoln.aiplatform.identity.infrastructure.CaptchaService;
import cn.lincoln.aiplatform.identity.infrastructure.JwtTokenService;
import cn.lincoln.aiplatform.identity.infrastructure.LoginFailureProperties;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证：登录、刷新（轮换 + 重放检测）、登出、改密、当前用户。
 *
 * <p>★ 这是 {@code 08 §4.2.3} 说的"只有两处必须自己写"里的<strong>第一处</strong>： 官方没有"双令牌 +
 * 轮换"的开箱能力。边界仍然明确：<em>只写签发与轮换这一层薄服务</em>， 验签与解析全走官方 {@code JwtDecoder}。
 *
 * <h3>登录的四道关（顺序是有讲究的）</h3>
 *
 * <ol>
 *   <li><strong>验证码</strong>（等保）—— 最廉价的一道，放在最前面挡自动化脚本
 *   <li><strong>锁定检查</strong>（等保）—— ★ 放在密码校验<em>之前</em>：已锁定就不必再比对密码， 而 BCrypt 校验正是被暴力破解时最贵的那个操作
 *   <li><strong>密码</strong> —— 失败要计数，达到阈值触发锁定
 *   <li><strong>账号状态</strong> —— 停用 / 待审核
 * </ol>
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
  private final RolePermissionRepository rolePermissions;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;
  private final CaptchaService captchaService;
  private final CaptchaProperties captchaProperties;
  private final LoginFailureProperties loginFailure;
  private final AuthSecurityRecorder securityRecorder;

  public AuthService(
      UserRepository users,
      RoleRepository roles,
      RolePermissionRepository rolePermissions,
      RefreshTokenRepository refreshTokens,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      CaptchaService captchaService,
      CaptchaProperties captchaProperties,
      LoginFailureProperties loginFailure,
      AuthSecurityRecorder securityRecorder) {
    this.users = users;
    this.roles = roles;
    this.rolePermissions = rolePermissions;
    this.refreshTokens = refreshTokens;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
    this.captchaService = captchaService;
    this.captchaProperties = captchaProperties;
    this.loginFailure = loginFailure;
    this.securityRecorder = securityRecorder;
  }

  /** 发一张新验证码。 */
  @Transactional(readOnly = true)
  public CaptchaView issueCaptcha() {
    return captchaService.issue();
  }

  /**
   * 登录。
   *
   * <p>★ <strong>不区分"账号不存在"与"密码错误"</strong>：区分它们等于对外提供一个用户名探测器。 代价是提示略含糊 —— 而用户自己是知道密码的，所以不影响他。
   *
   * <p>★ 但"账号被停用 / 被锁定"是可以区分的、也应该区分：它们出现在<strong>密码校验通过之后 （或被锁定这个状态本身）</strong>，提示能明确告诉用户该去做什么。
   *
   * <p>★ 失败时<strong>不提示"还剩几次"</strong>：那等于给攻击者一个进度条。 改为在 hint 里告知规则（"连续失败 N 次会锁定"）—— 合法用户看到规则就够用了。
   */
  @Transactional
  public LoginResult login(
      String username, String rawPassword, String captchaId, String captchaCode) {
    Instant now = Instant.now();

    // ── 第一道：验证码（等保要求）──
    if (captchaProperties.enabled() && !captchaService.verify(captchaId, captchaCode)) {
      throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码不正确或已过期", "点一下图片可以换一张");
    }

    User user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS));

    // ── 第二道：锁定（★ 在密码校验之前）──
    if (user.isLocked(now)) {
      throw new BusinessException(
          ErrorCode.ACCOUNT_LOCKED,
          "账号已被锁定，请 " + minutesOf(user.remainingLockout(now)) + " 分钟后再试",
          lockoutRule());
    }

    // ── 第三道：密码 ──
    if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
      // ★ 走独立事务记录（见 AuthSecurityRecorder 的类注释）：下面紧跟着 throw，
      //   共用一个事务的话这次写入会被回滚 ⇒ 失败计数永远为 0 ⇒ 账号永远锁不上
      User afterFailure =
          securityRecorder.recordLoginFailure(
              user.id(), now, loginFailure.maxAttempts(), loginFailure.lockDuration());

      if (afterFailure.isLocked(now)) {
        log.warn(
            "账号 {} 连续登录失败达 {} 次，已锁定至 {}",
            user.username(),
            loginFailure.maxAttempts(),
            afterFailure.lockedUntil());
        throw new BusinessException(
            ErrorCode.ACCOUNT_LOCKED,
            "连续登录失败 "
                + loginFailure.maxAttempts()
                + " 次，账号已锁定 "
                + minutesOf(loginFailure.lockDuration())
                + " 分钟",
            "锁定期间无法登录，请稍后再试或联系管理员");
      }
      throw new BusinessException(ErrorCode.UNAUTHORIZED, BAD_CREDENTIALS, lockoutRule());
    }

    // ── 第四道：账号状态 ──
    if (!user.canLogin()) {
      throw new BusinessException(
          ErrorCode.FORBIDDEN, statusMessage(user.status()), "请联系管理员处理账号状态");
    }

    Role role = requireRole(user);

    user.recordSuccessfulLogin(now);
    users.save(user);

    IssuedRefresh refresh = issueRefreshToken(user.id());
    refreshTokens.save(refresh.entity());

    log.info("用户 {} 登录成功（角色 {}）", user.username(), role.code());
    return new LoginResult(
        jwtTokenService.issueAccessToken(user, role, authoritiesOf(role)),
        refresh.plainValue(),
        user.mustChangePassword());
  }

  /**
   * 用刷新令牌换一对新令牌（**轮换**）。
   *
   * <p>★ 重放检测：一个已被轮换掉的令牌再次出现，意味着它被复制过（或从浏览器里被捞走了）。 此时正确的反应不是"拒绝这一次"，而是<strong>撤销该用户全部会话</strong> ——
   * 因为对方手里那个令牌是有效的，放过它等于把会话送出去一半。
   *
   * <p>★ 这里<strong>不检查登录锁定</strong>：锁定的目的是阻止"密码猜测"，而刷新不用密码，
   * 不构成猜测途径。若在这里也拒，会出现"别人试你的密码、结果把你自己的会话踢掉"的荒谬结果。
   */
  @Transactional
  public LoginResult refresh(String rawRefreshToken) {
    RefreshToken token =
        refreshTokens
            .findByTokenHash(sha256(rawRefreshToken))
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "刷新令牌无效，请重新登录"));

    Instant now = Instant.now();

    if (token.revokedAt() != null) {
      // ★ 同样必须走独立事务：下面立刻 throw，共用事务会把这次撤销一起回滚掉，
      //   于是"侦测到重放就掐断所有会话"变成一句空话，攻击者手里那个令牌依然有效
      securityRecorder.revokeAllSessionsAfterReplay(token.userId(), now);
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
        jwtTokenService.issueAccessToken(user, role, authoritiesOf(role)),
        next.plainValue(),
        user.mustChangePassword());
  }

  /**
   * 登出。
   *
   * <p>★ 语义是"撤销该用户<strong>全部</strong>有效刷新令牌"，而不是"只登出这一台设备"。 契约的 {@code POST /auth/logout}
   * 不带令牌参数（{@code frontend/src/api/identity.ts:79}）， 所以服务端拿不到"是哪一台"。要做单设备登出，得让请求带上 refresh token ——
   * 那是一次契约变更。
   */
  @Transactional
  public void logout(UUID userId) {
    refreshTokens.revokeAllActiveByUserId(userId, Instant.now());
  }

  /**
   * 改密（等保：口令应有强度要求）。
   *
   * <p>★ 改密成功后<strong>撤销该用户全部刷新令牌</strong>：口令都换了， 此前签发的会话凭据不该还能继续续期 —— 这正是"改密用于止血"时要的效果。 （访问令牌最长还能活
   * 30 分钟，这是短有效期设计的代价，也是它的收益。）
   */
  @Transactional
  public void changePassword(UUID userId, String oldPassword, String newPassword) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在", "它可能已被删除"));

    if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "原密码不正确", "改密需要先验证原密码");
    }

    PasswordPolicy.violationOf(newPassword, user.username())
        .ifPresent(
            reason -> {
              throw new BusinessException(
                  ErrorCode.PASSWORD_TOO_WEAK, reason, "密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类，且不含账号名");
            });

    user.markPasswordChanged(passwordEncoder.encode(newPassword));
    users.save(user);
    refreshTokens.revokeAllActiveByUserId(userId, Instant.now());

    log.info("用户 {} 修改了密码，其全部刷新令牌已撤销", user.username());
  }

  @Transactional(readOnly = true)
  public CurrentUserView currentUser(UUID userId) {
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在", "它可能已被删除"));

    Role role = requireRole(user);
    // ★ 与令牌同一真源：GET /users/me 回显的权限点必须与 @PreAuthorize 看到的一致。
    //   若这里回 Permissions.of 而令牌写库值，界面会显示一套、鉴权认另一套。
    return new CurrentUserView(
        user.id(), user.username(), user.displayName(), role.name(), authoritiesOf(role));
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 角色当前持有的授权集合。★ 真源是 {@code iam_role_permission}（界面上勾的）， 不是 {@code Permissions.of}（那是启动 seed 与
   * ADMIN 维护用的初始映射）。
   */
  private List<String> authoritiesOf(Role role) {
    return rolePermissions.findByRoleId(role.id());
  }

  private String lockoutRule() {
    return "连续登录失败 "
        + loginFailure.maxAttempts()
        + " 次会锁定账号 "
        + minutesOf(loginFailure.lockDuration())
        + " 分钟";
  }

  private static long minutesOf(java.time.Duration duration) {
    return Math.max(1, duration.toMinutes());
  }

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
   * bcrypt 会让这个查询无法走索引（每次全表扫一遍、逐个重算）。
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
