package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ModelCapabilityPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量模型字符上限解析器（第 7 周 · 0.2.2 批 2b）。
 *
 * <p>★ 独立成组件的原因：{@code resolve} 必须跑在 {@code NOT_SUPPORTED} 传播里， 否则内部的 {@code @Transactional}
 * 调用抛异常时会把外层事务标记为 rollback-only。 放在调用方里自调用绕过代理，注解不生效 —— 必须是独立 bean。
 *
 * <p>★ 取值顺序（领域配置，不是常量）：
 *
 * <ol>
 *   <li>KB 绑定的向量模型（{@code embeddingModelId} → {@code Model.contextWindow}）<strong>有则用它</strong> ——
 *       口径：把模型窗口数当字数上限，近似
 *   <li>否则回落配置 {@code app.segmentation.max-chars}，默认 1200 —— 与前端 mock 的 {@code MODEL_LIMIT} 一致
 * </ol>
 */
@Component
public class MaxCharsResolver {

  private final KnowledgeBaseService knowledgeBases;
  private final ModelCapabilityPort modelCapability;

  public MaxCharsResolver(
      KnowledgeBaseService knowledgeBases, ModelCapabilityPort modelCapability) {
    this.knowledgeBases = knowledgeBases;
    this.modelCapability = modelCapability;
  }

  /**
   * 解析向量模型字符上限。
   *
   * <p>★ {@code NOT_SUPPORTED}：内部调用若抛异常，不把外层事务标记为 rollback-only —— 这里只是尽力查一下模型上限，查不到就回落配置。
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int resolve(UUID kbId, int fallback) {
    try {
      var base = knowledgeBases.get(kbId);
      UUID modelId = UUID.fromString(base.embeddingModelId());
      Integer window = modelCapability.contextWindow(modelId);
      if (window != null && window > 0) {
        return window;
      }
    } catch (Exception e) {
      // 模型不存在 / KB 无绑定 / 上游不可达 ⇒ 回落配置，不阻断
    }
    return fallback;
  }
}
