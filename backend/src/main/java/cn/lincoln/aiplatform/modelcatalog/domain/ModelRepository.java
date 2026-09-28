package cn.lincoln.aiplatform.modelcatalog.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模型仓储（接口定义在 {@code domain}，实现放 {@code infrastructure}）。
 *
 * <p>★ 依赖倒置：领域层不认识 JPA，参数与返回值都是<strong>领域类型</strong>，没有 {@code Page} / {@code Specification}
 * 这类框架类型。 分页用最朴素的 {@code offset}/{@code limit} —— 与 {@code identity/domain/UserRepository} 同一口径： 引入
 * Spring Data 的 {@code Pageable} 会把框架类型漏进领域层。
 *
 * <p>★ 列表筛选（{@code kind} / {@code status}，对应契约 {@code ModelQuery}）<strong>拼进查询</strong>，不是查完再筛 ——
 * 后者会让 {@code total} 与页内条数对不上（{@code 08 §4.3.3}）。
 */
public interface ModelRepository {

  Optional<Model> findById(UUID id);

  Model save(Model model);

  void delete(Model model);

  /** 分页查询（按 name 排序）。{@code kind} / {@code status} 为 null 表示不按该条件筛。 */
  List<Model> search(ModelKind kind, ModelStatus status, int offset, int limit);

  /** 与 {@link #search} 同条件的<strong>总数</strong>。★ 它必须是过滤后的数，不能是表行数。 */
  long countSearch(ModelKind kind, ModelStatus status);
}
