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
 * <p>★ 列表筛选（{@code keyword} / {@code kind} / {@code status}，对应契约 {@code
 * ModelQuery}）<strong>拼进查询</strong>，不是查完再筛 —— 后者会让 {@code total} 与页内条数对不上（{@code 08 §4.3.3}）。
 */
public interface ModelRepository {

  Optional<Model> findById(UUID id);

  Model save(Model model);

  void delete(Model model);

  /**
   * 分页查询（按 name 排序）。{@code kind} / {@code status} 为 null 表示不按该条件筛。
   *
   * <p>{@code keyword} 对 {@code name} / {@code baseModel} 做大小写不敏感的模糊匹配；null 或空白表示不筛。
   */
  List<Model> search(
      String keyword, ModelKind kind, ModelStatus status, UUID providerId, int offset, int limit);

  /** 与 {@link #search} 同条件的<strong>总数</strong>。★ 它必须是过滤后的数，不能是表行数。 */
  long countSearch(String keyword, ModelKind kind, ModelStatus status, UUID providerId);

  /** 某个服务商下还有多少个模型 —— 删除服务商前的守卫读它。 */
  long countByProviderId(UUID providerId);

  /**
   * 同一个基础模型的全部向量模型（`#305` 缺口 B）。
   *
   * <p>存在理由：向量维度决定写哪张 {@code vec_embedding_<dim>} 表。同一个基础模型被登记成两个维度时， 两批向量会分居两表、互相检索不到 ——
   * 而这种错在界面上看起来完全正常，只有在"换了个模型重新向量化"之后 才会以"检索突然少一半"的形式暴露。
   */
  default List<Model> embeddingModelsOf(String baseModel) {
    // ★ 默认返回空、而不是要求每个实现都写一遍：这是一个**新增的校验输入**，不是核心能力。
    //   生产的唯一实现 ModelRepositoryAdapter 会覆盖它；测试里的 Fake 不该被这种细节牵连
    //   （`#305` 缺口 B 之前，三个测试各有一个 FakeModels，加抽象方法当场把它们全编译红）。
    return List.of();
  }
}
