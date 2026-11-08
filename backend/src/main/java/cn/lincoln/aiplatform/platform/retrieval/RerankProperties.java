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
 *   <tr><td>{@code enabled}</td><td>{@code false}</td><td>总开关；默认关，理由见下</td></tr>
 *   <tr><td>{@code top-k}</td><td>{@code 5}</td><td>★ 只重排前 topK 个（离线实测，见 {@link RerankPort}）</td></tr>
 *   <tr><td>{@code model-path}</td><td>{@code models/bge-reranker-base/model.onnx}</td><td>ONNX 模型（约 1GB，不进仓库）</td></tr>
 *   <tr><td>{@code tokenizer-path}</td><td>{@code models/bge-reranker-base/tokenizer.json}</td><td>分词器</td></tr>
 * </table>
 *
 * <p>路径是相对路径时按"当前工作目录 → 上一层"解析（跑在 {@code backend/} 或仓库根都能找到， 见 {@code LocalOnnxEmbedding#resolve}
 * 的同款处理）。
 *
 * <p>★★ {@code enabled} 默认 <b>true</b>、{@code top-k} 默认 <b>10</b>（2026-11-08 全量实测后定的）：
 *
 * <table border="1">
 *   <caption>30 条同口径四组对照</caption>
 *   <tr><th>口径</th><th>B1</th><th>B3</th><th>doc_top1</th><th>单条耗时</th></tr>
 *   <tr><td>重排关</td><td>73.33%</td><td>63.33%</td><td>93.33%</td><td>899ms</td></tr>
 *   <tr><td>fp32 · 窗口 5</td><td>73.33%</td><td>63.33%</td><td>100.00%</td><td>4618ms</td></tr>
 *   <tr><td><b>fp32 · 窗口 10</b></td><td><b>80.00%</b></td><td><b>70.00%</b></td><td>100.00%</td><td>8498ms</td></tr>
 *   <tr><td>int8 · 窗口 10</td><td>80.00%</td><td>63.33%</td><td>100.00%</td><td>5670ms</td></tr>
 * </table>
 *
 * <p>全量 195 条：**B1 85.13% → 87.18%、B3 60.00% → 73.33%（+13.33 点）**。
 *
 * <p>★★★ <b>窗口才是那个旋钮，不是模型</b>：窗口 5 只能改「第 1 条指向错文档」（{@code doc_top1} 93→100），B1/B3 一动不动 ——
 * 因为**答案段压根不在前 5，重排看不到它**。窗口 10 才把 B1 与 B3 一起拉起来。
 *
 * <p>★ {@code int8} 不能用：小窗口上只掉 1 点，**窗口 10 上掉 6.67 点**（70 → 63.33）。
 *
 * <p>★ 代价（用户 2026-11-08 拍板选「质量优先」）：窗口 10 的 8.5s 是**检索本身**，加 LLM 生成约 1.2s ⇒ 单跳 约 9.7s，贴着 B4 的 10s
 * 门槛（`§二` 2026-11-08 起是「单跳 ≤10s、多跳 ≤20s」）。余量只有 0.3s，将来换真模型 会破 —— 那时要么上 GPU 执行提供器（1050 4GB 会与 ollama
 * 抢显存，见 {@code #282}），要么缩窗口。
 *
 * @param enabled 是否启用重排（默认开，见类注释）
 * @param topK 重排窗口（只重排前 topK 个候选；默认 10，见类注释）
 * @param modelPath ONNX 模型路径
 * @param tokenizerPath tokenizer.json 路径
 */
@ConfigurationProperties(prefix = "app.retrieval.rerank")
public record RerankProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10") int topK,
    @DefaultValue("models/bge-reranker-base/model.onnx") String modelPath,
    @DefaultValue("models/bge-reranker-base/tokenizer.json") String tokenizerPath) {

  /**
   * 全默认值实例 —— 给"重排与被测行为无关"的测试夹具用。
   *
   * @return 全默认值配置
   */
  public static RerankProperties defaults() {
    return new RerankProperties(
        true, 10, "models/bge-reranker-base/model.onnx", "models/bge-reranker-base/tokenizer.json");
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
