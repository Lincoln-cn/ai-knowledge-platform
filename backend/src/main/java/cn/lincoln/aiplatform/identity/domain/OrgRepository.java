package cn.lincoln.aiplatform.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 组织机构仓储（{@code 07 §十}）。 */
public interface OrgRepository {

  /** 全部节点（按 path 排 —— 一棵树的自然读序，前端不必再排）。 */
  List<Org> findAllOrdered();

  Optional<Org> findById(UUID id);

  Optional<Org> findByCode(String code);

  boolean existsByCode(String code);

  /**
   * 同一父节点下是否已有这个展示名。
   *
   * <p>★ 为什么要挡（而不是"让界面自己注意"）：重名会让**所有按名字比较**的地方把两个部门混成一个 ——
   * 重名部门下的文档因此在检索里互相可见，**且不报错**。跨父重名是允许的（两个分公司各有「行政部」）。
   */
  boolean existsSiblingName(UUID parentId, String name);

  Org save(Org org);

  /** 删一个节点。★ 调用方（{@code OrgService.delete}）必须先过"没有下级、没有用户"的守卫。 */
  void delete(Org org);

  /** 某节点的**整棵子树**（含自己）—— 按物化路径前缀取。 */
  List<Org> findSubtree(String pathPrefix);

  /**
   * 重写子树的物化路径（移动部门时用）。
   *
   * <p>★ 为什么必须一次 SQL 做完：逐个改会在中途留下"父已改、子未改"的不一致窗口， 而那个窗口里的数据权限判定是<strong>错的</strong>（且不报错）。一次 {@code
   * update ... where path like} 没有这个窗口。
   */
  void rewriteSubtreePath(String oldPrefix, String newPrefix);

  /** 挂在这个部门下的用户数（删/停部门前的守卫用）。 */
  long countUsers(UUID orgId);
}
