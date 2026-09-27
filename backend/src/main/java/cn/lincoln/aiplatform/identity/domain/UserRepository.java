package cn.lincoln.aiplatform.identity.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储（接口定义在 {@code domain}，实现放 {@code infrastructure}）。
 *
 * <p>★ 依赖倒置：领域层不认识 JPA。实现由 {@code infrastructure} 的 {@code JpaUserRepository}（Spring Data）提供 ——
 * 于是领域代码里不出现任何 {@code EntityManager} / {@code Query} 类型。
 */
public interface UserRepository {

  Optional<User> findByUsername(String username);

  Optional<User> findById(UUID id);

  User save(User user);
}
