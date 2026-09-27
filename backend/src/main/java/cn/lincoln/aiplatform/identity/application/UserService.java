package cn.lincoln.aiplatform.identity.application;

import cn.lincoln.aiplatform.identity.domain.PasswordPolicy;
import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（{@code GET/POST/PUT/DELETE /users}）。
 *
 * <p>★ 用户是<strong>平台级</strong>资源，不参与 {@code dataScope} 过滤（{@code 07 §4.2}
 * 的数据面矩阵里没有"用户"这一行）：范围三档管的是"知识库 / 应用 / 会话"那三类 <em>用户产出</em>的资源，而"能看多少个账号"由 {@code system:manage}
 * 这一个动作权限决定。 把用户也塞进 dataScope 会让"管理员只看得到自己"这种荒谬配置变得可表达。
 */
@Service
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  /**
   * 建用户时不传密码时的初始口令。
   *
   * <p>★ 真源在<strong>后端</strong>，并一次性回给创建者（{@code UserView.initialPassword}）： 契约的 {@code
   * UpsertUserBody.password} 是可选的，而前端 mock 曾写死 {@code '123456'} —— 那连本项目自己的 {@link PasswordPolicy}
   * 都过不了。 管理员若按界面提示把"123456"告诉新人，登录<strong>必然失败</strong>。 所以默认口令由后端决定，并只在创建响应里出现一次（不是可随时查出的口令）。
   */
  public static final String DEFAULT_INITIAL_PASSWORD = "ChangeMe@2026";

  /**
   * 内置管理员账号名。★ 与前端 {@code UserManage.vue} 的 {@code isBuiltinAdmin} 同口径（用户名 {@code admin}）。
   *
   * <p>前端拦得住删除按钮，但前端只是体验、不是安全边界 —— 直连 API 不该绕过它。
   */
  public static final String BUILTIN_ADMIN_USERNAME = "admin";

  private static final int MAX_PAGE_SIZE = 100;

  private final UserRepository users;
  private final RoleRepository roles;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.roles = roles;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional(readOnly = true)
  public PageResult<UserView> list(
      String keyword, UUID roleId, UserStatus status, Integer page, Integer size) {
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    String safeKeyword = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

    List<User> found =
        users.search(safeKeyword, roleId, status, (safePage - 1) * safeSize, safeSize);
    long total = users.countSearch(safeKeyword, roleId, status);

    Map<UUID, String> roleNames = roleNames();
    return PageResult.of(
        found.stream().map(user -> toView(user, roleNames)).toList(), total, safePage, safeSize);
  }

  @Transactional(readOnly = true)
  public UserView get(UUID id) {
    return toView(requireUser(id), roleNames());
  }

  /**
   * 建用户。
   *
   * <p>★ 不传密码时用一个**满足口令策略**的初始口令（{@code ChangeMe@2026}）并置 {@code mustChangePassword = true}，
   * 且把该初始口令**一次性**回在 {@code UserView.initialPassword} 里。
   *
   * <p>⚠️ 契约的 mock 在这里用的是 {@code '123456'} —— 我们**不能沿用**它： 等保要求口令有长度与类别，而"6 位纯数字"连自己定的 {@link
   * PasswordPolicy} 都过不了。 一个连自己策略都违不过的初始口令，等于在系统里留了一扇后门。 首次登录强制改密由前端与路由守卫共同保证（契约已有该字段）。
   */
  @Transactional
  public UserView create(String username, String displayName, UUID roleId, String rawPassword) {
    String name = normalizeUsername(username);
    if (users.findByUsername(name).isPresent()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "账号「" + name + "」已存在", "换一个账号名，或先确认该账号是否已停用");
    }
    requireRole(roleId);

    boolean useDefault = rawPassword == null || rawPassword.isBlank();
    String password = useDefault ? DEFAULT_INITIAL_PASSWORD : rawPassword;
    if (!useDefault) {
      validatePasswordPolicy(password, name);
    }

    User user =
        new User(
            UUID.randomUUID(),
            name,
            displayName,
            passwordEncoder.encode(password),
            roleId,
            UserStatus.正常,
            // ★ 用了初始口令就必须改密；调用方自己给的密码也要改吗？不必 —— 那是他选的
            useDefault);
    users.save(user);

    log.info("新建用户 {}（角色 {}，初始口令{}）", name, roleId, useDefault ? "为默认值" : "由调用方指定");
    // ★ 初始口令只在「本次创建且走了默认口令」时回传一次。列表/详情/更新一律 null ——
    //   否则它会变成一个可随时查出的口令，等于把初始密码挂在用户详情页上。
    return toView(user, roleNames(), useDefault ? DEFAULT_INITIAL_PASSWORD : null);
  }

  /**
   * 改用户。
   *
   * <p>★ <strong>改不了用户名</strong>：契约的 {@code UpsertUserBody} 里有 {@code username} 字段，编辑态前端会一起发回来。
   * 服务端必须守这条：账号是登录标识与审计主体，改名会让历史记录里的"谁改的"指向一个对不上号的字符串。 若前端发来的 username 与现账号不一致，返 400 ——
   * 而不是"看起来改了、实际没改"（Jackson 静默忽略是最坏的一种失败）。
   *
   * <p>★ <strong>不能修改自己的角色</strong>：直连 API 把唯一的管理员降级自己 ⇒ 平台永久没人能管账号（不可恢复）。
   * 只在角色<strong>真的变了</strong>时拒 —— 管理员改自己的显示名是合法操作。
   */
  @Transactional
  public UserView update(
      UUID id,
      String username,
      String displayName,
      UUID roleId,
      String rawPassword,
      UUID operatorId) {
    User user = requireUser(id);

    // 账号名不可修改。★ 缺失（null）视为"调用方没提这事"，不拒；一旦提出且不一致就是 400。
    if (username != null && !username.isBlank() && !username.equals(user.username())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "账号名不可修改", "账号是登录标识与审计主体，改名会让历史记录对不上号；需要新账号名请新建");
    }

    requireRole(roleId);

    // 不能改自己的角色。★ 只在角色真的变了时拒，避免误伤"改自己的显示名"。
    if (user.id().equals(operatorId) && !user.roleId().equals(roleId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能修改自己的角色", "请让另一位管理员操作，避免自锁");
    }

    user.updateProfile(displayName, roleId);

    if (rawPassword != null && !rawPassword.isBlank()) {
      validatePasswordPolicy(rawPassword, user.username());
      user.markPasswordChanged(passwordEncoder.encode(rawPassword));
    }
    users.save(user);

    return toView(user, roleNames());
  }

  /**
   * 软删用户。
   *
   * <p>★ 两道守卫（顺序有意义，都要能被断言到）：
   *
   * <ol>
   *   <li><strong>不能删自己</strong>：这一条就已经保证了"平台上永远至少有一个管理员" —— 最后一个管理员删不掉自己。
   *   <li><strong>不能删内置管理员</strong>（用户名 {@code admin}）：前端拦得住按钮，但前端只是体验、 不是安全边界。直连 API 不能绕过它。
   * </ol>
   *
   * <p>★ 软删而不是硬删：用户是审计字段（{@code created_by}/{@code updated_by}）指向的东西，
   * 硬删会让历史记录里的"谁改的"变成空指针。用户名唯一用的是部分索引（{@code where deleted_at is null}）， 软删后同名账号可以重建。
   */
  @Transactional
  public void delete(UUID id, UUID operatorId) {
    User user = requireUser(id);
    // ★ 先判"不能删自己"，再判"不能删内置 admin"：否则 admin 删自己会落到第二条，
    //   "不能删除自己"这条断言就永远够不着。两种违规都要有可测路径。
    if (user.id().equals(operatorId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除自己的账号", "请让另一位管理员来停用或删除你的账号");
    }
    if (BUILTIN_ADMIN_USERNAME.equals(user.username())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "内置管理员账号不可删除", "它是平台唯一的恢复入口，删掉后无人能再管理账号");
    }
    user.softDelete(Instant.now());
    users.save(user);
    log.info("软删用户 {}", user.username());
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * ★ 找不到就 404 —— 而不是 403。
   *
   * <p>这里"找不到"是真的不存在（用户不参与范围过滤）。但语义仍然用 404： 返 403 会确认"这个 id 存在但你没权限"，是可被枚举的信息泄漏（{@code 08 §4.4.3}
   * 纪律 2）。软删后的用户也走这里 —— 对外就是"看不见"。
   */
  private User requireUser(UUID id) {
    return users
        .findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在", "它可能已被删除"));
  }

  private Role requireRole(UUID roleId) {
    return roles
        .findById(roleId)
        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在", "请选择一个有效的角色"));
  }

  private static String normalizeUsername(String username) {
    String name = username == null ? "" : username.trim();
    if (name.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "账号不能为空");
    }
    return name;
  }

  private static void validatePasswordPolicy(String rawPassword, String username) {
    PasswordPolicy.violationOf(rawPassword, username)
        .ifPresent(
            reason -> {
              throw new BusinessException(
                  ErrorCode.PASSWORD_TOO_WEAK, reason, "密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 类，且不含账号名");
            });
  }

  /** 角色表只有个位数行，一次取出来做映射 —— 避免列表页 N+1 次查询。 */
  private Map<UUID, String> roleNames() {
    Map<UUID, String> byId = new HashMap<>();
    for (Role role : roles.findAll()) {
      byId.put(role.id(), role.name());
    }
    return byId;
  }

  private static UserView toView(User user, Map<UUID, String> roleNames) {
    return toView(user, roleNames, null);
  }

  private static UserView toView(User user, Map<UUID, String> roleNames, String initialPassword) {
    return new UserView(
        user.id(),
        user.username(),
        user.displayName(),
        user.roleId(),
        Optional.ofNullable(roleNames.get(user.roleId())).orElse("（角色已删除）"),
        user.status(),
        user.lastLoginAt(),
        initialPassword);
  }
}
