package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import ai.onnxruntime.OrtException;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * 进程内 ONNX 向量化（`#172`）—— **不再需要 Ollama 那个 11434 进程**。
 *
 * <h3>为什么要有它</h3>
 *
 * <p>在此之前向量化只有一条路：HTTP 打到 Ollama（或测试桩）。后果是一个很具体的开发痛点： <strong>只起后端时，文档解析完永远停在 {@code
 * PENDING_EMBEDDING}</strong>，检索永远 0 命中， 而 `/actuator/health` 一直是 {@code UP} —— "进程活着"与"向量化可用"是两件事。
 *
 * <h3>★ 生命周期的两条官方事实（决定了本类只能这么写）</h3>
 *
 * <ol>
 *   <li><b>一个 JVM 只能有一个 {@code OrtEnvironment}</b>（官方 javadoc 原话）；
 *   <li>它的 {@code close()} <b>自 ONNX Runtime 1.11 起是 no-op</b>（由 JVM shutdown hook 关闭）。
 * </ol>
 *
 * <p>⇒ 「卸载」只能发生在 <b>{@code OrtSession}</b> 这一层（{@link TransformersEmbeddingModel#close()}），
 * 环境与进程同生共死。★ 因此本类<b>不</b>做"多模型热切换"：本地只留一个模型， 多模型交给服务侧（HTTP）—— 反复 create/close 会话会让 native
 * 内存阶梯式上涨（不是泄漏， 但用户看起来就是泄漏）。
 *
 * <h3>★★ 三条不变量</h3>
 *
 * <ol>
 *   <li><b>懒加载</b>：第一次用时才初始化 ONNX 会话 —— 不让"启动"被几百毫秒到几十秒的模型加载拖住；
 *   <li><b>失败必须是可读的 {@code BusinessException}</b>，不是 500、也不是"返回空向量"。 模型文件缺失/加载失败时，调用方要看到"为什么"；
 *   <li><b>绝不静默回退</b>到上游（向量空间不同，混用后检索"照样有结果，只是全错"）。
 * </ol>
 */
@Component
public class LocalOnnxEmbedding {

  private static final Logger log = LoggerFactory.getLogger(LocalOnnxEmbedding.class);

  /** 是否启用本地 ONNX。关掉它就完全回到上游那条路（用于临时排查）。 */
  private final boolean enabled;

  /** ONNX 模型文件路径（**不进仓库**，见 `models/README.md`）。 */
  private final String modelPath;

  /** tokenizer.json 路径。 */
  private final String tokenizerPath;

  /** ★ 本类只服务这一个模型名；其它模型名一律走上游。 */
  private final String servedModelName;

  /** 懒加载的模型；未初始化时为 {@code null}。 */
  private final AtomicReference<TransformersEmbeddingModel> model = new AtomicReference<>();

  /** 加载失败的原因（一旦失败就保留，避免每次调用都重试一遍昂贵的初始化）。 */
  private final AtomicReference<String> loadFailure = new AtomicReference<>();

  public LocalOnnxEmbedding(
      @Value("${app.embedding.local.enabled:true}") boolean enabled,
      @Value("${app.embedding.local.model-path:models/multilingual-e5-small/model_quantized.onnx}")
          String modelPath,
      @Value("${app.embedding.local.tokenizer-path:models/multilingual-e5-small/tokenizer.json}")
          String tokenizerPath,
      @Value("${app.embedding.local.model-name:multilingual-e5-small}") String servedModelName) {
    this.enabled = enabled;
    this.modelPath = modelPath;
    this.tokenizerPath = tokenizerPath;
    this.servedModelName = servedModelName;
  }

  /**
   * 这个模型名是否由本地实现服务。
   *
   * <p>★ 用**模型名**做判定，而不是"supplier == LOCAL_INFERENCE"：一个用户可以把 `bge-m3` 也标成
   * LOCAL_INFERENCE（它确实跑在本机），但那是 Ollama 托管的、不是进程内的。 按 supplier 判会让"Ollama 上的 bge-m3"被误判成本地
   * ONNX，然后加载失败 —— 而那看起来像"模型坏了"。
   *
   * @param baseModel 模型目录里的基础模型名
   * @return 是否由本实现服务
   */
  public boolean serves(String baseModel) {
    return enabled && baseModel != null && servedModelName.equalsIgnoreCase(baseModel.trim());
  }

  /**
   * @return 是否已成功初始化（健康指示与判据都用它）
   */
  public boolean ready() {
    return model.get() != null;
  }

  /**
   * @return 最近一次加载失败的原因；没失败过则 {@code null}
   */
  public String loadFailure() {
    return loadFailure.get();
  }

  /**
   * @return 本地模型文件是否就位（不触发加载）
   */
  public boolean modelFilesPresent() {
    return resolve(modelPath).isFile() && resolve(tokenizerPath).isFile();
  }

  /**
   * 解析模型文件路径（配置里给的是**相对路径**）。
   *
   * <p>★★ 为什么需要它：相对路径相对的是**工作目录**，而本仓有两种 CWD：
   *
   * <ul>
   *   <li>`scripts/aip-be-live.cmd` 先 `cd /d %BE%` ⇒ CWD 是 `backend/`；
   *   <li>surefire（单测）的 basedir 也是 `backend/`（实测 `user.dir=D:\…\ai-plantform\backend`）；
   *   <li>但直接 `mvn -f backend/pom.xml spring-boot:run`（从仓库根执行）时 CWD 是**仓库根**。
   * </ul>
   *
   * <p>⇒ 先按原样找，找不到再往**上一层**找一次。★ 只向上一次、不循环 —— 无限向上会让 "配置写错了"变成"在别处找到了另一个模型"，而后者是**静默取错模型**，比报错可怕得多。
   */
  private File resolve(String configured) {
    File direct = new File(configured);
    if (direct.isFile()) {
      return direct;
    }
    File cwd = new File(System.getProperty("user.dir"));
    File parent = cwd.getParentFile();
    if (parent == null) {
      return direct;
    }
    File oneUp = new File(parent, configured);
    return oneUp.isFile() ? oneUp : direct;
  }

  /**
   * 把一批文本向量化（顺序即结果顺序）。
   *
   * @param texts 待向量化文本
   * @return 与 {@code texts} 一一对应的向量
   * @throws BusinessException 模型未启用 / 文件缺失 / 初始化失败 / 推理失败（★ 都带可读原因）
   */
  public List<float[]> embed(List<String> texts) {
    TransformersEmbeddingModel current = requireModel();
    try {
      return texts.stream().map(current::embed).toList();
    } catch (RuntimeException inferenceFailed) {
      // ★★ 必须把原始异常打进日志：{@code BusinessException} 没有 cause 构造器，
      //   只包一层 message 会丢掉**真正的抛出点** —— 第一次就因为这点看不到
      //   "Failed to save pytorch index file" 到底是谁抛的（判据红得没法查）。
      log.warn("本地向量模型推理失败", inferenceFailed);
      throw new BusinessException(
          ErrorCode.MODEL_NOT_READY,
          "本地向量模型推理失败：" + inferenceFailed.getMessage(),
          "★ 这一批没有落任何向量；请检查模型文件是否完整（见 models/README.md 的 size + sha256）");
    }
  }

  /**
   * 懒加载 + 失败缓存。
   *
   * <p>★ 用 {@code double-checked} + {@code synchronized}：初始化很贵（秒级），并发入库时会同时进来。
   */
  private TransformersEmbeddingModel requireModel() {
    if (!enabled) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "本地向量化未启用（app.embedding.local.enabled=false）",
          "要么打开它，要么把这个知识库换成走服务（配 apiBase）的向量模型");
    }
    TransformersEmbeddingModel current = model.get();
    if (current != null) {
      return current;
    }
    String failure = loadFailure.get();
    if (failure != null) {
      throw failureException(failure);
    }
    synchronized (this) {
      current = model.get();
      if (current != null) {
        return current;
      }
      failure = loadFailure.get();
      if (failure != null) {
        throw failureException(failure);
      }
      try {
        model.set(load());
        return model.get();
      } catch (RuntimeException failed) {
        // ⚠️ 触发点是受检异常（OrtException/IOException），这里统一收成一句可读原因
        loadFailure.set(failed.getMessage());
        log.warn("本地向量模型加载失败：{}", failed.getMessage(), failed);
        throw failureException(failed.getMessage());
      }
    }
  }

  /**
   * 真正初始化 ONNX 会话。
   *
   * <p>★ 先判文件存在：{@code TransformersEmbeddingModel} 在资源不存在时抛的是 一个由 ONNX 内部抛出的异常，消息里只有路径片段 ——
   * 对"我该放哪个文件"没有帮助。 这里提前给出**同一个模板下的可读文案**。
   */
  private TransformersEmbeddingModel load() {
    if (!modelFilesPresent()) {
      throw new IllegalStateException(
          "本地向量模型文件缺失：model=" + modelPath + "，tokenizer=" + tokenizerPath);
    }
    TransformersEmbeddingModel created = new TransformersEmbeddingModel();
    // ★★ 必须给一个**可写**的缓存目录：实测不加这一句时，模型能加载、但第一次推理就抛
    //   `Failed to save pytorch index file`（DJL 的 tokenizer/资源缓存默认要往
    //   它自己挑的位置写索引，而在本仓的运行方式下那个位置不可写）。
    //   ⇒ 缓存是真需要的（首次会解出 tokenizer 的索引），所以是"指到能写的地方"，不是关掉它。
    java.io.File cacheDir =
        new java.io.File(System.getProperty("java.io.tmpdir"), "ai-embedding-cache");
    if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
      throw new IllegalStateException("无法创建向量模型缓存目录：" + cacheDir.getAbsolutePath());
    }
    created.setResourceCacheDirectory(cacheDir.getAbsolutePath());
    created.setModelResource(new FileSystemResource(resolve(modelPath)));
    created.setTokenizerResource(new FileSystemResource(resolve(tokenizerPath)));
    // ★ 初始化（构造 ONNX 会话 + 载入 tokenizer）。开销就在这一句。
    try {
      created.afterPropertiesSet();
    } catch (Exception initFailed) {
      throw new IllegalStateException(
          "本地向量模型初始化失败（" + modelPath + "）：" + initFailed.getMessage(), initFailed);
    }
    log.info("本地向量模型已加载：{}（tokenizer={}）", modelPath, tokenizerPath);
    return created;
  }

  private BusinessException failureException(String reason) {
    return new BusinessException(
        ErrorCode.MODEL_NOT_READY,
        "本地向量模型不可用：" + reason,
        "按 models/README.md 把模型文件放到 "
            + modelPath
            + " 与 "
            + tokenizerPath
            + "（★ 缺文件时不会回退到上游——两个向量空间不同，混用后检索照样有结果，只是全错）");
  }

  /**
   * 关闭 ONNX 会话（★ 环境关不掉，见类注释）。
   *
   * <p>用 {@code @PreDestroy} 而不是 {@code DisposableBean}：与其它适配器同一风格。
   */
  @jakarta.annotation.PreDestroy
  void shutdown() {
    TransformersEmbeddingModel current = model.getAndSet(null);
    if (current == null) {
      return;
    }
    try {
      current.close();
      log.info("本地向量模型的 ONNX 会话已关闭");
    } catch (OrtException closeFailed) {
      // ★ 关不掉不影响正确性（进程要退了），但要留痕：反复出现说明会话没被正确释放
      log.warn("关闭本地向量模型的 ONNX 会话失败：{}", closeFailed.getMessage());
    }
  }

  /**
   * @return 供日志/诊断用的模型文件路径
   */
  public String modelPath() {
    return modelPath;
  }

  /**
   * @return 供诊断用的 tokenizer 路径
   */
  public String tokenizerPath() {
    return tokenizerPath;
  }

  /**
   * @return 本实现服务的模型名
   */
  public String servedModelName() {
    return servedModelName;
  }
}
