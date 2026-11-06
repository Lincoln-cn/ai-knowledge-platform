package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的模型仓储。
 *
 * <p>★ 纯 Spring Data 接口，不同时继承 domain 的 {@code ModelRepository} —— 双继承写法一旦涉及分页就必须出现 {@code Pageable}
 * （框架类型），而领域接口不该认识它（与 {@code JpaUserRepository} 同一条判断）。
 *
 * <p>★ 列表查询用 {@link JpaSpecificationExecutor} 而不是 {@code @Query} HQL：空条件就不拼谓词， 从根上消除「无类型 null
 * 绑定污染查询计划」那类 bug （见 {@code JpaUserRepository} 类注释记下的联调真 bug）。
 */
public interface JpaModelRepository
    extends JpaRepository<Model, UUID>, JpaSpecificationExecutor<Model> {

  /**
   * 同一 baseModel 的全部模型（`#305` 缺口 B：同一基础模型只能有一个向量维度）。
   *
   * <p>★ 用 baseModel 而不是"全部向量模型"：不同基础模型的维度本来就该不同（e5 是 384、bge-m3 是 1024）， 只有同一个 baseModel
   * 被登记成两个维度才是错的 —— 那会让两批向量落进不同的 {@code vec_embedding_<dim>} 表， 而检索时按模型自己的 dimension 选表，两边永远互相看不见。
   */
  java.util.List<Model> findByKindAndBaseModel(ModelKind kind, String baseModel);
}
