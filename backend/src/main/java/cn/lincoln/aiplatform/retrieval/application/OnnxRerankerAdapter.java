package cn.lincoln.aiplatform.retrieval.application;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import cn.lincoln.aiplatform.platform.retrieval.NoopRerankerAdapter;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import jakarta.annotation.PreDestroy;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * cross-encoder 重排实现（{@code BAAI/bge-reranker-base}，ONNX + DJL tokenizers）。
 *
 * <h3>★ 失败必须降级、绝不能让检索不可用</h3>
 *
 * <p>模型文件缺失 / 初始化失败 / 推理异常 ⇒ <b>返回原序</b>（显式委托 {@link NoopRerankerAdapter#INSTANCE}）+ {@code
 * log.warn} <b>同一个原因只打一次</b>（{@link RoutingEmbeddingAdapter} 的同款做法： 逐段调用会刷几十行，反而把真正的问题淹掉）。
 * 这与向量那一路"绝不静默回退 上游"（{@code LocalOnnxEmbedding}）的口径<b>相反</b>： 重排是增益件（坏了就退回 RRF
 * 原序，检索照样能用），向量是命脉件（换了向量空间就全错）。
 *
 * <h3>★ 懒加载与"一个 JVM 一个 OrtEnvironment"</h3>
 *
 * <p>照 {@code LocalOnnxEmbedding} 的实测结论：{@code OrtEnvironment} 是进程级单例（{@code getEnvironment()}
 * 全局一份， {@code close()} 是 no-op），<b>能卸载的只有 {@code OrtSession} 这一层</b> —— 所以 {@link PreDestroy} 只关
 * session 与 tokenizer， 不关 environment。 模型（约 1GB）在第一次调用时才加载：一次 {@code createSession}
 * 可能要数秒到数十秒，之后的调用只有推理耗时（离线实测 2.4s/条）。
 *
 * <h3>模型 IO（Python 端已验证）</h3>
 *
 * <ul>
 *   <li>输入 {@code input_ids} / {@code attention_mask}（int64 {@code [batch, seq]}）；{@code
 *       token_type_ids} <b>可选</b> —— 用 {@code session.getInputNames().contains(...)} 判定有没有再喂；
 *   <li>输出 logits {@code [batch, 1]}：取 {@code [:, 0]} 再 sigmoid 当分数；
 *   <li>{@code max_length} 512，{@code pad_id} 0 —— 批内按最长补齐（pad 补 0、mask 补 0），单次前向 {@value
 *       #FORWARD_BATCH} 条。
 * </ul>
 */
@Component
public class OnnxRerankerAdapter implements RerankPort {

  private static final Logger log = LoggerFactory.getLogger(OnnxRerankerAdapter.class);

  /** 单次前向的批量条数（离线评测就是按 5 条一批量的）。 */
  private static final int FORWARD_BATCH = 5;

  /** 模型的最大输入长度（超过截断）：{@code bge-reranker-base} 是 512。 */
  private static final int MAX_LENGTH = 512;

  private static final String INPUT_IDS = "input_ids";
  private static final String ATTENTION_MASK = "attention_mask";
  private static final String TOKEN_TYPE_IDS = "token_type_ids";

  private final RerankProperties properties;

  /** 已警告过的原因键（同一个原因只打一次 warn，见类注释）。 */
  private final Set<String> warnedReasons = ConcurrentHashMap.newKeySet();

  /** 懒加载的推理组件（null = 未加载）；初始化失败记进 {@link #loadFailure}。 */
  private final AtomicReference<Bundle> bundle = new AtomicReference<>();

  private final AtomicReference<String> loadFailure = new AtomicReference<>();

  /** 一次加载好的推理组件。 */
  private record Bundle(
      OrtEnvironment environment,
      OrtSession session,
      HuggingFaceTokenizer tokenizer,
      boolean needsTokenTypeIds) {}

  public OnnxRerankerAdapter(RerankProperties properties) {
    this.properties = properties;
  }

  /**
   * 重排前 {@code topK} 个候选（契约见 {@link RerankPort}）；任何失败返回原序。
   *
   * @param query 原始问题
   * @param candidateIds 候选（已按融合分数排好）
   * @param texts segmentId → 正文（缺项按空串）
   * @param topK 重排窗口
   * @return 重排后的排列；失败 = 原序
   */
  @Override
  public List<UUID> rerank(
      String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
    if (candidateIds == null || candidateIds.size() < 2 || topK < 2) {
      // 一条或零条没什么可排的（含 topK=1 的退化配置）：直接原序，连模型都不用碰
      return candidateIds;
    }
    int window = Math.min(topK, candidateIds.size());
    List<UUID> head = new ArrayList<>(candidateIds.subList(0, window));
    try {
      Bundle ready = requireBundle();
      double[] scores = score(ready, query, head, texts);
      // ★ 稳定排序：同分保持原序（Arrays.sort(T[], Comparator) 是稳定排序）
      Integer[] order = new Integer[window];
      for (int i = 0; i < window; i++) {
        order[i] = i;
      }
      Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));
      List<UUID> result = new ArrayList<>(candidateIds.size());
      for (int idx : order) {
        result.add(head.get(idx));
      }
      // 窗口外保持原序（重排只决定"谁进答案"，不淘汰任何候选）
      result.addAll(candidateIds.subList(window, candidateIds.size()));
      return result;
    } catch (Exception failure) {
      warnOnce(
          "inference:" + failure.getClass().getSimpleName(),
          "cross-encoder 重排失败，本次降级返回原序（同一原因不再重复告警）：{}",
          failure);
      return NoopRerankerAdapter.INSTANCE.rerank(query, candidateIds, texts, topK);
    }
  }

  /** 懒加载：第一次调用时才建 {@code OrtSession}；失败缓存下来（重启才重试）。 */
  private Bundle requireBundle() throws Exception {
    Bundle ready = bundle.get();
    if (ready != null) {
      return ready;
    }
    synchronized (this) {
      ready = bundle.get();
      if (ready != null) {
        return ready;
      }
      String failure = loadFailure.get();
      if (failure != null) {
        throw new IllegalStateException(failure);
      }
      try {
        Path model = resolve(properties.modelPath());
        Path tokenizerPath = resolve(properties.tokenizerPath());
        if (!Files.isRegularFile(model)) {
          throw new FileNotFoundException("重排模型文件不存在：" + model);
        }
        if (!Files.isRegularFile(tokenizerPath)) {
          throw new FileNotFoundException("重排 tokenizer 文件不存在：" + tokenizerPath);
        }
        // ★ 一个 JVM 只有一个 OrtEnvironment（getEnvironment() 全局一份）；能卸载的只有 OrtSession 这一层
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        boolean needsTokenTypeIds;
        OrtSession session;
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
          session = environment.createSession(model.toString(), options);
        } finally {
          options.close();
        }
        needsTokenTypeIds = session.getInputNames().contains(TOKEN_TYPE_IDS);
        HuggingFaceTokenizer tokenizer =
            HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optMaxLength(MAX_LENGTH)
                .optTruncation(true)
                .optAddSpecialTokens(true)
                .optPadding(false)
                .build();
        Bundle loaded = new Bundle(environment, session, tokenizer, needsTokenTypeIds);
        bundle.set(loaded);
        log.info(
            "cross-encoder 重排已加载：{}（token_type_ids={}，窗口 topK={}）",
            model,
            needsTokenTypeIds,
            properties.topK());
        return loaded;
      } catch (Exception initFailure) {
        String reason =
            "重排模型加载失败（app.retrieval.rerank.model-path={}）：{}"
                .formatted(properties.modelPath(), initFailure.getMessage());
        loadFailure.set(reason);
        warnOnce(
            "load:" + initFailure.getClass().getSimpleName(),
            "cross-encoder 重排加载失败，本次起降级返回原序（同一原因不再重复告警）：{}",
            initFailure);
        throw new IllegalStateException(reason, initFailure);
      }
    }
  }

  /** 按 {@value #FORWARD_BATCH} 条一批算分。 */
  private double[] score(Bundle ready, String query, List<UUID> head, Map<UUID, String> texts)
      throws Exception {
    double[] all = new double[head.size()];
    for (int from = 0; from < head.size(); from += FORWARD_BATCH) {
      int to = Math.min(from + FORWARD_BATCH, head.size());
      double[] batch = forward(ready, query, head, texts, from, to);
      System.arraycopy(batch, 0, all, from, batch.length);
    }
    return all;
  }

  /** 一次前向：批内按最长补齐（pad 补 0、mask 补 0），输出 logits [batch,1] 取 [:,0] 再 sigmoid。 */
  private double[] forward(
      Bundle ready, String query, List<UUID> head, Map<UUID, String> texts, int from, int to)
      throws Exception {
    int size = to - from;
    long[][] inputIds = new long[size][];
    long[][] attentionMask = new long[size][];
    long[][] tokenTypeIds = ready.needsTokenTypeIds() ? new long[size][] : null;
    int seqLen = 0;
    for (int i = 0; i < size; i++) {
      String passage = texts.getOrDefault(head.get(from + i), "");
      Encoding encoded = ready.tokenizer().encode(query, passage);
      long[] ids = clamp(encoded.getIds());
      long[] mask = clamp(encoded.getAttentionMask());
      long[] types = clamp(encoded.getTypeIds());
      inputIds[i] = ids;
      attentionMask[i] = mask;
      if (tokenTypeIds != null) {
        tokenTypeIds[i] = types;
      }
      seqLen = Math.max(seqLen, ids.length);
    }
    // pad 到批内最长（pad_id = 0，mask = 0 —— 对应 tokenizer 的 [PAD]）
    inputIds = pad(inputIds, seqLen);
    attentionMask = pad(attentionMask, seqLen);
    if (tokenTypeIds != null) {
      tokenTypeIds = pad(tokenTypeIds, seqLen);
    }

    double[] scores = new double[size];
    try (OnnxTensor idsTensor = OnnxTensor.createTensor(ready.environment(), inputIds);
        OnnxTensor maskTensor = OnnxTensor.createTensor(ready.environment(), attentionMask)) {
      Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
      inputs.put(INPUT_IDS, idsTensor);
      inputs.put(ATTENTION_MASK, maskTensor);
      try (OnnxTensor typesTensor =
          tokenTypeIds == null
              ? null
              : OnnxTensor.createTensor(ready.environment(), tokenTypeIds)) {
        if (typesTensor != null) {
          inputs.put(TOKEN_TYPE_IDS, typesTensor);
        }
        try (OrtSession.Result result = ready.session().run(inputs)) {
          float[][] logits = (float[][]) result.get(0).getValue();
          for (int i = 0; i < size; i++) {
            scores[i] = 1.0 / (1.0 + Math.exp(-logits[i][0]));
          }
        }
      }
    }
    return scores;
  }

  /** 防御性截断：tokenizer 已按 512 截断，这里再兜一层（导出形状不同也不至于炸）。 */
  private static long[] clamp(long[] values) {
    return values.length > MAX_LENGTH ? Arrays.copyOf(values, MAX_LENGTH) : values;
  }

  /** 批内补齐到 {@code seqLen}：补 0（pad_id = 0）。 */
  private static long[][] pad(long[][] batch, int seqLen) {
    for (int i = 0; i < batch.length; i++) {
      if (batch[i].length < seqLen) {
        batch[i] = Arrays.copyOf(batch[i], seqLen);
      }
    }
    return batch;
  }

  /**
   * 相对路径按"当前工作目录 → 上一层"解析（照 {@code LocalOnnxEmbedding#resolve}）： 跑在 {@code
   * backend/}（surefire）或仓库根（IDE）都能找到 {@code models/}。
   */
  private static Path resolve(String configured) {
    Path path = Path.of(configured);
    if (path.isAbsolute() || Files.isRegularFile(path)) {
      return path;
    }
    Path parent = Path.of("").toAbsolutePath().getParent();
    Path fallback = parent == null ? null : parent.resolve(configured);
    return fallback != null && Files.isRegularFile(fallback) ? fallback : path;
  }

  /** 同一个原因只打一次 warn（逐段调用会刷几十行，反而把真正的问题淹掉）。 */
  private void warnOnce(String reasonKey, String message, Exception failure) {
    if (!warnedReasons.add(reasonKey)) {
      return;
    }
    log.warn(message, failure.getMessage(), failure);
  }

  /** 只关 {@code OrtSession} 与 tokenizer —— {@code OrtEnvironment} 是进程级单例，不关（见类注释）。 */
  @PreDestroy
  void shutdown() {
    Bundle ready = bundle.getAndSet(null);
    if (ready == null) {
      return;
    }
    try {
      ready.session().close();
    } catch (Exception failure) {
      log.warn("关闭重排 OrtSession 失败（忽略）：{}", failure.getMessage());
    }
    try {
      ready.tokenizer().close();
    } catch (Exception failure) {
      log.warn("关闭重排 tokenizer 失败（忽略）：{}", failure.getMessage());
    }
  }

  /** 测试钩子：诊断用，返回是否已加载（不触发加载）。 */
  boolean loadedForTest() {
    return bundle.get() != null;
  }

  /** 测试钩子：诊断用，返回是否已加载失败（不触发加载）。 */
  String loadFailureForTest() {
    return loadFailure.get();
  }
}
