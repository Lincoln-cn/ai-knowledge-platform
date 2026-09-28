package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
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
 * 把 Spring Data 的模型仓储适配成 domain 的 {@link ModelRepository}。
 *
 * <p>★ 存在的理由只有一个：<strong>框架类型不许进领域层</strong>。domain 的接口用 {@code offset}/{@code limit} 表达分页，而 Spring
 * Data 用 {@code Pageable} / {@code Specification} —— 这层差异留在这里。
 *
 * <p>★ 列表筛选<strong>拼进查询</strong>（{@code 08 §4.3.3}），不是查完再筛：后者的 {@code total} 是表行数、页内却是过滤后的结果，
 * 用户会看到"共 100 条"却只翻得出 3 条。{@code search} 与 {@code countSearch} 共用同一份谓词 {@link #filter}， 两处各拼一遍必然漂移。
 */
@Component
public class ModelRepositoryAdapter implements ModelRepository {

  private final JpaModelRepository jpa;

  public ModelRepositoryAdapter(JpaModelRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Model> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Model save(Model model) {
    return jpa.save(model);
  }

  @Override
  public void delete(Model model) {
    jpa.delete(model);
  }

  @Override
  public List<Model> search(ModelKind kind, ModelStatus status, int offset, int limit) {
    // 领域给的是 offset/limit；Spring Data 要的是"第几页、每页几条"。
    // limit 恒 > 0（调用方已归一化），所以这里的整除是安全的
    return jpa.findAll(filter(kind, status), PageRequest.of(offset / limit, limit, sort()))
        .getContent();
  }

  @Override
  public long countSearch(ModelKind kind, ModelStatus status) {
    // ★ 与 search 共用同一份谓词 —— 两处各拼一遍必然漂移，症状是"列表 3 条、total 却是 10"
    return jpa.count(filter(kind, status));
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Sort sort() {
    return Sort.by(Sort.Direction.ASC, "name");
  }

  /**
   * 列表筛选谓词。★ <strong>search 与 countSearch 的唯一谓词来源</strong>。
   *
   * <p>★ 空条件<strong>不拼</strong>谓词（而不是拼 {@code :param is null or ...}）：后者在 HQL 里会让无类型的 null
   * 绑定污染查询计划。
   */
  private static Specification<Model> filter(ModelKind kind, ModelStatus status) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (kind != null) {
        predicates.add(cb.equal(root.get("kind"), kind));
      }
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
