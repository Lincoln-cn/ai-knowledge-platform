package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
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

  /**
   * 已经提醒过「这个模型没登记维度」的模型 id —— 同一个模型只打一次（`#348`）。
   *
   * <p>★ 同一份文档是逐段调用的（70 段 = 70 次），每段都打一行「本轮跳过维度比对」等于把这行变成噪音；
   * 而去重之后它只在"这个模型第一次入库"时出现一次，正好是有人会去看日志的时刻。
   */
  private final java.util.Set<UUID> warnedMissingDimension =
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
    List<float[]> vectors;
    if (!local.serves(baseModel)) {
      warnIfItsFilesAreRightHere(baseModel);
      vectors = upstream.embed(modelId, texts);
    } else {
      // ★ 只在这一条分支里用本地实现；失败会抛 BusinessException（调用方据此把入库停在"可读原因"上）
      if (!local.ready()) {
        log.info("本地向量模型尚未初始化，本次调用触发加载（model={}）", local.modelPath());
      }
      vectors = local.embed(texts);
    }
    requireDeclaredDimension(modelId, baseModel, vectors);
    return vectors;
  }

  /**
   * 这一批向量的维度必须与模型**登记的**维度一致（`#348`）。
   *
   * <h3>★ 为什么必须在这里拦，而不是在写入端</h3>
   *
   * <p>写入端（{@code JdbcVectorIndex#upsert}）拿不到登记维度 —— 它只看得见"一条向量有多长"，而表名正是按这个长度拼的 （{@code
   * vec_embedding_<维度>}）。于是"登记 1024、实际返回 768"会**静默落进 768 表**：功能是自洽的（写与查同口径）， 走查照样全过、检索照样有结果，
   * 但两批向量分居两张表、永远互相看不见 —— 换真模型重算后才以"检索结果少一半"的形态暴露。 这正是本类 javadoc 里那句"最贵的失败形态"，只不过换了一条分支。
   *
   * <p>★ <strong>整批一起失败</strong>：调用方是"逐条 upsert"的，所以在返回之前抛，一条都不会写进去。半批写进去比全不写更糟 —— 那会留下两份向量空间并存的状态。
   *
   * @param modelId 模型 id
   * @param baseModel 基础模型名（报错时点名用）
   * @param vectors 这一批的向量
   * @throws BusinessException 维度与登记不符（可读 400，写清登记值与实际值）
   */
  private void requireDeclaredDimension(UUID modelId, String baseModel, List<float[]> vectors) {
    if (vectors == null || vectors.isEmpty()) {
      return;
    }
    Integer declared = identities.dimensionOf(modelId);
    int actual = vectors.get(0).length;
    if (declared == null) {
      // ★「不知道」不能悄悄变成「通过」：登记维度是空的，就明说这一轮没校验（同一模型只说一次）
      if (warnedMissingDimension.add(modelId)) {
        log.warn(
            "模型「{}」（{}）没有登记维度，本轮跳过维度比对 —— 这次返回 {} 维。请补上 ai_model.dimension，"
                + "否则上游换了维度也不会被发现（#348）",
            baseModel,
            modelId,
            actual);
      }
      return;
    }
    for (float[] vector : vectors) {
      if (vector.length != declared) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "向量维度与模型登记不符：模型「"
                + baseModel
                + "」（"
                + modelId
                + "）登记 "
                + declared
                + " 维，这一批返回的是 "
                + vector.length
                + " 维",
            "向量按维度分表存放（vec_embedding_<维度>），写进另一张表之后这批向量与已有向量永远互相查不到。"
                + "请把该模型的 dimension 改成上游实际返回的维度（并在「模型」页重建向量），或让上游按登记维度返回");
      }
    }
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

    /**
     * 取模型**登记的向量维度**（{@code ai_model.dimension}）。
     *
     * <p>★ 取不到返回 {@code null}，与 {@code contextWindow} 同口径：**未知不等于错**（一列没填不该让整个入库停下来）。 但未知时调用方会打一行
     * WARN 说明"这一轮没校验" —— 让"没填"与"填对了"在日志里区分得开（`#348`）。
     *
     * @param modelId 模型 id
     * @return 登记维度；未知时 {@code null}
     */
    Integer dimensionOf(UUID modelId);
  }
}
