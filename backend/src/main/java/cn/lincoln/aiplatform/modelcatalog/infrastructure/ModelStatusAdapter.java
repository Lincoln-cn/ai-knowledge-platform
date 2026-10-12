package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.platform.model.ModelStatusPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link ModelStatusPort} 的实现（第 15 周 · 0.4.3）。
 *
 * <p>★ <strong>"已加载"只认 {@link ModelStatus#LOADED}</strong>，不认 {@code enabled}： {@code enabled}
 * 是"平台启用位"，而发布校验关心的是"这次调用真的能成吗" —— 一个 {@code 加载失败} 的模型也可能 {@code enabled=true}， 拿它去发布，用户提问时才报错（这正是
 * AS-I2 要提前拦住的那个故障）。
 *
 * <p>★ 软删的模型当作不存在：返回 {@code empty} 让调用方说"模型不存在（可能已被删除）"， 而不是说"模型没加载"——后者会把排查方向指错。
 */
@Component
public class ModelStatusAdapter implements ModelStatusPort {

  private final ModelRepository models;

  public ModelStatusAdapter(ModelRepository models) {
    this.models = models;
  }

  @Override
  public Optional<ModelSnapshot> find(UUID modelId) {
    if (modelId == null) {
      return Optional.empty();
    }
    return models.findById(modelId).map(ModelStatusAdapter::snapshot);
  }

  private static ModelSnapshot snapshot(Model model) {
    return new ModelSnapshot(model.id(), model.name(), model.status() == ModelStatus.LOADED);
  }
}
