package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.RoleRepository;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.platform.access.SubjectIdentityPort;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SubjectIdentityPort} 的实现（第 17 周 · {@code 08 §4.4.4}）—— 端口在 {@code platform}、实现在提供方模块。
 *
 * <p>★ 实现只能放在 {@code identity}：{@code iam_user} 与 {@code iam_role} 都在本模块 （与 {@link
 * GrantSubjectAdapter} / {@link OrgDirectoryAdapter} 同一条理由）。
 *
 * <p>★ 它是"公开面唯一的那一次查库"。登录面读<strong>令牌</strong>（零查询，代价见 {@code AccessScopeAdapter} 的类注释）， 而匿名访客没有令牌
 * ⇒ 这个人的角色 / 数据范围 / 密级 / 部门必须<strong>从库里读一次</strong>。
 * 这笔账的方向与令牌那次相反，但都只发生<strong>一次</strong>：一次请求一次委派，不是在每条资源上各查一遍。
 *
 * <p>★★ <strong>它只回答"这个人是谁"</strong>（{@code 08 §4.4.4} 纪律 1 的前半）：
 *
 * <ul>
 *   <li>链接的可用性（存在 / 已撤销 / 已过期 / 已停用）不在这里判 —— 那是 {@code PublicTokenResolver} 的事；
 *   <li>账号状态（{@code status}）<strong>不</strong>在这里判：把"账号被停用"混进"范围构造的材料"， 会让"这条链接为什么失效"多出第二个真源；链接自己的
 *       {@code enabled} / {@code revokedAt} 才是它的生命周期；
 *   <li>范围本身不在这里构造 —— 那是 {@code AccessScopeAdapter} 那一处（端口只给材料，见 record 的注释）。
 * </ul>
 */
@Component
public class SubjectIdentityAdapter implements SubjectIdentityPort {

  private static final Logger log = LoggerFactory.getLogger(SubjectIdentityAdapter.class);

  private final UserRepository users;
  private final RoleRepository roles;

  public SubjectIdentityAdapter(UserRepository users, RoleRepository roles) {
    this.users = users;
    this.roles = roles;
  }

  /**
   * {@inheritDoc}
   *
   * <p>★ 两次查询（用户 + 角色）在<strong>同一个只读事务</strong>里：身份要么完整、要么读不出来，
   * 不允许出现"读到一半的用户"（那会让范围里一半分量是新的、一半是旧的，而它不报错）。
   *
   * <p>★ 软删过滤<strong>不靠这里记得写</strong>：注入的是 domain 的 {@link UserRepository}（它的 {@code findById}
   * 只认未软删的行 —— 见 {@link UserRepositoryAdapter} 的类注释），于是"已删的账号还能被链接行使范围" 这件事在类型上就不成立。
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<SubjectIdentity> load(UUID subjectId) {
    if (subjectId == null) {
      return Optional.empty();
    }
    // ★ 两个仓储都按 **domain 接口**注入（不是 Jpa* 那个）：domain 的 findById 已经带软删过滤
    //  （UserRepositoryAdapter 的类注释把这条纪律写在一处），于是"已删的账号还能被链接行使范围"在类型上就不成立。
    User user = users.findById(subjectId).orElse(null);
    if (user == null) {
      // ★ 不存在 / 已软删 ⇒ empty（**不**抛异常：公开面上"这个人没了"的正确表现是"什么都看不见"，
      //   而抛异常会把一次正常的失效变成一次 500）
      return Optional.empty();
    }
    Role role = roles.findById(user.roleId()).orElse(null);
    if (role == null) {
      // ★ 身份只读出一半 ⇒ 当作读不出来（fail-closed），**不**退回"最窄档"：
      //   退回默认档会让"角色被误删"表现成"这个人按 own/公开 继续看得到东西"，而那种降级不会有人上报。
      log.warn(
          "主体 {} 的角色 {} 读不出来 ⇒ 公开面本次委派按'载入不出身份'处理（fail-closed，什么都看不见）", subjectId, user.roleId());
      return Optional.empty();
    }
    return Optional.of(
        new SubjectIdentity(
            user.id(),
            role.dataScope(),
            // ★ 角色用 **code**（= 令牌里的 role 声明、= 共享主体里存的那个值），不是 uuid：
            //   三处同名同值是"共享给某角色"能命中、以及 ScopeFilter.myRoles 能比对的唯一前提
            Set.of(role.code()),
            role.maxClassification(),
            Set.copyOf(role.labels()),
            user.orgId()));
  }
}
