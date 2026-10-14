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
 *   <li>范围本身不在这里构造 —— 那是 {@code AccessScopeAdapter} 那一处（端口只给材料，见 record 的注释）。
 * </ul>
 *
 * <h3>★★ 账号被停用（{@code status != ACTIVE}）⇒ 载入不出（第 17 周补，{@code 08 §4.4.4} 末尾那条产品口径）</h3>
 *
 * <p>口径原文：<strong>主体账号被停用（{@code iam_user.status != ACTIVE}）⇒ 它发出的公开链接不再返回任何内容</strong>
 * （fail-closed：本端口载入不出 ⇒ {@code AccessScopeAdapter.asSubject} 装 {@code nobody()}）。
 * 理由：账号停用最常见的原因是<strong>离职 / 处置</strong>，若它的链接继续有效，等于"停用账号"不起作用。
 *
 * <p>★ 本条与上面"账号状态不在这里判"那句的关系，必须说清（否则读代码的人会以为是两处口径打架）：
 *
 * <table>
 *   <tr><th>问题</th><th>谁回答</th><th>为什么</th></tr>
 *   <tr><td>"这条<strong>链接</strong>还能用吗"（存在 / 撤销 / 过期 / 停用）</td><td>{@code PublicTokenResolver}
 *       </td><td>链接自己的生命周期。四个码（{@code 40401}–{@code 40404}）由它一处发出，前端按码分四态文案</td></tr>
 *   <tr><td>"行使范围的这个<strong>人</strong>还算数吗"</td><td>★ 本类（本方法）</td>
 *       <td>身份材料的一部分：一个已停用账号的角色 / 数据范围 / 密级<strong>不该再被拿来取数</strong>。判在这里，
 *       是因为"载入不出 ⇒ nobody()"这条 fail-closed 的落点就在本端口的返回值上</td></tr>
 * </table>
 *
 * <p>★ 两者的<strong>观感差别是有意的</strong>（口径自己写下了这笔代价）：链接失效 ⇒ 访客看到四态文案之一； 账号停用 ⇒
 * 链接<strong>打得开</strong>，但<strong>没有内容</strong>。 把后者也做成"链接已失效"需要新增第五个码与一处自动撤销 —— 那是 {@code 13 §5.3}
 * 读法 (b)，MVP 明确不采用。
 *
 * <p>★ 判据的可核对形态：{@code conversation/PublicChatHttpTest} 里"停用账号 ⇒ 不出内容"那一条 （SSE 里 {@code refs}
 * 为空、正文不含任何库内内容），以及 {@code AccessScopeAdapter.asSubject} 的 「载入不出 ⇒ {@code nobody()}」那条常驻断言。
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
    if (!user.canLogin()) {
      // ★★ 账号被停用 / 待审核 ⇒ 载入不出（08 §4.4.4 末尾的产品口径，见类注释那条表）。
      //   走去重入口 user.canLogin() 而不是在这里写 `status != ACTIVE`：三态（正常/停用/待审核）里
      //   只有"正常"能做事，而那条判断在领域对象上只有一处（User.canLogin）——
      //   将来加第四态时只会有一个改动点。
      //   ★ 观感是有意的：访客看到的是"没有内容"而不是"链接已失效"（代价写在类注释里）。
      log.warn(
          "公开面的主体 {} 账号状态不是 ACTIVE（{}）⇒ 本次委派什么都看不见（fail-closed，08 §4.4.4）",
          subjectId,
          user.status());
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
