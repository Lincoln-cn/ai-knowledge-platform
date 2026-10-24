package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantFavorite;
import cn.lincoln.aiplatform.assistant.domain.AssistantGrant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.SubjectKind;
import cn.lincoln.aiplatform.platform.access.Visibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
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
 * 把 Spring Data 的应用仓储适配成领域的 {@link AssistantRepository}。
 *
 * <p>★ 存在的理由与 {@code KnowledgeBaseRepositoryAdapter} 一样：<strong>框架类型不许进领域层</strong>。 领域用 {@code
 * offset}/{@code limit} 与 {@link ScopeFilter} 表达查询意图，Spring Data 要的是 {@code Pageable} / {@code
 * Specification} —— 这层差异留在这里。
 *
 * <h3>★ 数据范围拼进查询（{@code 08 §4.4.3} 纪律 1）</h3>
 *
 * <p>{@link #search} 与 {@link #countSearch} 共用同一份谓词 {@link #spec}。 若"先分页查、再在内存里按范围筛"，{@code total}
 * 会是表行数、页内却是过滤后的结果 —— 用户看到"共 100 条"却只翻得出 3 条。
 *
 * <p>范围谓词与 {@code ScopeFilter.canSee} <strong>同一分支口径</strong>：
 *
 * <ul>
 *   <li>{@code ALL} —— 不加归属谓词
 *   <li>{@code OWN} —— 个人沙箱：只看 {@code owner_id = me}
 *   <li>{@code SHARED} —— 自己的 + {@code PUBLIC} + 有效 grant 命中的
 * </ul>
 *
 * <p>★ SHARED 分支的 grants 子查询与 {@code ScopeFilter.canSee} 同一份数据（{@code as_assistant_grant}）：
 * 少了它，别人共享给我的应用会出现"详情 404、列表里也没有"或更糟的"详情能看、列表看不到"。 主体三种 kind（USER / ROLE / ORG）都拼上 —— ORG
 * 用构造范围时已展开好的祖先链。
 *
 * <p>⚠️ 标签筛选用 Postgres 的 {@code array_position}：{@code tags} 是 {@code text[]}（{@code V29}）。
 * <strong>这是本文件里唯一没有闸门覆盖的 SQL 谓词</strong>（真栈那几条闸门还没走到标签筛选；市场那一批补）。
 */
@Component
public class AssistantRepositoryAdapter implements AssistantRepository {

  private final JpaAssistantRepository jpa;
  private final EntityManager em;

  public AssistantRepositoryAdapter(JpaAssistantRepository jpa, EntityManager em) {
    this.jpa = jpa;
    this.em = em;
  }

  @Override
  public Assistant save(Assistant assistant) {
    return jpa.save(assistant);
  }

  @Override
  public Optional<Assistant> findById(UUID id) {
    return jpa.findById(id).filter(assistant -> !assistant.isDeleted());
  }

  @Override
  public Optional<Assistant> findByClientRequestIdAndOwnerId(String clientRequestId, UUID ownerId) {
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
  public List<Assistant> search(ScopeFilter scope, Filter filter, int offset, int limit) {
    // 领域给的是 offset/limit；Spring Data 要的是"第几页、每页几条"（limit 恒 > 0，调用方已归一化）
    return jpa.findAll(spec(scope, filter), PageRequest.of(offset / limit, limit, sortOf(filter)))
        .getContent();
  }

  @Override
  public long countSearch(ScopeFilter scope, Filter filter) {
    // ★ 与 search 共用同一份 spec —— 两处各拼一遍必然漂移，症状是"列表 3 条、total 却是 10"
    return jpa.count(spec(scope, filter));
  }

  @Override
  public List<String> listTags(ScopeFilter scope) {
    // ★ 用 EntityManager 而不是 jpa.findAll(spec)：Spring Data 会在 spec 之后强制 query.select(root)，
    //   把这里的 `select distinct unnest(tags)` 覆盖掉（读数是"返回一串应用实体"，不是标签）。
    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<String> query = cb.createQuery(String.class);
    Root<Assistant> root = query.from(Assistant.class);
    Expression<String> tag = cb.function("unnest", String.class, root.get("tags"));
    query.select(tag).distinct(true);
    query.where(visiblePredicates(root, query, cb, scope, NO_FILTER).toArray(new Predicate[0]));
    query.orderBy(cb.asc(tag));
    return em.createQuery(query).getResultList();
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 标签列表只需要范围谓词，业务筛选一律关掉（与 mock 的语义一致）。 */
  private static final Filter NO_FILTER =
      new Filter(null, null, false, null, null, null, null, false);

  /** 契约 {@code AssistantQuery.sort} 的三档；不传 = 最近创建在前。 */
  private static Sort sortOf(Filter filter) {
    String key = filter.sort() == null ? "LATEST" : filter.sort();
    return switch (key) {
      case "POPULAR" -> Sort.by(Sort.Direction.DESC, "callCount");
      case "NAME" -> Sort.by(Sort.Direction.ASC, "name");
      default -> Sort.by(Sort.Direction.DESC, "createdAt");
    };
  }

  private static Specification<Assistant> spec(ScopeFilter scope, Filter filter) {
    return (root, query, cb) ->
        cb.and(visiblePredicates(root, query, cb, scope, filter).toArray(new Predicate[0]));
  }

  /**
   * 范围 + 筛选谓词。<strong>{@code search} 与 {@code countSearch} 共用它</strong>（两处各拼一遍必然漂移， 症状是"列表 3
   * 条、total 却是 10"）。
   *
   * <p>★ {@code query} 只给 {@code favorite} 的相关子查询用：{@code CriteriaBuilder} 的 {@code subquery} 要从外层
   * {@code CriteriaQuery} 上开，不能凭空造。
   */
  private static List<Predicate> visiblePredicates(
      Root<Assistant> root,
      CriteriaQuery<?> query,
      CriteriaBuilder cb,
      ScopeFilter scope,
      Filter filter) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.isNull(root.get("deletedAt")));

    // ── 数据范围（07 §3.2 / 08 §4.4.3）──
    // ★ 与 ScopeFilter.canSee 同一分支口径；两处分叉 = 某个入口能看见不该看见的东西
    UUID me = scope.me();
    if (me == null) {
      // 未认证：什么都看不见（恒假谓词，而不是放行 public —— 免登录走公开凭证那条独立链路）
      predicates.add(cb.disjunction());
    } else if (scope.scope() == DataScope.ALL) {
      // 全平台可见：不加归属谓词
    } else if (scope.scope() == DataScope.OWN) {
      predicates.add(cb.equal(root.get("ownerId"), me));
    } else {
      // SHARED：自己的 + PUBLIC + 有效 grant 命中的
      predicates.add(
          cb.or(
              cb.equal(root.get("ownerId"), me),
              cb.equal(root.get("visibility"), Visibility.PUBLIC),
              grantedToMe(root, query, cb, scope)));
    }

    // ── 只看我收藏的（#195）──
    // ★ 必须是相关 EXISTS：把收藏 id 先查进内存再拼 `in (...)`，总条数会在收藏变多时撞上 IN 列表上限，
    //   而且"翻页"与"筛选"会变成两次读（两次读之间取消收藏 ⇒ 页内少一条、total 没变）。
    if (filter.favorite() && me != null) {
      Subquery<UUID> favorited = query.subquery(UUID.class);
      Root<AssistantFavorite> favorite = favorited.from(AssistantFavorite.class);
      favorited
          .select(favorite.<UUID>get("assistantId"))
          .where(
              cb.equal(favorite.get("assistantId"), root.get("id")),
              cb.equal(favorite.get("userId"), me));
      predicates.add(cb.exists(favorited));
    }

    // ── 业务筛选（契约 AssistantQuery）──
    if (filter.mine()) {
      predicates.add(cb.equal(root.get("ownerId"), me));
    }
    if (filter.type() != null) {
      predicates.add(cb.equal(root.get("type"), filter.type()));
    }
    if (filter.published() != null) {
      predicates.add(cb.equal(root.get("published"), filter.published()));
    }
    if (filter.origin() != null) {
      switch (filter.origin()) {
        case "OFFICIAL" -> predicates.add(cb.isTrue(root.get("builtin")));
        case "MINE" -> predicates.add(cb.equal(root.get("ownerId"), me));
        // ★ 他人共享给我的：当前恒为空集（没有 grants 表）—— 真值就是空，不是"没过滤"
        case "SHARED" -> {
          predicates.add(cb.equal(root.get("visibility"), Visibility.SHARED));
          predicates.add(cb.notEqual(root.get("ownerId"), me));
        }
        default -> throw new IllegalArgumentException("未知的来源分段：" + filter.origin());
      }
    }
    if (filter.tag() != null && !filter.tag().isBlank()) {
      // text[] 里找这个标签：array_position(tags, ?) > 0（Postgres 原生函数，本文件唯一未被闸门覆盖的谓词）
      predicates.add(
          cb.greaterThan(
              cb.function(
                  "array_position",
                  Integer.class,
                  root.get("tags"),
                  cb.literal(filter.tag().trim())),
              0));
    }
    if (filter.keyword() != null && !filter.keyword().isBlank()) {
      String pattern = "%" + filter.keyword().trim().toLowerCase() + "%";
      predicates.add(
          cb.or(
              cb.like(cb.lower(root.get("name")), pattern),
              cb.like(cb.lower(root.get("description")), pattern)));
    }
    return predicates;
  }

  /**
   * {@code EXISTS} 子查询：存在一条有效 grant 覆盖我 / 我的角色 / 我的部门（{@code 07 §8.5} 的可下推谓词）。
   *
   * <p>★ kind 与 id 一起比 —— 部门「3」不是角色「3」。时限必须带上，否则会写出一条永远不会过期的共享。
   *
   * <p>★ 只在 {@code visibility = SHARED} 时才算：与 {@code ScopeFilter.canSee} 的分支顺序一致（PUBLIC 在前面已放行、
   * PRIVATE 直接返回 false）。否则一条残留的 grant 会让私有应用在列表里露出半张脸。
   *
   * <p>★ ORG 用 {@code scope.myOrgIds()}（构造范围时已展开好的祖先链）：共享给「技术部」= 技术部及下级看得见 ⇒ "我在它下级里" ⟺ "它在我的祖先链上"。
   */
  private static Predicate grantedToMe(
      Root<Assistant> root, CriteriaQuery<?> query, CriteriaBuilder cb, ScopeFilter scope) {
    Subquery<Integer> sq = query.subquery(Integer.class);
    Root<AssistantGrant> g = sq.from(AssistantGrant.class);
    List<Predicate> grantPredicates = new ArrayList<>();
    grantPredicates.add(cb.equal(g.get("assistant").get("id"), root.get("id")));
    grantPredicates.add(cb.equal(root.get("visibility"), Visibility.SHARED));

    List<Predicate> subjectMatches = new ArrayList<>();
    UUID me = scope.me();
    subjectMatches.add(
        cb.and(
            cb.equal(g.get("subjectKind"), SubjectKind.USER),
            cb.equal(g.get("subjectId"), me.toString())));
    Set<String> myRoles = scope.myRoles();
    if (myRoles != null && !myRoles.isEmpty()) {
      subjectMatches.add(
          cb.and(cb.equal(g.get("subjectKind"), SubjectKind.ROLE), g.get("subjectId").in(myRoles)));
    }
    Set<String> myOrgIds = scope.myOrgIds();
    if (myOrgIds != null && !myOrgIds.isEmpty()) {
      subjectMatches.add(
          cb.and(cb.equal(g.get("subjectKind"), SubjectKind.ORG), g.get("subjectId").in(myOrgIds)));
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
