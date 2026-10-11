package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.platform.access.GrantSubjectPort;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link GrantSubjectPort} 的实现（{@code 07 §8.2}）—— 端口在 platform、实现在提供方模块。
 *
 * <p>★ 实现只能放在 identity：{@code iam_user} / {@code iam_role} / {@code iam_org} 这三张表都在本模块，
 * 别的模块看不见它们（{@code ModuleBoundaryTest} 会拦）。
 *
 * <p>★ 三种 kind 的取值口径<strong>故意写在同一处</strong>，因为它们是同一件事的三个答案： {@code USER}/{@code ORG} 按 uuid、{@code
 * ROLE} 按 code（{@code Grant.subject} 的形状）。 分散到调用方就会有人拿角色 id 去比 role code —— 那是一次"看起来共享了、实际没人命中"。
 */
@Component
public class GrantSubjectAdapter implements GrantSubjectPort {

  private final JpaUserRepository users;
  private final JpaRoleRepository roles;
  private final JpaOrgRepository orgs;

  public GrantSubjectAdapter(
      JpaUserRepository users, JpaRoleRepository roles, JpaOrgRepository orgs) {
    this.users = users;
    this.roles = roles;
    this.orgs = orgs;
  }

  @Override
  public boolean exists(SubjectKind kind, String id) {
    if (kind == null || id == null || id.isBlank()) {
      return false;
    }
    return switch (kind) {
      // ★ 软删的账号不算存在：共享给一个已删除的账号是一条永远没人命中的授权
      case USER -> parseUuid(id).flatMap(users::findByIdAndDeletedAtIsNull).isPresent();
      // ★ 角色按 **code** 比（不是 uuid）—— 与 grant 里存的东西一字不差
      case ROLE -> roles.findByCode(id).isPresent();
      // ★ 停用的部门不算存在（07 §10.5 判据 3：停用不产生**新**共享，但不让既有共享失效）
      case ORG -> parseUuid(id).flatMap(orgs::findById).map(Org::enabled).orElse(false);
      // ★ 这两个是枚举里的**预留值**（至今零消费方）：显式 false 而不是 default ——
      //   将来谁给 SubjectKind 加第 6 个值，编译器会强迫它在这里做决定
      case GROUP, LINK -> false;
    };
  }

  private static Optional<UUID> parseUuid(String raw) {
    try {
      return Optional.of(UUID.fromString(raw));
    } catch (IllegalArgumentException e) {
      // 传进来的不是 uuid ⇒ 这个主体不可能存在（不抛异常：调用方要的是"能不能授权"，不是"格式对不对"）
      return Optional.empty();
    }
  }
}
