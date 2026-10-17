package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 向量化的**路由**：本地 ONNX（进程内）还是上游服务（HTTP）。
 *
 * <h3>判定规则（只有一条，且按模型名）</h3>
 *
 * <p>模型的基础模型名 == 本地实现服务的那个名字（默认 {@code bge-small-zh-v1.5}） ⇒ 走 {@link LocalOnnxEmbedding}；否则走
 * {@link UpstreamEmbeddingAdapter}。
 *
 * <p>★ 为什么按**模型名**而不是 {@code supplier}：一个用户完全可以把 Ollama 托管的 {@code bge-m3} 标成 {@code
 * LOCAL_INFERENCE}（它确实跑在本机）。按 supplier 判会让它 被误判成本地 ONNX、然后加载失败 —— 而那个症状看起来像"模型坏了"。
 *
 * <h3>★★ 绝静默回退</h3>
 *
 * <p>本地模型没就绪（文件缺失 / 初始化失败）时，**不**改走上游：两个模型的向量空间不同， 混用之后检索**照样返回结果**，只是全部不对 —— 这是本仓最贵的失败形态。
 * 所以这里的是"抛可读错误并停下"，不是"换一条路继续"。
 */
@Primary
@Component
public class RoutingEmbeddingAdapter implements EmbeddingPort {

  private static final Logger log = LoggerFactory.getLogger(RoutingEmbeddingAdapter.class);

  private final LocalOnnxEmbedding local;
  private final UpstreamEmbeddingAdapter upstream;
  private final ModelIdentityPort identities;

  public RoutingEmbeddingAdapter(
      LocalOnnxEmbedding local, UpstreamEmbeddingAdapter upstream, ModelIdentityPort identities) {
    this.local = local;
    this.upstream = upstream;
    this.identities = identities;
  }

  @Override
  public List<float[]> embed(UUID modelId, List<String> texts) {
    String baseModel = identities.baseModelOf(modelId);
    if (!local.serves(baseModel)) {
      return upstream.embed(modelId, texts);
    }
    // ★ 只在这一条分支里用本地实现；失败会抛 BusinessException（调用方据此把入库停在"可读原因"上）
    if (!local.ready()) {
      log.info("本地向量模型尚未初始化，本次调用触发加载（model={}）", local.modelPath());
    }
    return local.embed(texts);
  }

  /** 只在"取模型名"这一件事上需要模型目录 —— 用一个极小的端口，避免把整个 {@code ModelService} 拖进 infrastructure（那会绕开模块边界）。 */
  public interface ModelIdentityPort {

    /**
     * 取模型的基础模型名（{@code baseModel}）。
     *
     * @param modelId 模型 id
     * @return 基础模型名；模型不存在时抛可读的 404
     */
    String baseModelOf(UUID modelId);
  }
}
