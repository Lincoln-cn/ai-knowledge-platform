package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Org;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@link Org} 的 Spring Data 仓储（{@link OrgRepositoryAdapter} 用它）。 */
public interface JpaOrgRepository extends JpaRepository<Org, UUID> {

  List<Org> findAllByOrderByPathAsc();

  Optional<Org> findByCode(String code);

  boolean existsByCode(String code);

  /**
   * 兄弟重名检查。分两个方法是因为"父为 null"在派生查询里要用 `IsNull` 显式表达 —— ★ 与迁移里那两条 partial unique index
   * 同口径（`unique(parent_id, name)` 对 NULL 不判等， 根节点会被整段漏过）。
   */
  boolean existsByParentIdAndName(UUID parentId, String name);

  boolean existsByParentIdIsNullAndName(String name);

  /** 子树 = 路径前缀匹配（★ 前缀自带结尾的 `/`，见 {@code Org#path} 的注释）。 */
  List<Org> findByPathStartingWithOrderByPathAsc(String prefix);

  /**
   * 祖先链 = <strong>反方向</strong>的前缀匹配：我这条 path 以它的 path 开头。
   *
   * <p>★ 它与 {@link #findByPathStartingWithOrderByPathAsc} 互为镜像，失效模式也互为镜像：
   * 那两个查询靠"<strong>查询用的前缀</strong>自带结尾 `/`"保证正确，而<strong>本查询</strong>靠"<strong>库里的 path</strong> 都以
   * `/` 结尾"这条不变量 —— 若某个节点的 path 不以斜杠结尾（例如 {@code /ROOT/TE}），它会被当成 {@code /ROOT/TECH/TECH_RD/} 的祖先 ⇒
   * 一次静默越权。
   */
  @Query("select o from Org o where :path like concat(o.path, '%') order by o.path asc")
  List<Org> findAncestorsOfPath(@Param("path") String path);

  /**
   * 一次重写子树的物化路径。
   *
   * <p>★ 用 {@code concat} 而不是"取出来改再存"：后者要逐行，中途会出现"父已改、子未改"的窗口， 而那个窗口里的数据权限判定是错的（{@code 07 §10.2}）。
   */
  @Modifying
  @Query(
      "update Org o set o.path = concat(:newPrefix, substring(o.path, length(:oldPrefix) + 1))"
          + " where o.path like concat(:oldPrefix, '%')")
  int rewritePathPrefix(@Param("oldPrefix") String oldPrefix, @Param("newPrefix") String newPrefix);
}
