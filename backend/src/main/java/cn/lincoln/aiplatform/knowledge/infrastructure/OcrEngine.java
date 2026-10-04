package cn.lincoln.aiplatform.knowledge.infrastructure;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
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

  /**
   * 逐页判定阈值：每页字符数 &lt; 10 ⇒ 该页走 OCR（{@code 12 §11.3}）。
   *
   * <p>★ 格式口径按<b>实测</b>修正：设计册抄的是 Tika 4.0 的 <strong>JSON</strong> 块 （{@code
   * {"totalCharsPerPage":10,...}}），而 3.3.1 的 {@code setOcrStrategyAuto(String)} 要的是 <strong>{@code
   * "num[%], num"}</strong> 短格式 —— 照抄设计册会直接抛 {@code Error parsing OCRStrategyAuto}。
   */
  private static final String OCR_STRATEGY_AUTO = "10, 10";

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
   * 把 OCR 相关的解析配置装进 Tika 的上下文 —— ★★ <strong>这个方法就是"Tesseract 的全部知识"的边界</strong>。
   *
   * <h3>★ 为什么值得单独一个方法（用户提的"会不会硬编码"）</h3>
   *
   * <p>流水线的其余部分（分块 → 前缀 → 向量化 → 进度）**一行都不认识 OCR 引擎**： 它们只消费 {@code ParseResult}（域类型：正文 + 页数 + OCR
   * 页数）。 而"引擎怎么配"这件事全部收在这里 —— 产物形态、空格口径、语言、DPI、超时、预处理、逐页判定阈值。
   *
   * <p>★ 因此换引擎的成本被限制在<strong>这一个文件</strong>：
   *
   * <ul>
   *   <li>换成 <b>tess4j</b>（JNI，进程内）⇒ 改 {@link #newParser()} 与 {@link #tesseractConfig()} 的实现， 外层不用动
   *   <li>换成 Tika 的 <b>VLM 路线</b>（{@code openai-vlm-parser} 经 JAiRouter 调视觉模型）⇒ 在这里加一条分支
   *   <li>换成**根本不是 Tika parser 的引擎**（例如自带 Java 绑定的引擎）⇒ 那时才需要把 "解析"整体提到一个端口后面；★
   *       但那是**另一种量级的决定**，不该现在为它预先铺抽象
   * </ul>
   *
   * <p>★ 判据（可机械核对）：<code>grep -rn Tesseract backend/src/main</code> 应当**只命中本文件**。
   * 一旦别处也出现，说明引擎知识开始渗进流水线了。
   *
   * <p>⚠️ 一条已裁决的事实：设计册 {@code 12 §11.2} 明确**排除过 PaddleOCR** （无成熟的纯 Java 进程内调用；其"独立 OCR
   * 容器"形态违反本项目的三进程约束）。 所以"以后换 PaddleOCR"在本项目的约束下不是主路径 —— 但上面这条边界对任何引擎都成立。
   */
  public void configure(PDFParserConfig pdf, ParseContext context) {
    if (!available()) {
      // ★ 显式置 NO_OCR，不依赖默认值：哪天默认改成 AUTO，一台没装引擎的机器会在每页上
      //   都尝试一次 spawn 并失败 —— 症状是"解析变慢且原因不明"。
      pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
      return;
    }
    // ★ 逐页判定由 Tika 内建完成（12 §11.3）：每页文本量 < 阈值 ⇒ 该页走 OCR，
    //   混合型 PDF 自动逐页处理，不需要我们写"整文档二选一"。
    pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.AUTO);
    pdf.setOcrStrategyAuto(OCR_STRATEGY_AUTO);
    pdf.setOcrDPI(properties.dpi());
    pdf.setOcrRenderingStrategy(PDFParserConfig.OCR_RENDERING_STRATEGY.ALL);
    context.set(TesseractOCRConfig.class, tesseractConfig());
    context.set(TesseractOCRParser.class, newParser());
  }

  /**
   * Tesseract 的详细配置。
   *
   * <p>★★ 产物形态用 <b>TXT</b>（= Tika 的默认值），不用 HOCR。这一行是<strong>实测换来的</strong>， 而且我第一版选错了：为了拿词级 {@code
   * x_wconf}（低置信度标 needsReview）我选了 HOCR， 结果中文被逐字打散成「某 某 集团 内 部 管理 制度」—— chi_sim 把**每个汉字**识别成一个
   * {@code ocrx_word}，事件流里它们之间就成了空白。四种组合实测（夹具 {@code scanned.pdf}，原文已知）：
   *
   * <table>
   *   <tr><th>OUTPUT_TYPE</th><th>preserveInterwordSpacing</th><th>中文产物</th></tr>
   *   <tr><td><b>TXT（默认）</b></td><td><b>true（默认）</b></td><td>★ <b>某某集团内部管理制度</b>（正确）</td></tr>
   *   <tr><td>TXT</td><td>false</td><td>某 某 集团 …（打散）</td></tr>
   *   <tr><td>HOCR</td><td>true / false</td><td>某 某 集团 …（打散）</td></tr>
   * </table>
   *
   * <p>⇒ 打散<strong>不是</strong> Tika 事件流的固有缺陷，是选 HOCR 造成的；标准接口本来就能给出干净中文。 {@code x_wconf}
   * 那个需求退化成"**是否要在本适配器内部再做一次 hOCR 增强**"的可选项， 不再是流水线的一部分。
   */
  private TesseractOCRConfig tesseractConfig() {
    TesseractOCRConfig tess = new TesseractOCRConfig();
    tess.setLanguage(properties.languages());
    tess.setOutputType(TesseractOCRConfig.OUTPUT_TYPE.TXT);
    // ★ 显示写 true（而不是靠默认值）：上表说明 **false 会把中文打散**，而"不重建词间空格"
    //   听上去才是更自然的选择 —— 一个会被后人顺手改成 false 的默认值，必须钉住并说明理由。
    tess.setPreserveInterwordSpacing(true);
    tess.setDensity(properties.dpi());
    tess.setEnableImagePreprocessing(properties.preprocess());
    tess.setTimeoutSeconds(properties.timeoutSeconds());
    return tess;
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
