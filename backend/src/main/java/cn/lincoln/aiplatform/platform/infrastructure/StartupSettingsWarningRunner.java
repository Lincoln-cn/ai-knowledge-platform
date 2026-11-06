package cn.lincoln.aiplatform.platform.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时跑一遍 {@link StartupSettingsCheck}，把"配错了会在第一次入库才炸"的项提前打出来。
 *
 * <p>★ 用 {@link ApplicationRunner}（与 {@code CaptchaDisabledWarningRunner} / {@code
 * PermissionCatalogRunner} 同一形态）：它在上下文完全就绪之后执行，此时配置项已经绑定到最终值 —— 用 {@code @PostConstruct}
 * 读到的是"还没绑定完"的对象。
 *
 * <p>★ 四项都用 {@code @Value} 直接取，**不注入** {@code SegmentationProperties}：那个 record 住在 {@code
 * knowledge} 模块里，而 `platform` 是底座，反向依赖业务模块会把 {@code ModuleBoundaryTest} 守的方向弄反（`#74` 的
 * `CryptoConfig` 出于同一个理由用了 {@code @Value}，那次是有意的，这次也是）。
 *
 * <p>★ 只打日志、<strong>不抛异常</strong>：这些都是"能跑但会以难查的方式坏"的配置， 让它 fail-fast
 * 起不来，会把一个可修复的配置问题升级成"服务挂了"。真正的硬约束（比如负的窗口会在 入库时抛）仍然由领域层在运行时守住。
 *
 * <p>★ 一项一行日志、彼此独立：不要拼成一段 —— 长文在日志里会被 grep 切碎，而人排查时是照着配置名搜的。
 */
@Component
public class StartupSettingsWarningRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StartupSettingsWarningRunner.class);

  /** 同时向量化的文档份数（`#227` 的闸门容量）。 */
  private final int embeddingConcurrency;

  /** 同时解析/分段的文档份数（`#303` 的闸门容量；0 = auto）。 */
  private final int parseConcurrency;

  /** 连接池上限。默认值与 Spring Boot 的 Hikari 默认值一致（10）。 */
  private final int poolSize;

  /** 上下文窗口字符数（`#65`，`0` = 关闭）。 */
  private final int contextWindow;

  /** 向量预算（`app.segmentation.max-chars`）。 */
  private final int segmentMaxChars;

  private final int restoreWindowDays;

  private final int cleanupRetainDays;

  /** JWT 签名密钥（默认值是仓库里的示例值，`#272`）。 */
  private final String jwtSecret;

  /** 模型 API Key 的加密密钥（默认值是仓库里的示例值，`#272`）。 */
  private final String apiKeySecret;

  /**
   * 是否开启了"取相邻段落做上下文"（`#65`，`app.chat.retrieval.neighbor-context.enabled`）。
   *
   * <p>★ 这一项<b>不进 problems</b>：它不是"配错了会坏"，而是"打开了就要知道它多跑一次 SQL、且这个 版本没有回归测试兜住"。放进去会把"配置自检发现 N
   * 项"的语义污染成"N 个配置错误"。
   */
  private final boolean neighborContextEnabled;

  /** 上游 Ollama 的模型槽位上限（`#282`）；`-1` = 未声明，走 Ollama 自己的默认值。 */
  private final int ollamaMaxLoadedModels;

  public StartupSettingsWarningRunner(
      @Value("${app.ingestion.embedding-concurrency:4}") int embeddingConcurrency,
      @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
      @Value("${app.segmentation.context-window:0}") int contextWindow,
      @Value("${app.segmentation.max-chars:1200}") int segmentMaxChars,
      // ★ 回收站的两个窗口（`#259`）：分开两个键是因为它们回答不同的问题
      //   （"用户还能撤回多久" vs "内容还能占多久"），但配错方向会互相打脸。
      @Value("${app.knowledge.restore-window-days:7}") int restoreWindowDays,
      @Value("${app.knowledge.cleanup.retain-days:7}") int cleanupRetainDays,
      // ★ 两个密钥只用来和"示例值"对比，不参与任何计算：值本身就是秘密，日志里一个字都不许打。
      @Value("${app.security.jwt.secret:}") String jwtSecret,
      @Value("${app.model.api-key-secret:}") String apiKeySecret,
      // ★ `#282`：问答要两个模型同时驻留；槽位只有 1 时每轮都在重新加载模型。
      //   默认 -1 = 没声明（Ollama 默认 3×GPU 数，通常够），不报。
      @Value("${app.model.ollama.max-loaded-models:-1}") int ollamaMaxLoadedModels,
      // ★ `#303`：解析/分段并发（0 = auto）。与向量化并发同一套容量口径 —— 一起校验。
      @Value("${app.ingestion.parse-concurrency:0}") int parseConcurrency,
      @Value("${app.chat.retrieval.neighbor-context.enabled:false}")
          boolean neighborContextEnabled) {
    this.embeddingConcurrency = embeddingConcurrency;
    this.poolSize = poolSize;
    this.contextWindow = contextWindow;
    this.segmentMaxChars = segmentMaxChars;
    this.restoreWindowDays = restoreWindowDays;
    this.cleanupRetainDays = cleanupRetainDays;
    this.jwtSecret = jwtSecret;
    this.apiKeySecret = apiKeySecret;
    this.ollamaMaxLoadedModels = ollamaMaxLoadedModels;
    this.parseConcurrency = parseConcurrency;
    this.neighborContextEnabled = neighborContextEnabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> problems =
        new ArrayList<>(
            StartupSettingsCheck.problems(
                embeddingConcurrency,
                poolSize,
                contextWindow,
                segmentMaxChars,
                restoreWindowDays,
                cleanupRetainDays));
    // ★ 密钥那两条单独算：它们不是"配错了会坏"，而是"没换就等于没有"（`#272`）。
    problems.addAll(StartupSettingsCheck.secretProblems(jwtSecret, apiKeySecret));
    // ★ 上游模型槽位（`#282`）：与上面那两组不同，它管的是"能不能稳定跑"，不是"安不安全"。
    problems.addAll(StartupSettingsCheck.modelSlotProblems(ollamaMaxLoadedModels));
    // ★ 解析/分段并发（`#303`）：与向量化并发**同一套容量口径**（都从连接池上限推），所以同一处校验。
    problems.addAll(
        StartupSettingsCheck.ingestionProblems(
            parseConcurrency, poolSize, Runtime.getRuntime().availableProcessors()));

    // ★ 邻座上下文（`#65`）：默认关。打开后每轮问答多一次相邻段落查询，而这个版本它没有
    //   仓库内的回归测试（见 ROADMAP §11.18.5），所以打开时明说，不让它悄悄生效。
    if (neighborContextEnabled) {
      log.warn(
          "⚠️ 邻座上下文已开启（app.chat.retrieval.neighbor-context.enabled=true）：每轮问答会额外查一次"
              + "相邻段落。★ 该功能在本版本没有回归测试覆盖，生产请先小范围验证；关掉它不影响任何现有行为。");
    }

    if (problems.isEmpty()) {
      log.info(
          "配置自检通过：向量化并发 {} · 连接池 {} · 上下文窗口 {} · 向量预算 {} · 恢复窗口 {} 天 · 清理窗口 {} 天 · 两个密钥都不是示例值",
          embeddingConcurrency,
          poolSize,
          contextWindow,
          segmentMaxChars,
          restoreWindowDays,
          cleanupRetainDays);
      return;
    }

    log.warn("⚠️ 配置自检发现 {} 项会在运行时以难查的方式出问题（服务仍会启动）：", problems.size());
    for (String problem : problems) {
      log.warn("  · {}", problem);
    }
  }
}
