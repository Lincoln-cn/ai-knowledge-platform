package cn.lincoln.aiplatform.platform.retrieval;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 重排配置（{@code app.retrieval.rerank.*}）。
 *
 * <h3>四个键与默认值</h3>
 *
 * <table border="1">
 *   <caption>配置键</caption>
 *   <tr><th>键</th><th>默认</th><th>含义</th></tr>
 *   <tr><td>{@code enabled}</td><td>{@code true}</td><td>总开关；关掉 = 一次都不调重排（省 2.4s/条）</td></tr>
 *   <tr><td>{@code top-k}</td><td>{@code 5}</td><td>★ 只重排前 topK 个（离线实测，见 {@link RerankPort}）</td></tr>
 *   <tr><td>{@code model-path}</td><td>{@code models/bge-reranker-base/model.onnx}</td><td>ONNX 模型（约 1GB，不进仓库）</td></tr>
 *   <tr><td>{@code tokenizer-path}</td><td>{@code models/bge-reranker-base/tokenizer.json}</td><td>分词器</td></tr>
 * </table>
 *
 * <p>路径是相对路径时按"当前工作目录 → 上一层"解析（跑在 {@code backend/} 或仓库根都能找到， 见 {@code LocalOnnxEmbedding#resolve}
 * 的同款处理）。
 *
 * @param enabled 是否启用重排
 * @param topK 重排窗口（只重排前 topK 个候选）
 * @param modelPath ONNX 模型路径
 * @param tokenizerPath tokenizer.json 路径
 */
@ConfigurationProperties(prefix = "app.retrieval.rerank")
public record RerankProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("5") int topK,
    @DefaultValue("models/bge-reranker-base/model.onnx") String modelPath,
    @DefaultValue("models/bge-reranker-base/tokenizer.json") String tokenizerPath) {

  /**
   * 全默认值实例（{@code enabled=true / topK=5} + 默认模型路径）—— 给"重排与被测行为无关"的测试夹具用。
   *
   * @return 全默认值配置
   */
  public static RerankProperties defaults() {
    return new RerankProperties(
        true, 5, "models/bge-reranker-base/model.onnx", "models/bge-reranker-base/tokenizer.json");
  }

  /**
   * 构造即校验（同 {@link RetrievalBudgetProperties} 的做法）：值的合法性属于<strong>这个类型</strong>， "直接 {@code
   * new}"的测试与"Boot 绑定"的生产走同一条检查。
   *
   * @throws IllegalArgumentException 任一值不合法
   */
  public RerankProperties {
    if (topK < 1) {
      throw new IllegalArgumentException(
          "app.retrieval.rerank.top-k 必须 ≥ 1（只重排前 topK 个候选）：实际 " + topK);
    }
    Objects.requireNonNull(modelPath, "app.retrieval.rerank.model-path 不许为空");
    Objects.requireNonNull(tokenizerPath, "app.retrieval.rerank.tokenizer-path 不许为空");
  }
}
