package cn.lincoln.aiplatform.modelcatalog.application;

import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/**
 * 重排（cross-encoder）模型的<strong>只读</strong>状态（`#352`）。
 *
 * <h3>为什么它需要存在</h3>
 *
 * <p>重排不是注册模型：`ai_model` 里没有这一行，`ModelKind` 里也没有 `RERANK`。 它由 `app.retrieval.rerank.*` 四个键 +
 * 两个本地文件（约 1GB 的 onnx 与 tokenizer.json）决定。 于是"检索还有一条重排腿、它的窗口是 10、int8
 * 不能用"这件事<strong>只活在设计册里</strong>， 界面上既看不见、也改不了 —— 用户 2026-11-18 的原话是"reank 本地模型不在 web
 * 页面中的模型管理中可以看到"。
 *
 * <p>★ 刻意做成**只读**：它不是可编辑的模型。做成能改的假模型会让人以为"换个模型名就能换重排模型"， 而实际换模型要换 onnx 文件、重启、并重取 B3 读数（见 {@link
 * RerankProperties} 类注释里的四组对照）。 以后若真要把它变成注册模型，那是 `#312` 拍板后的另一档（那一档还要动建档校验与能力探测语义）。
 */
@Service
public class RerankStatusService {

  private final RerankProperties properties;

  public RerankStatusService(RerankProperties properties) {
    this.properties = properties;
  }

  /** 当前进程里重排模型的真实状态（读的是运行期配置 + 磁盘，不查库）。 */
  public RerankStatus status() {
    boolean enabled = properties.enabled();
    Path modelPath = resolve(properties.modelPath());
    Path tokenizerPath = resolve(properties.tokenizerPath());
    boolean filesPresent = Files.isRegularFile(modelPath) && Files.isRegularFile(tokenizerPath);
    String reason;
    if (!enabled) {
      reason = "重排未启用（app.retrieval.rerank.enabled=false）";
    } else if (filesPresent) {
      reason = null;
    } else {
      reason = "模型文件缺失：" + modelPath;
    }
    return new RerankStatus(
        enabled,
        modelName(modelPath),
        modelPath.toString(),
        tokenizerPath.toString(),
        properties.topK(),
        weights(modelPath),
        filesPresent,
        reason,
        properties.listwiseEnabled(),
        properties.listwiseTopK(),
        properties.forwardBatch());
  }

  /**
   * 相对路径按"当前工作目录 → 上一层"解析。
   *
   * <p>与 {@code LocalOnnxEmbedding#resolve} 同款处理：后端可能从 {@code backend/} 也可能是仓库根被启动，
   * 两种工作目录下都要能找到默认的相对路径。
   */
  private static Path resolve(String configured) {
    Path configuredPath = Path.of(configured);
    if (configuredPath.isAbsolute() || Files.exists(configuredPath)) {
      return configuredPath;
    }
    Path fromParent = Path.of("..").resolve(configured).normalize();
    return Files.exists(fromParent) ? fromParent : configuredPath;
  }

  /** 模型名取目录名（`models/bge-reranker-base/model.onnx` → `bge-reranker-base`）。 */
  private static String modelName(Path modelPath) {
    Path parent = modelPath.getParent();
    String name =
        parent == null
            ? null
            : parent.getFileName() == null ? null : parent.getFileName().toString();
    return name == null || name.isBlank() ? modelPath.getFileName().toString() : name;
  }

  /**
   * 权重量化形态。
   *
   * <p>★ 只能从文件名推断（onnx 本身不带这个标记），所以它显示的是"文件名说的是什么"，不是"内容确实是什么"。 这条限制写在界面上更好 ——
   * 但**不做成可编辑字段**：换权重是换文件，不是改个字符串（见类注释）。
   */
  private static String weights(Path modelPath) {
    String fileName = modelPath.getFileName() == null ? "" : modelPath.getFileName().toString();
    return fileName.toLowerCase().contains("int8") ? "int8" : "fp32";
  }

  /**
   * 重排模型的只读状态。
   *
   * @param enabled 总开关（{@code app.retrieval.rerank.enabled}）
   * @param model 目录名，如 {@code bge-reranker-base}
   * @param modelPath 解析后的 onnx 路径（相对路径已按工作目录修正）
   * @param topK 重排窗口（★ 窗口才是那个旋钮，不是模型 —— 见 {@link RerankProperties}）
   * @param weights 从文件名推断的量化形态（{@code fp32} / {@code int8}）
   * @param filesPresent onnx 与 tokenizer 是否都在
   * @param reason 可用时为 null；否则是「未启用 / 文件缺失」
   * @param listwiseEnabled cross-encoder 之后是否再让对话模型排一次
   * @param listwiseTopK 交给 listwise 的候选数
   * @param forwardBatch 单次前向条数（0 = 整个窗口一次算完）
   */
  public record RerankStatus(
      boolean enabled,
      String model,
      String modelPath,
      String tokenizerPath,
      int topK,
      String weights,
      boolean filesPresent,
      String reason,
      boolean listwiseEnabled,
      int listwiseTopK,
      int forwardBatch) {}
}
