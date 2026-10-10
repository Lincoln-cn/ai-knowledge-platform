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
 * <p>★ {@link #selfAndDescendants(UUID)} 走<strong>物化路径前缀</strong>（{@code Org#path}，自带结尾 {@code
 * /}）而不是递归 CTE：数据权限要在每一条检索 SQL 里生效，而"我的部门及下级"在前缀比较下 planner 用得上索引（{@code 07 §10.2}）。★
 * 结尾斜杠也是判定的一部分：少了它， {@code /ROOT/AD/} 会 match 到 {@code /ROOT/ADMIN/} —— 一次"看到别人部门"的越权且不报错。
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
  public List<UUID> selfAndDescendants(UUID orgId) {
    if (orgId == null) {
      return List.of();
    }
    return jpa.findById(orgId)
        .map(
            org ->
                jpa.findByPathStartingWithOrderByPathAsc(org.path()).stream().map(Org::id).toList())
        .orElseGet(List::of);
  }

  private static OrgBrief brief(Org org) {
    return new OrgBrief(org.id(), org.code(), org.name(), org.path(), org.enabled());
  }
}
