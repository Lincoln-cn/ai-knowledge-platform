package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data 实现，同时满足 {@link UserRepository} 这个 domain 接口。
 *
 * <p>这个"双继承"的写法是刻意的：{@code domain} 定义的是纯接口（不认识 JPA）， 而实现类把它们接到 Spring Data 的 {@code JpaRepository}
 * 上 —— 于是领域代码里看不到任何持久化类型，而实现只需要声明方法、不必写方法体。
 */
public interface JpaUserRepository extends JpaRepository<User, UUID>, UserRepository {

  @Override
  Optional<User> findByUsername(String username);
}
