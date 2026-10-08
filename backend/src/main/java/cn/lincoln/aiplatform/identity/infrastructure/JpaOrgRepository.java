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

  /** 子树 = 路径前缀匹配（★ 前缀自带结尾的 `/`，见 {@code Org#path} 的注释）。 */
  List<Org> findByPathStartingWithOrderByPathAsc(String prefix);

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
