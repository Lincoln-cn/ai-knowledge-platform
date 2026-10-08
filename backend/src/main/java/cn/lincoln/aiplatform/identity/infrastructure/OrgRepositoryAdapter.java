package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.Org;
import cn.lincoln.aiplatform.identity.domain.OrgRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrgRepository} 的实现（{@code 07 §十}）。
 *
 * <p>★ 两处刻意的选择：
 *
 * <ul>
 *   <li><strong>读用 JPA、子树路径重写用一次 JPQL update</strong>：日常读不需要 SQL 的灵活度， 而"一次改完整棵子树"正相反 ——
 *       它要的是"一条语句、无中间态"。
 *   <li><strong>{@code countUsers} 走 JdbcTemplate 而不是给 {@code Org} 加一个 {@code @OneToMany}</strong>：
 *       部门不需要知道用户（那是用户表的归属），而映射一段只为了数数的关联会把 加载语义（懒/急）绑进领域对象 —— 那正是本项目在别处避开的形状。
 * </ul>
 */
@Component
public class OrgRepositoryAdapter implements OrgRepository {

  private final JpaOrgRepository jpa;
  private final JdbcTemplate jdbc;

  public OrgRepositoryAdapter(JpaOrgRepository jpa, JdbcTemplate jdbc) {
    this.jpa = jpa;
    this.jdbc = jdbc;
  }

  @Override
  public List<Org> findAllOrdered() {
    return jpa.findAllByOrderByPathAsc();
  }

  @Override
  public Optional<Org> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Optional<Org> findByCode(String code) {
    return jpa.findByCode(code);
  }

  @Override
  public boolean existsByCode(String code) {
    return jpa.existsByCode(code);
  }

  @Override
  public boolean existsSiblingName(UUID parentId, String name) {
    return parentId == null
        ? jpa.existsByParentIdIsNullAndName(name)
        : jpa.existsByParentIdAndName(parentId, name);
  }

  @Override
  public Org save(Org org) {
    return jpa.save(org);
  }

  @Override
  public void delete(Org org) {
    jpa.delete(org);
  }

  @Override
  public List<Org> findSubtree(String pathPrefix) {
    return jpa.findByPathStartingWithOrderByPathAsc(pathPrefix);
  }

  @Override
  @Transactional
  public void rewriteSubtreePath(String oldPrefix, String newPrefix) {
    jpa.rewritePathPrefix(oldPrefix, newPrefix);
  }

  @Override
  public long countUsers(UUID orgId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from iam_user where org_id = ? and deleted_at is null",
            Long.class,
            orgId);
    return count == null ? 0 : count;
  }
}
