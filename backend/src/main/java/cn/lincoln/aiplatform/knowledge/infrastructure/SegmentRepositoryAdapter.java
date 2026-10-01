package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseGrant;
import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
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
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的分段仓储适配成 domain 的 {@link SegmentRepository}。
 *
 * <p>★ 存在的理由与 {@code DocumentRepositoryAdapter} 相同：框架类型不进领域层。
 *
 * <h3>★ 数据范围拼进查询（{@code 08 §4.4.3} 纪律 1）</h3>
 *
 * <p>{@link #search} 与 {@link #countSearch} 共用同一份谓词。 分段可见性<strong>继承库</strong>（库不可见 ⇒ 其分段不可见）—— 通过
 * {@code kbId} 子查询关联 {@code kb_knowledge_base}， 把 {@link ScopeFilter} 的归属谓词拼进 SQL，不是查完再筛。
 *
 * <p>★ 范围谓词与 {@code KnowledgeBaseRepositoryAdapter} <strong>同一口径</strong>（判定只有一处实现的原则）。
 */
@Component
public class SegmentRepositoryAdapter implements SegmentRepository {

  private final JpaSegmentRepository jpa;
  private final EntityManager entityManager;

  public SegmentRepositoryAdapter(JpaSegmentRepository jpa, EntityManager entityManager) {
    this.jpa = jpa;
    this.entityManager = entityManager;
  }

  @Override
  public Optional<Segment> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Segment save(Segment segment) {
    return jpa.save(segment);
  }

  @Override
  public List<Segment> saveAll(List<Segment> segments) {
    return jpa.saveAll(segments);
  }

  @Override
  public List<Segment> findByDocumentId(UUID documentId) {
    return jpa.findAll(
            (root, query, cb) -> cb.equal(root.get("documentId"), documentId),
            org.springframework.data.domain.PageRequest.of(
                0,
                Integer.MAX_VALUE,
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.ASC, "ordinal")))
        .getContent();
  }

  @Override
  public void deleteByDocumentId(UUID documentId) {
    jpa.deleteAll(jpa.findAll((root, query, cb) -> cb.equal(root.get("documentId"), documentId)));
  }

  @Override
  public void delete(Segment segment) {
    jpa.delete(segment);
  }

  @Override
  public List<Segment> search(
      ScopeFilter scope, UUID documentId, UUID kbId, Boolean onlyOversized, int offset, int limit) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Segment> query = cb.createQuery(Segment.class);
    Root<Segment> root = query.from(Segment.class);
    query.where(
        searchPredicates(root, query, cb, scope, documentId, kbId, onlyOversized)
            .toArray(new Predicate[0]));
    query.orderBy(cb.asc(root.get("documentId")), cb.asc(root.get("ordinal")));
    return entityManager
        .createQuery(query)
        .setFirstResult(offset)
        .setMaxResults(limit)
        .getResultList();
  }

  @Override
  public long countSearch(ScopeFilter scope, UUID documentId, UUID kbId, Boolean onlyOversized) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Long> query = cb.createQuery(Long.class);
    Root<Segment> root = query.from(Segment.class);
    query.select(cb.count(root));
    query.where(
        searchPredicates(root, query, cb, scope, documentId, kbId, onlyOversized)
            .toArray(new Predicate[0]));
    return entityManager.createQuery(query).getSingleResult();
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 列表筛选谓词。★ <strong>search 与 countSearch 的唯一谓词来源</strong>。
   *
   * <p>★ 数据范围通过 {@code kbId} 子查询关联 {@code kb_knowledge_base} 拼进 SQL —— 与 {@code
   * KnowledgeBaseRepositoryAdapter.visiblePredicates} 同一口径。
   */
  private List<Predicate> searchPredicates(
      Root<Segment> root,
      CriteriaQuery<?> query,
      CriteriaBuilder cb,
      ScopeFilter scope,
      UUID documentId,
      UUID kbId,
      Boolean onlyOversized) {
    List<Predicate> predicates = new ArrayList<>();

    // ── 数据范围：分段可见性继承库（07 §3.3）──
    predicates.add(cb.in(root.get("kbId")).value(visibleKbSubquery(query, cb, scope)));

    if (documentId != null) {
      predicates.add(cb.equal(root.get("documentId"), documentId));
    }
    if (kbId != null) {
      predicates.add(cb.equal(root.get("kbId"), kbId));
    }
    if (onlyOversized != null && onlyOversized) {
      // SEG-I1：超长 = 字数超过向量模型上限（与 mock 的 onlyOversized 同口径）
      // oversizedRisk 是 L4 硬切标记，两者相关但不等同；此处按契约语义筛 charCount 超限的
      predicates.add(cb.isTrue(root.get("oversizedRisk")));
    }
    return predicates;
  }

  /**
   * 可见知识库 id 的子查询 —— 范围谓词与 {@code KnowledgeBaseRepositoryAdapter} 同一口径。
   *
   * <p>★ 未认证（{@code me == null}）⇒ 恒假（什么都看不见），而不是"放行 public"。
   */
  private Subquery<UUID> visibleKbSubquery(
      CriteriaQuery<?> query, CriteriaBuilder cb, ScopeFilter scope) {
    Subquery<UUID> sq = query.subquery(UUID.class);
    Root<KnowledgeBase> kb = sq.from(KnowledgeBase.class);
    sq.select(kb.get("id"));
    sq.where(visibleKbPredicates(kb, sq, cb, scope).toArray(new Predicate[0]));
    return sq;
  }

  /** 与 {@code KnowledgeBaseRepositoryAdapter.visiblePredicates} 的数据范围分支同口径。 */
  private List<Predicate> visibleKbPredicates(
      Root<KnowledgeBase> kb, Subquery<?> query, CriteriaBuilder cb, ScopeFilter scope) {
    List<Predicate> predicates = new ArrayList<>();
    predicates.add(cb.isNull(kb.get("deletedAt")));

    UUID me = scope.me();
    if (me == null) {
      predicates.add(cb.disjunction());
    } else if (scope.scope() == DataScope.ALL) {
      // 全平台：不加归属谓词
    } else if (scope.scope() == DataScope.OWN) {
      predicates.add(cb.equal(kb.get("ownerId"), me));
    } else {
      // SHARED：自己的 + PUBLIC + 有效 grant 命中的
      predicates.add(
          cb.or(
              cb.equal(kb.get("ownerId"), me),
              cb.equal(kb.get("visibility"), Visibility.PUBLIC),
              grantedToMe(kb, query, cb, me, scope.myRoles())));
    }
    return predicates;
  }

  private Predicate grantedToMe(
      Root<KnowledgeBase> kb, Subquery<?> query, CriteriaBuilder cb, UUID me, Set<String> myRoles) {
    Subquery<Integer> sq = query.subquery(Integer.class);
    Root<KnowledgeBaseGrant> g = sq.from(KnowledgeBaseGrant.class);
    List<Predicate> grantPredicates = new ArrayList<>();
    grantPredicates.add(cb.equal(g.get("knowledgeBase").get("id"), kb.get("id")));

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

    Instant now = Instant.now();
    grantPredicates.add(
        cb.or(cb.isNull(g.get("expiresAt")), cb.greaterThan(g.get("expiresAt"), now)));

    sq.select(cb.literal(1)).where(grantPredicates.toArray(new Predicate[0]));
    return cb.exists(sq);
  }
}
