package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import cn.lincoln.aiplatform.platform.access.Visibility;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *   <li>{@code SHARED} —— 自己的 + {@code PUBLIC}
 * </ul>
 *
 * <p>⚠️ SHARED 分支<strong>没有 grants 子查询</strong>：{@code assistant} 的共享表与写入口在"共享"那一批（{@code V29}
 * 文末）。于是"别人共享给我的应用"当前是<strong>空集</strong> —— 那不是遗漏，是当前真相， 且方向是 fail-closed（看不见，而不是看见不该看的）。 将来加
 * grants 时，这里照 {@code KnowledgeBaseRepositoryAdapter.grantedToMe} 长出 EXISTS 子查询即可。
 *
 * <p>⚠️ 标签筛选用 Postgres 的 {@code array_position}：{@code tags} 是 {@code text[]}（{@code V29}）。
 * <strong>这是本文件里唯一没有闸门覆盖的 SQL 谓词</strong>（真栈那几条闸门还没走到标签筛选；市场那一批补）。
 */
@Component
public class AssistantRepositoryAdapter implements AssistantRepository {

  private final JpaAssistantRepository jpa;

  public AssistantRepositoryAdapter(JpaAssistantRepository jpa) {
    this.jpa = jpa;
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

  // ────────────────────────── 内部 ──────────────────────────

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
        cb.and(visiblePredicates(root, cb, scope, filter).toArray(new Predicate[0]));
  }

  private static List<Predicate> visiblePredicates(
      Root<Assistant> root, CriteriaBuilder cb, ScopeFilter scope, Filter filter) {
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
      predicates.add(
          cb.or(
              cb.equal(root.get("ownerId"), me),
              cb.equal(root.get("visibility"), Visibility.PUBLIC)));
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
}
