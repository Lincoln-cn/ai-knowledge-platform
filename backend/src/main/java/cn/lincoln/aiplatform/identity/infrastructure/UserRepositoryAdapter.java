package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserRepository;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的用户仓储适配成 domain 的 {@link UserRepository}。
 *
 * <p>★ 存在的理由只有一个：**框架类型不许进领域层**。domain 的接口用 {@code offset}/{@code limit} 表达分页，而 Spring Data 用
 * {@code Pageable} / {@code Specification} —— 这层差异留在这里，领域代码里就不会出现它们。
 *
 * <p>★ 顺带在这一层落实一件纪律：所有查询都<strong>只认未软删的行</strong>。把"过滤软删"放在这一处，而不是指望每个调用方记得加条件 —— 后者会出现"列表里没有、按 id
 * 却能查到"这种不一致。
 *
 * <p>★ 列表查询用 {@link Specification} 而不是 {@code @Query} HQL：见 {@link JpaUserRepository} 类注释 —— HQL 里
 * {@code :keyword} 无类型上下文，null 时被绑成 {@code JAVA_OBJECT}（bytea）会让 {@code lower(bytea)} 直接 500，
 * 且错误类型会被缓存进查询计划。谓词按"参数是否为空"拼，空就不拼，从根上消除这一类 bug。 这也是 {@code 08 §4.3.3} 指定的做法（把范围拼进查询，不是查完再筛）。
 */
@Component
public class UserRepositoryAdapter implements UserRepository {

  private final JpaUserRepository jpa;

  public UserRepositoryAdapter(JpaUserRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<User> findByUsername(String username) {
    return jpa.findByUsernameAndDeletedAtIsNull(username);
  }

  @Override
  public Optional<User> findById(UUID id) {
    return jpa.findByIdAndDeletedAtIsNull(id);
  }

  @Override
  public User save(User user) {
    return jpa.save(user);
  }

  @Override
  public List<User> search(String keyword, UUID roleId, UserStatus status, int offset, int limit) {
    // 领域给的是 offset/limit；Spring Data 要的是"第几页、每页几条"。
    // limit 恒 > 0（调用方已归一化），所以这里的整除是安全的
    return jpa.findAll(
            filter(keyword, roleId, status), PageRequest.of(offset / limit, limit, sort()))
        .getContent();
  }

  @Override
  public long countSearch(String keyword, UUID roleId, UserStatus status) {
    // ★ 与 search 共用同一份谓词（filter）—— 两处各拼一遍必然漂移，
    //   症状是"列表 3 条、total 却是 10"这种没人能解释的分页
    return jpa.count(filter(keyword, roleId, status));
  }

  @Override
  public long countByRoleId(UUID roleId) {
    return jpa.countByRoleIdAndDeletedAtIsNull(roleId);
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Sort sort() {
    return Sort.by(Sort.Direction.ASC, "username");
  }

  /**
   * 列表筛选谓词。★ <strong>search 与 countSearch 的唯一谓词来源</strong>。
   *
   * <p>★ 空条件<strong>不拼</strong>谓词（而不是拼 {@code :param is null or ...}）：后者在 HQL 里会让无类型的 null
   * 绑定污染查询计划。
   *
   * <p>★ 模糊匹配的 pattern 在 Java 侧拼好并 {@code toLowerCase}，列侧用 {@code lower(...)} 对齐 —— 避免在 SQL 里再套
   * {@code lower(concat(...))}（那正是把类型推断弄丢的地方）。中文没有大小写， 但账号名是英文，用户不会记得自己注册时用的是大写还是小写。
   *
   * <p>★ 软删过滤是<strong>恒定</strong>谓词，不看参数 —— 与 {@code ...AndDeletedAtIsNull} 系列方法同一纪律。
   */
  private static Specification<User> filter(String keyword, UUID roleId, UserStatus status) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.isNull(root.get("deletedAt")));

      if (keyword != null && !keyword.isBlank()) {
        String pattern = "%" + keyword.trim().toLowerCase() + "%";
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("username")), pattern),
                cb.like(cb.lower(root.get("displayName")), pattern)));
      }
      if (roleId != null) {
        predicates.add(cb.equal(root.get("roleId"), roleId));
      }
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
