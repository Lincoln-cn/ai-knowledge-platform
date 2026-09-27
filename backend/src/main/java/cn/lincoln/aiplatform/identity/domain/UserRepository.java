package cn.lincoln.aiplatform.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储（接口定义在 {@code domain}，实现放 {@code infrastructure}）。
 *
 * <p>★ 依赖倒置：领域层不认识 JPA，所以下面这些方法的参数与返回值都是**领域类型** （{@link User} / {@link UserStatus}），没有 `Page` /
 * `Specification` 这类框架类型。 分页用最朴素的 {@code offset}/{@code limit} 表达 —— 它足够清楚， 而引入 Spring Data 的
 * `Pageable` 会把框架类型漏进领域层。
 */
public interface UserRepository {

  Optional<User> findByUsername(String username);

  Optional<User> findById(UUID id);

  User save(User user);

  /** 分页查询（按 username 排序）。★ 只返回未软删的。 */
  List<User> search(String keyword, UUID roleId, UserStatus status, int offset, int limit);

  /** 与 {@link #search} 同条件的**总数**。★ 它必须是过滤后的数，不能是表行数。 */
  long countSearch(String keyword, UUID roleId, UserStatus status);

  /** 某角色下的用户数（角色的 {@code memberCount}）。 */
  long countByRoleId(UUID roleId);
}
