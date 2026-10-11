package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link OrgDirectoryPort} 的实现（{@code 07 §十一}）—— 端口在 platform、实现在提供方模块。
 *
 * <p>★ 实现放在 identity：组织树的<strong>读写</strong>都在这个模块里（{@code OrgRepository} / {@code
 * OrgService}），端口只是"别人看它一眼"的窗口。把它实现到别处就会立刻长出第二个真相。
 *
 * <p>★ {@link #ancestorsAndSelf(UUID)} 走<strong>物化路径前缀</strong>（{@code Org#path}，自带结尾 {@code
 * /}）而不是递归 CTE：数据权限要在每一条检索 SQL 里生效，而"我的部门及上级"在前缀比较下 planner 用得上索引（{@code 07 §10.2}）。
 *
 * <p>★ 结尾斜杠也是判定的一部分，但两个方向依赖的不是同一件事：取子树靠"<em>查询前缀</em>带斜杠"（少了它 {@code /ROOT/AD/} 会命中 {@code
 * /ROOT/ADMIN/}），取祖先链靠"<em>库里的 path</em> 都带斜杠"（少了它 {@code /ROOT/TE} 会成为 {@code /ROOT/TECH/…}
 * 的祖先）。两者的症状同为"一个部门看到了另一个部门的资料"且查询不报错。
 *
 * <p>★ 方向值得再强调一次（{@code T301} 落地时对过）：共享判定的形状是 {@code 我的集合.contains(共享目标)}， 而"共享给技术部 =
 * 技术部及下级看得见"要求集合是<strong>祖先链</strong>。用子树会得到镜像语义（"共享给我的下级 ⇒ 我看得见"）， 而两者都能跑、症状相反 ⇒ 方向写进方法名，不靠调用方记得。
 */
@Component
public class OrgDirectoryAdapter implements OrgDirectoryPort {

  private final JpaOrgRepository jpa;

  public OrgDirectoryAdapter(JpaOrgRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<OrgBrief> findById(UUID id) {
    return id == null ? Optional.empty() : jpa.findById(id).map(OrgDirectoryAdapter::brief);
  }

  @Override
  public List<OrgBrief> findByIds(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return jpa.findAllById(ids).stream().map(OrgDirectoryAdapter::brief).toList();
  }

  @Override
  public List<UUID> ancestorsAndSelf(UUID orgId) {
    if (orgId == null) {
      return List.of();
    }
    return jpa.findById(orgId)
        .map(org -> jpa.findAncestorsOfPath(org.path()).stream().map(Org::id).toList())
        .orElseGet(List::of);
  }

  private static OrgBrief brief(Org org) {
    return new OrgBrief(org.id(), org.code(), org.name(), org.path(), org.enabled());
  }
}
