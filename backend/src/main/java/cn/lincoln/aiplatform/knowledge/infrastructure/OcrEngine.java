package cn.lincoln.aiplatform.knowledge.infrastructure;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OCR 引擎的<strong>可用性探测</strong>（第 9 周 · 0.3.1）—— 把"这台机器能不能 OCR"变成一条显式事实。
 *
 * <h3>★ 它解决的问题：OCR 引擎是系统级资产，坏了不会报错</h3>
 *
 * <p>{@code tesseract} 与语言包不像 jar 那样跟着构建走。装了就能用、没装就<strong>静默地</strong>不可用 ——
 * 表现为"扫描件解析出空"，落进一句「可能是扫描件」的通用文案。本类把这件事提前到<strong>启动时</strong>问清楚：
 *
 * <ol>
 *   <li>配置关了吗（{@code app.ocr.enabled=false}）
 *   <li>找得到 {@code tesseract} 可执行文件吗（路径显式配了没 / PATH 上有没有）
 *   <li>★ 语言包在不在（{@code chi_sim.traineddata} 这类文件）—— <strong>这是离线交付最常漏的一件</strong>： 引擎装了、语言包忘拷 ⇒
 *       中文全成方块，而日志里一句错都没有
 * </ol>
 *
 * <p>结论同时落两处：<strong>启动日志</strong>（排障第一现场）与 {@link OcrHealthIndicator} （{@code
 * /actuator/health}，部署验收用）。
 *
 * <h3>★ 探测只做一次</h3>
 *
 * <p>引擎不会在运行中自己出现或消失（镜像里有没有就是有没有），所以结果缓存 —— 每次解析都去 spawn 一个进程探测会把吞吐白吃掉。
 */
@Component
public class OcrEngine {

  private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);

  private final OcrProperties properties;
  private volatile Status status;

  public OcrEngine(OcrProperties properties) {
    this.properties = properties;
  }

  /**
   * 可用性结论。
   *
   * @param available 是否可用
   * @param reason 不可用的可读原因（可用时为空串）—— 直接进用户可见的失败文案
   * @param hint 下一步建议（可用时为空串）
   * @param languages 实际可用的语言（能确认时给出，否则空）
   * @param tesseractPath 实际解析到的可执行文件（能确认时给出）
   */
  public record Status(
      boolean available, String reason, String hint, List<String> languages, String tesseractPath) {

    /** 一句话摘要（进日志与 health detail）。 */
    public String summary() {
      return available
          ? "可用（语言 " + String.join("+", languages) + "，tesseract " + tesseractPath + "）"
          : "不可用：" + reason;
    }
  }

  /** 探测结论（缓存）。 */
  public Status status() {
    Status local = status;
    if (local == null) {
      synchronized (this) {
        if (status == null) {
          status = probe();
        }
        local = status;
      }
    }
    return local;
  }

  /** 是否可用（解析链据此决定走 OCR 还是 NO_OCR）。 */
  public boolean available() {
    return status().available();
  }

  public OcrProperties properties() {
    return properties;
  }

  /**
   * 装配一个按配置配好的 {@link TesseractOCRParser}。
   *
   * <p>★ 每次都新建：Tika 的 parser 带了可变配置（路径、超时），共享一个实例会让"谁改了配置"变成隐式状态。
   */
  public TesseractOCRParser newParser() {
    TesseractOCRParser parser = new TesseractOCRParser();
    if (notBlank(properties.tesseractPath())) {
      parser.setTesseractPath(properties.tesseractPath().trim());
    }
    Optional<String> tessdata = effectiveTessdataPath();
    tessdata.ifPresent(parser::setTessdataPath);
    return parser;
  }

  /** 生效的 tessdata 目录：配置优先，其次 {@code TESSDATA_PREFIX} 环境变量（★ 用了哪个来源也说出来）。 */
  public Optional<String> effectiveTessdataPath() {
    if (notBlank(properties.tessdataPath())) {
      return Optional.of(properties.tessdataPath().trim());
    }
    String fromEnv = System.getenv("TESSDATA_PREFIX");
    return notBlank(fromEnv) ? Optional.of(fromEnv.trim()) : Optional.empty();
  }

  @PostConstruct
  void logStatus() {
    Status current = status();
    if (current.available()) {
      log.info("OCR 引擎：{}", current.summary());
    } else {
      // ★ 用 WARN 而不是 INFO：这条一旦出现，"扫描件能不能入库"就是否定的 ——
      //   它该在部署验收时被看见，而不是埋在启动日志里
      log.warn("OCR 引擎：{} —— {}", current.summary(), current.hint());
    }
  }

  // ────────────────────────── 探测本体 ──────────────────────────

  private Status probe() {
    if (!properties.enabled()) {
      return new Status(
          false,
          "OCR 已在配置里关闭（app.ocr.enabled=false）",
          "要支持扫描件/图像型 PDF，请置 app.ocr.enabled=true，并在机器或镜像里装好 tesseract 与语言包",
          List.of(),
          "");
    }

    String resolved = properties.tesseractPath() == null ? "" : properties.tesseractPath().trim();
    try {
      TesseractOCRParser parser = newParser();
      if (!parser.hasTesseract()) {
        return notFound(resolved);
      }
    } catch (Exception e) {
      // ★ hasTesseract() 在"找不到可执行文件"时是**抛异常**而不是返回 false（3.3.1 实测）
      return notFound(resolved);
    }

    // ── ★ 语言包：离线交付最常漏的一件 ──
    Optional<String> tessdata = effectiveTessdataPath();
    List<String> wanted = properties.languageList();
    List<String> missing = new ArrayList<>();
    if (tessdata.isPresent()) {
      for (String language : wanted) {
        Path file = Path.of(tessdata.get(), language + ".traineddata");
        if (!Files.isRegularFile(file)) {
          missing.add(language + ".traineddata");
        }
      }
    }
    if (!missing.isEmpty()) {
      return new Status(
          false,
          "找到了 tesseract，但缺语言包：" + String.join("、", missing) + "（目录 " + tessdata.orElse("?") + "）",
          "把对应的 <语言>.traineddata 放进该目录（离线交付清单见 deploy/README.md 的 OCR 段）；"
              + "缺中文包时中文会退化成方块/乱码，而且不报错",
          List.of(),
          resolved);
    }

    return new Status(
        true,
        "",
        "",
        wanted.isEmpty() ? List.of() : wanted,
        resolved.isEmpty() ? "(PATH)" : resolved);
  }

  private Status notFound(String configuredPath) {
    String where = configuredPath.isEmpty() ? "PATH 上（app.ocr.tesseract-path 未配置）" : configuredPath;
    return new Status(
        false,
        "找不到 tesseract 可执行文件（查找位置：" + where + "）",
        "在这台机器/这个镜像里安装 tesseract，或用 app.ocr.tesseract-path 指向它；" + "离线部署见 deploy/README.md 的 OCR 段",
        List.of(),
        "");
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
