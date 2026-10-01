package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 向量化端口的实现（第 8 周 · 0.3.0）—— 走 OpenAI 兼容的上游 {@code /embeddings}。
 *
 * <p>★ 为什么适配器留在 {@code modelcatalog} 而不是让入库链自己调上游：<strong>解密 API key 是这里的职责</strong>。 密钥以 AES
 * 密文存在模型行上（{@code ApiKeyCipher}），入库链拿不到也不该拿到明文； 它只要"给我向量"。这条边界也是"换适配器不影响业务模块"（风险 R9）能成立的前提。
 *
 * <p>★ 失败一律给<strong>可读原因 + 下一步</strong>（{@code 08 §4.1}）：上游不可达不该显示成"参数不合法"， 那会让人去改一个根本没错的入参。
 */
@Component
public class UpstreamEmbeddingAdapter implements EmbeddingPort {

  private final ModelRepository models;
  private final ApiKeyCipher apiKeyCipher;
  private final UpstreamGateway gateway;

  public UpstreamEmbeddingAdapter(
      ModelRepository models, ApiKeyCipher apiKeyCipher, UpstreamGateway gateway) {
    this.models = models;
    this.apiKeyCipher = apiKeyCipher;
    this.gateway = gateway;
  }

  @Override
  public List<float[]> embed(UUID modelId, List<String> texts) {
    Model model =
        models
            .findById(modelId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.NOT_FOUND, "向量模型不存在", "它可能已被删除，请在知识库上重新绑定向量模型"));

    // ★ 类型不对要明确拒绝：把对话模型当向量模型用，拿回来的不是向量，早拒比晚错好
    if (model.kind() != ModelKind.EMBEDDING) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + model.name() + "」不是向量模型（kind=" + model.kind() + "）",
          "请在知识库上绑定一个 EMBEDDING 类型的模型");
    }

    try {
      return gateway.embed(model.baseModel(), texts, model.apiBase(), decryptKey(model));
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE, "向量化失败：" + e.getMessage(), "请确认模型服务可用（地址与密钥），或稍后重试");
    }
  }

  /** 解密模型的 API key（没存 key 时返回 null，由 {@link UpstreamGateway} 用默认 key）。 */
  private String decryptKey(Model model) {
    if (model.apiKeyCipher() == null || model.apiKeyIv() == null) {
      return null;
    }
    return apiKeyCipher.decrypt(model.apiKeyCipher(), model.apiKeyIv());
  }
}
