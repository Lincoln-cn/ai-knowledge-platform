package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 取模型的基础模型名（{@link RoutingEmbeddingAdapter.ModelIdentityPort} 的实现）。
 *
 * <p>★ 为什么用 JDBC 直查、而不是注入 {@code ModelService}： {@code ModelService} 属于 modelcatalog 的
 * <b>application</b> 层，而这个适配器在 <b>infrastructure</b> 层 —— 同模块内的方向是 infrastructure →
 * application，反了会绕开模块边界 （`ModuleBoundaryTest` 会拦）。这里只需要**一列**，直查比引一层应用服务更干净， 与 {@code
 * JdbcModelCapabilityAdapter} 是同一个形状。
 *
 * <p>★ 与 `contextWindow` 的容错口径**刻意不同**：
 *
 * <ul>
 *   <li>{@code contextWindow} 取不到 ⇒ 返回 {@code null}（那是"上限未知，回落配置"，可接受）；
 *   <li>{@code baseModel} 取不到 ⇒ <b>抛 404</b>。因为路由**必须**知道这批文本该用哪个模型算向量；
 *       取不到还继续，就等于"用一个猜出来的模型算向量"，而向量错了不会报错。
 * </ul>
 */
@Component
public class JdbcModelIdentityAdapter implements RoutingEmbeddingAdapter.ModelIdentityPort {

  private final JdbcTemplate jdbc;

  public JdbcModelIdentityAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String baseModelOf(UUID modelId) {
    try {
      String baseModel =
          jdbc.queryForObject(
              "select base_model from ai_model where id = ?", String.class, modelId);
      if (baseModel == null || baseModel.isBlank()) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "模型 " + modelId + " 没有登记基础模型名",
            "向量化必须知道用哪个模型算向量；请补齐该模型的 baseModel 再入库");
      }
      return baseModel;
    } catch (BusinessException rethrow) {
      throw rethrow;
    } catch (Exception notFound) {
      throw new BusinessException(
          ErrorCode.NOT_FOUND, "向量模型不存在：" + modelId, "它可能已被删除；请重新选择知识库绑定的向量模型");
    }
  }
}
