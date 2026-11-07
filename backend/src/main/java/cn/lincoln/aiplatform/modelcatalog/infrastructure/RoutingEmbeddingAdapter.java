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

  /**
   * 已经为哪些 baseModel 打过「模型文件在、但名字对不上」的 WARN —— 同一名字只打一次。
   *
   * <p>★ 为什么必须去重：向量化是逐段调用的（一份 70 段的文档就是 70 次），若每次都打， 那行 WARN 会把自己淹没在 70 行里，等于没有。
   */
  private final java.util.Set<String> warnedModelFiles =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  public RoutingEmbeddingAdapter(
      LocalOnnxEmbedding local, UpstreamEmbeddingAdapter upstream, ModelIdentityPort identities) {
    this.local = local;
    this.upstream = upstream;
    this.identities = identities;
  }

  /**
   * 路由走了上游，但这个基础模型的模型文件就在磁盘上 ⇒ 至少留一行 WARN（#305 缺口 A）。
   *
   * <p>那种情形下用户看到的是「向量化成功、检索有结果、只是全不对」，而日志里一个字都没有 —— 本项目最贵的失败形态就是这种「看起来在做、实际没生效」。★
   * 这里只报不平移：改路由会让「填错名字」 变成「莫名走了本地」，而那个模型行也可能确实指向一台真上游。
   *
   * @param baseModel 模型行里的基础模型名
   */
  private void warnIfItsFilesAreRightHere(String baseModel) {
    if (baseModel == null || !local.hasModelFilesFor(baseModel)) {
      return;
    }
    if (!warnedModelFiles.add(baseModel.trim().toLowerCase(java.util.Locale.ROOT))) {
      return;
    }
    log.warn(
        "模型的基础模型名是「{}」，磁盘上 models/{}/ 里也有完整的本地模型文件 —— 但本地 ONNX 只服务「{}」"
            + "（app.embedding.local.model-name）。本轮已按名字走上游；若你以为在用本地模型，"
            + "把 app.embedding.local.model-name 改成「{}」并重启（#305）",
        baseModel,
        baseModel.trim(),
        local.servedModelName(),
        baseModel.trim());
  }

  @Override
  public List<float[]> embed(UUID modelId, List<String> texts) {
    String baseModel = identities.baseModelOf(modelId);
    if (!local.serves(baseModel)) {
      warnIfItsFilesAreRightHere(baseModel);
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
