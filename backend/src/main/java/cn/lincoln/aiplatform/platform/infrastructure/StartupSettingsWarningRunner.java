package cn.lincoln.aiplatform.platform.infrastructure;

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

  /** 连接池上限。默认值与 Spring Boot 的 Hikari 默认值一致（10）。 */
  private final int poolSize;

  /** 上下文窗口字符数（`#65`，`0` = 关闭）。 */
  private final int contextWindow;

  /** 向量预算（`app.segmentation.max-chars`）。 */
  private final int segmentMaxChars;

  public StartupSettingsWarningRunner(
      @Value("${app.ingestion.embedding-concurrency:4}") int embeddingConcurrency,
      @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize,
      @Value("${app.segmentation.context-window:0}") int contextWindow,
      @Value("${app.segmentation.max-chars:1200}") int segmentMaxChars) {
    this.embeddingConcurrency = embeddingConcurrency;
    this.poolSize = poolSize;
    this.contextWindow = contextWindow;
    this.segmentMaxChars = segmentMaxChars;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> problems =
        StartupSettingsCheck.problems(
            embeddingConcurrency, poolSize, contextWindow, segmentMaxChars);

    if (problems.isEmpty()) {
      log.info(
          "配置自检通过：向量化并发 {} · 连接池 {} · 上下文窗口 {} · 向量预算 {}",
          embeddingConcurrency,
          poolSize,
          contextWindow,
          segmentMaxChars);
      return;
    }

    log.warn("⚠️ 配置自检发现 {} 项会在运行时以难查的方式出问题（服务仍会启动）：", problems.size());
    for (String problem : problems) {
      log.warn("  · {}", problem);
    }
  }
}
