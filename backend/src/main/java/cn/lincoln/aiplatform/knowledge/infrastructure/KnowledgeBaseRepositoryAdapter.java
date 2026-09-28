package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseGrant;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import cn.lincoln.aiplatform.platform.access.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的知识库仓储适配成 domain 的 {@link KnowledgeBaseRepository}。
 *
 * <p>★ 存在的理由只有一个：<strong>框架类型不许进领域层</strong>。领域接口用 {@code offset}/{@code limit} 与 {@link
 * ScopeFilter} 表达查询意图， Spring Data 要的是 {@code Pageable} / {@code Specification} —— 这层差异留在这里。
 *
 * <h3>★ 数据范围拼进查询（{@code 08 §4.4.3} 纪律 1）</h3>
 *
 * <p>{@link #search} 与 {@link #countSearch} 共用同一份谓词 {@link #filter}。 若"先分页查、再在内存里按范围筛"， {@code
 * total} 会是表行数、页内却是过滤后的结果 —— 用户看到"共 100 条"却只翻得出 3 条。
 *
 * <p>范围谓词与 {@code ScopeFilter.canSee} <strong>同一口径</strong>（判定只有一处实现的原则， 落到 SQL
 * 上就是"谓词必须镜像那个方法的分支"）：
 *
 * <ul>
 *   <li>{@code ALL} —— 不加归属谓词（全平台可见）
 *   <li>{@code OWN} —— <strong>个人沙箱</strong>：只看 {@code owner_id = me}，连"别人分享给我的"也不看
 *   <li>{@code SHARED} —— 自己的 + {@code PUBLIC} + 有效 grant 命中的
 * </ul>
 *
 * <p>★ 未认证（{@code me == null}）⇒ 什么都看不见：谓词拼成恒假，而不是"放行 public" —— 免登录面走的是公开凭证那条独立链路（{@code 07 §3.1}）。
 */
@Component
public class KnowledgeBaseRepositoryAdapter implements KnowledgeBaseRepository {

  private final JpaKnowledgeBaseRepository jpa;
  private final EntityManager entityManager;

  public KnowledgeBaseRepositoryAdapter(
      JpaKnowledgeBaseRepository jpa, EntityManager entityManager) {
    this.jpa = jpa;
    this.entityManager = entityManager;
  }

  @Override
  public Optional<KnowledgeBase> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Optional<KnowledgeBase> findByClientRequestIdAndOwnerId(
      String clientRequestId, UUID ownerId) {
    return jpa
        .findAll(
            (root, query, cb) ->
                cb.and(
                    cb.equal(root.get("clientRequestId"), clientRequestId),
                    cb.equal(root.get("ownerId"), ownerId),
                    cb.isNull(root.get("deletedAt"))))
        .stream()
        .findFirst();
  }

  @Override
  public KnowledgeBase save(KnowledgeBase base) {
    return jpa.save(base);
  }

  @Override
  public List<KnowledgeBase> search(
      ScopeFilter scope,
      String category,
      Boolean enabled,
      boolean mine,
      String keyword,
      int offset,
      int limit) {
    // 领域给的是 offset/limit；Spring Data 要的是"第几页、每页几条"。
    // limit 恒 > 0（调用方已归一化），所以这里的整除是安全的
    return jpa.findAll(
            filter(scope, category, enabled, mine, keyword),
            PageRequest.of(offset / limit, limit, sort()))
        .getContent();
  }

  @Override
  public long countSearch(
      ScopeFilter scope, String category, Boolean enabled, boolean mine, String keyword) {
    // ★ 与 search 共用同一份谓词（filter）—— 两处各拼一遍必然漂移，
    //   症状是"列表 3 条、total 却是 10"这种没人能解释的分页
    return jpa.count(filter(scope, category, enabled, mine, keyword));
  }

  @Override
  public List<Object[]> countByCategory(ScopeFilter scope) {
    // 分类计数也必须带范围谓词：否则会把别人私有库的分类名与数量泄露出来
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
    Root<KnowledgeBase> root = query.from(KnowledgeBase.class);
    query.multiselect(root.get("category"), cb.count(root.get("id")));
    query.groupBy(root.get("category"));
    query.where(
        visiblePredicates(root, query, cb, scope, null, null, false, null)
            .toArray(new Predicate[0]));
    query.orderBy(cb.asc(root.get("category")));
    return entityManager.createQuery(query).getResultList();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Sort sort() {
    return Sort.by(Sort.Direction.DESC, "createdAt");
  }

  /**
   * 列表筛选谓词。★ <strong>search 与 countSearch 的唯一谓词来源</strong>。
   *
   * <p>★ 空条件<strong>不拼</strong>谓词；软删过滤是<strong>恒定</strong>谓词 —— 与 {@code UserRepositoryAdapter}
   * 同一条纪律。
   */
  private static Specification<KnowledgeBase> filter(
      ScopeFilter scope, String category, Boolean enabled, boolean mine, String keyword) {
    return (root, query, cb) ->
        cb.and(
            visiblePredicates(root, query, cb, scope, category, enabled, mine, keyword)
                .toArray(new Predicate[0]));
  }

  /**
   * 软删 + 数据范围 + 业务筛选的谓词清单。分类计数与列表/计数共用，避免三处各拼一遍。
   *
   * <p>★ 空条件不拼谓词。keyword 的 pattern 在 Java 侧拼好并 {@code toLowerCase}， 列侧用 {@code lower(...)} 对齐（与
   * {@code UserRepositoryAdapter} 同一口径）。
   */
  private static List<Predicate> visiblePredicates(
      Root<KnowledgeBase> root,
      CriteriaQuery<?> query,
      CriteriaBuilder cb,
      ScopeFilter scope,
      String category,
      Boolean enabled,
      boolean mine,
      String keyword) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.isNull(root.get("deletedAt")));

    // ── 数据范围（07 §3.2 / 08 §4.4.3）──
    // ★ 与 ScopeFilter.canSee 同一分支口径；两处分叉 = 某个入口能看见不该看见的东西
    UUID me = scope.me();
    if (me == null) {
      // 未认证：什么都看不见（恒假谓词，而不是放行 public）
      predicates.add(cb.disjunction());
    } else if (scope.scope() == DataScope.ALL) {
      // 全平台：不加归属谓词
    } else if (scope.scope() == DataScope.OWN) {
      // 个人沙箱：只看自己的，连"别人分享给我的"也不看（07 §3.2）
      predicates.add(cb.equal(root.get("ownerId"), me));
    } else {
      // SHARED：自己的 + PUBLIC + 有效 grant 命中的
      predicates.add(
          cb.or(
              cb.equal(root.get("ownerId"), me),
              cb.equal(root.get("visibility"), Visibility.PUBLIC),
              grantedToMe(root, query, cb, me, scope.myRoles())));
    }

    if (mine) {
      predicates.add(cb.equal(root.get("ownerId"), me));
    }
    if (category != null && !category.isBlank()) {
      predicates.add(cb.equal(root.get("category"), category.trim()));
    }
    if (enabled != null) {
      predicates.add(cb.equal(root.get("enabled"), enabled));
    }
    if (keyword != null && !keyword.isBlank()) {
      String pattern = "%" + keyword.trim().toLowerCase() + "%";
      predicates.add(
          cb.or(
              cb.like(cb.lower(root.get("name")), pattern),
              cb.like(cb.lower(root.get("description")), pattern)));
    }
    return predicates;
  }

  /**
   * {@code EXISTS} 子查询：存在一条有效 grant 覆盖我或我的角色（{@code 07 §8.5} 的可下推谓词）。
   *
   * <p>★ kind 与 id 一起比 —— 部门「3」不是角色「3」。 时限必须带上，否则会写出一条永远不会过期的共享。
   */
  private static Predicate grantedToMe(
      Root<KnowledgeBase> root,
      CriteriaQuery<?> query,
      CriteriaBuilder cb,
      UUID me,
      Set<String> myRoles) {
    Subquery<Integer> sq = query.subquery(Integer.class);
    Root<KnowledgeBaseGrant> g = sq.from(KnowledgeBaseGrant.class);
    List<Predicate> grantPredicates = new ArrayList<>();
    grantPredicates.add(cb.equal(g.get("knowledgeBase").get("id"), root.get("id")));

    List<Predicate> subjectMatches = new ArrayList<>();
    subjectMatches.add(
        cb.and(
            cb.equal(g.get("subjectKind"), SubjectKind.USER),
            cb.equal(g.get("subjectId"), me.toString())));
    if (myRoles != null && !myRoles.isEmpty()) {
      subjectMatches.add(
          cb.and(cb.equal(g.get("subjectKind"), SubjectKind.ROLE), g.get("subjectId").in(myRoles)));
    }
    grantPredicates.add(cb.or(subjectMatches.toArray(new Predicate[0])));

    // 时效：expires_at 为 null（长期）或尚未过期 —— 不带时限的共享等于永远不会过期
    Instant now = Instant.now();
    grantPredicates.add(
        cb.or(cb.isNull(g.get("expiresAt")), cb.greaterThan(g.get("expiresAt"), now)));

    sq.select(cb.literal(1)).where(grantPredicates.toArray(new Predicate[0]));
    return cb.exists(sq);
  }
}
