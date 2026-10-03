package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * OCR 解析探针（第 9 周 · 0.3.1）—— 把<strong>我们对框架的假设</strong>写成断言。
 *
 * <h3>★ 本类存在的理由</h3>
 *
 * <p>设计册 {@code 12 §11.2} 的选型照 <strong>Tika 4.0</strong> 写（"4.0 自带三条 OCR 路线"）， 而本项目钉的是
 * <strong>3.3.1</strong>（4.0 只有 alpha）。于是有一串必须逐个钉住的假设： "3.3.1 有没有那套 API" · "CLI 路线通不通" ·
 * "抽出来的中文能不能用"。 ★ 前两个已经验通；第三个<strong>验出了设计册预留的那条退路</strong>（见下面那条金丝雀）。
 *
 * <h3>★★ 实测结论：Tika 的事件流在中文上<strong>不可用</strong> ⇒ 自解析 hOCR</h3>
 *
 * <p>{@code scanned.pdf} 经 Tika 走得通、也真的抽出了正文，但输出是 「某 某 集团 内 部 管理 制度」——
 * <strong>每个汉字之间都被插了空格</strong>。
 *
 * <p>根因（已看原始 hOCR 定位，不是猜）：chi_sim 把 <strong>每一个汉字</strong>识别成一个 {@code <span class='ocrx_word'>}（实测
 * 133 个 word 对应 133 个汉字）， 而 Tika 把 hOCR 当 XHTML 事件流发出去时，**词与词之间就成了空白** —— {@code
 * preserveInterwordSpacing=false} 也救不回来（那个开关只作用于 TXT 那条路）。
 *
 * <p>★ 危害不是"不好看"：分块器的 L1/L2 锚点（「第X章 / 第X条」正则，{@code 12 §11.5} 说它是 OCR
 * 路径的<strong>主锚点</strong>）从此匹配不到，短语级召回也一起失效 —— 又一个「看起来在做、实际没生效」。
 *
 * <p>⇒ 按 {@code 12 §11.4} 明确预留的退路走：<strong>自解析 hOCR</strong>。 hOCR 里需要的信号一个不少（实测）：{@code bbox} ·
 * {@code x_wconf} · {@code x_size} · {@code ocr_par} / {@code ocr_line}，而且★ **汉字之间的空隙是用 `bbox`
 * 表达的**—— 这恰好是重建"该不该加空格"的唯一依据（同线且相邻 ⇒ 不加；有明显间距 ⇒ 加）。
 *
 * <p>★ 本机没有 tesseract 时<strong>跳过而不是失败</strong>（与 {@code EmbeddingPipelineTest} 同口径）； 真正的端到端证据在真栈闸门
 * {@code check_ocr_e2e.py}。
 */
class OcrParserTest {

  /**
   * 逐页判定阈值：每页字符数 &lt; 10 ⇒ 该页走 OCR（{@code 12 §11.3}）。
   *
   * <p>★ 格式口径与设计册不一致，已按<b>实测</b>修正：设计册抄的是 Tika 4.0 的 <strong>JSON</strong> 块（{@code
   * {"totalCharsPerPage":10,...}}），而 3.3.1 的 {@code setOcrStrategyAuto(String)} 要的是 <strong>{@code
   * "num[%], num"}</strong> 这种短格式 —— 照抄设计册会直接抛 {@code Error parsing OCRStrategyAuto}。
   */
  private static final String STRATEGY_AUTO = "10, 10";

  @BeforeAll
  static void requireTesseract() {
    boolean available;
    try {
      available = new TesseractOCRParser().hasTesseract();
    } catch (Exception e) {
      // ★ hasTesseract() 在"找不到可执行文件"时是**抛异常**而不是返回 false ——
      //   直接调会让"没装 tesseract 的机器"看到一片红，而不是"跳过"。
      available = false;
    }
    assumeThat(available).as("本机没有 tesseract CLI ⇒ 跳过（真栈闸门 check_ocr_e2e.py 才是端到端证据）").isTrue();
  }

  // ────────────────────────── 1 · 能力清单（框架假设） ──────────────────────────

  /**
   * ★ 假设 ①：CLI Tesseract 可用。
   *
   * <p>★ 这里<strong>刻意不断言</strong> {@code parser.getLangs()}：实测它在 3.3.1 上返回**空集**， 而同一台机器的 {@code
   * tesseract --list-langs} 明明列出 chi_sim / eng —— 它读的是 Tika 自己的 tessdata 位置，不是 CLI
   * 的位置。拿它当"语言包装没装"的判据只会得到一个**假的红灯**； 真正该问的是"中文认得出来吗"，那由下面的产物断言回答。
   */
  @Test
  void cli_tesseract可用() throws Exception {
    assertThat(new TesseractOCRParser().hasTesseract())
        .as("hasTesseract() 必须为真 —— 否则整条 OCR 链无从谈起")
        .isTrue();
  }

  /**
   * 诊断：把 Tika 眼中的 OCR 环境原样打出来。
   *
   * <p>★ 留着它是有意的：「部署期漏装 chi_sim」是最难发现的一类（不影响启动、只是中文全成方块）， 排障时先跑这条看 {@code getTesseractProg /
   * tessdataPath / TESSDATA_PREFIX}。
   */
  @Test
  void 诊断_把Tika眼中的OCR环境打出来() throws Exception {
    TesseractOCRParser ocr = new TesseractOCRParser();
    System.out.println("== OCR 环境诊断 ==");
    System.out.println("  getTesseractProg()   = " + TesseractOCRParser.getTesseractProg());
    System.out.println("  getTesseractPath()   = " + ocr.getTesseractPath());
    System.out.println("  getTessdataPath()    = " + ocr.getTessdataPath());
    System.out.println("  getLangs()           = " + ocr.getLangs() + "（★ 实测为空，别拿它判断语言包）");
    System.out.println("  env TESSDATA_PREFIX  = " + System.getenv("TESSDATA_PREFIX"));
    System.out.println("  env PATH 里的 tesseract = " + whichTesseract());
    assertThat(ocr.hasTesseract()).isTrue();
  }

  // ────────────────────────── 2 · ★ 金丝雀：Tika 事件流在中文上的缺陷 ──────────────────────────

  /**
   * ★★ 金丝雀：<strong>断言 Tika 的事件流在中文上会打散汉字</strong> —— 这就是"自解析 hOCR"的依据。
   *
   * <p>它是<strong>反向断言</strong>（断言缺陷存在），写法刻意如此：
   *
   * <ul>
   *   <li>缺陷在 ⇒ 绿，说明"自解析 hOCR"这条退路仍然必要
   *   <li>缺陷哪天被 Tika 修好（或换了版本）⇒ <strong>变红</strong>，提醒我们回来重新评估： 也许可以直接用事件流，少维护一套 hOCR 解析器
   * </ul>
   *
   * <p>★ 不这么写的话，这条知识只会留在某人的记忆里 —— 而"我们为什么不用框架自带的那个"是最容易被 后人"优化"掉的一类决定。
   */
  @Test
  void 金丝雀_Tika事件流在中文上会打散汉字() throws Exception {
    String markdown = parseWithTika("scanned.pdf", PDFParserConfig.OCR_STRATEGY.AUTO);

    assertThat(markdown).as("OCR 确实跑了（否则这条金丝雀在空跑）").isNotBlank();
    assertThat(markdown)
        .as("★ 若这里不再匹配，说明 Tika 已经不再打散汉字 —— 请重新评估是否还需要自解析 hOCR" + "（12 §11.4 的退路）")
        .contains("某 某 集团")
        .doesNotContain("某某集团");
  }

  // ────────────────────────── 3 · 逐页判定（真正的能力） ──────────────────────────

  /**
   * ★ 逐页判定在起作用：同一份全扫描件，{@code AUTO} 抽得出正文、{@code NO_OCR} 抽不出。
   *
   * <p>★ 这条比"抽出来了"更有价值：它排除了"其实是别的东西给了文本"。 断言用<strong>去空白后</strong>比较 —— 因为 Tika
   * 事件流那条路的空格缺陷是已知的（见上面金丝雀）， 这里要验的是"有没有内容"，不是"空格对不对"。
   */
  @Test
  void 逐页判定在起作用_关掉OCR就抽不出正文() throws Exception {
    String withOcr = squeeze(parseWithTika("scanned.pdf", PDFParserConfig.OCR_STRATEGY.AUTO));
    String withoutOcr = squeeze(parseWithTika("scanned.pdf", PDFParserConfig.OCR_STRATEGY.NO_OCR));

    assertThat(withOcr)
        .as("AUTO 下要抽出正文 —— 含章节标题（分块器的 L1/L2 锚点）与正文长句")
        .contains("第一章")
        .contains("住宿标准");
    assertThat(withoutOcr).as("NO_OCR 下必须几乎为空 —— 否则上一条的'抽出了正文'根本不是 OCR 的功劳").doesNotContain("住宿标准");
  }

  /**
   * ★ 混合型 PDF 逐页处理：{@code mixed.pdf} 第 1 页有文本层、第 2–3 页没有。
   *
   * <p>判据要的是逐页而不是"整文档二选一"：第 1 页的文本必须来自文本层 （否则会多跑一次无谓的 OCR，还会把清晰的文本层换成 OCR 的错字）。
   */
  @Test
  void 混合型PDF逐页处理() throws Exception {
    String withOcr = squeeze(parseWithTika("mixed.pdf", PDFParserConfig.OCR_STRATEGY.AUTO));

    assertThat(withOcr).as("第 1 页有文本层 ⇒ 它的内容必须在（来自文本层）").contains("某某集团内部管理制度");
    assertThat(withOcr).as("第 2–3 页没有文本层 ⇒ 也要有内容（来自 OCR）").contains("住宿标准");
  }

  // ────────────────────────── 工具 ──────────────────────────

  /** 去掉所有空白后比较 —— 绕开 Tika 事件流的已知空格缺陷，只验"有没有内容"。 */
  private static String squeeze(String text) {
    return Pattern.compile("\\s+").matcher(text).replaceAll("");
  }

  private static String parseWithTika(String fixture, PDFParserConfig.OCR_STRATEGY strategy) {
    byte[] bytes = readFixture(fixture);

    PDFParserConfig pdf = new PDFParserConfig();
    pdf.setOcrStrategy(strategy);
    pdf.setOcrStrategyAuto(STRATEGY_AUTO);
    // ★ dpi 与"渲染策略 ALL"都是 12 §11.3 的口径：300dpi 是 OCR 质量的下限
    pdf.setOcrDPI(300);
    pdf.setOcrRenderingStrategy(PDFParserConfig.OCR_RENDERING_STRATEGY.ALL);

    TesseractOCRConfig tess = new TesseractOCRConfig();
    tess.setLanguage("chi_sim+eng");
    // ★ HOCR 而不是 TXT：只有 hOCR 带 bbox / x_wconf（低置信度标 needsReview 与页眉页脚去重都靠它，12 §11.4）
    tess.setOutputType(TesseractOCRConfig.OUTPUT_TYPE.HOCR);
    tess.setDensity(300);
    tess.setEnableImagePreprocessing(true);
    tess.setTimeoutSeconds(180);

    ParseContext context = new ParseContext();
    context.set(PDFParserConfig.class, pdf);
    context.set(TesseractOCRConfig.class, tess);
    context.set(TesseractOCRParser.class, new TesseractOCRParser());

    ToMarkdownContentHandler handler = new ToMarkdownContentHandler();
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fixture);
    try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
      new AutoDetectParser().parse(in, handler, metadata, context);
    } catch (Exception e) {
      throw new IllegalStateException("解析夹具失败：" + fixture, e);
    }
    return handler.toString();
  }

  private static byte[] readFixture(String fixture) {
    try (InputStream in = OcrParserTest.class.getResourceAsStream("/fixtures/" + fixture)) {
      if (in == null) {
        throw new IllegalStateException("夹具不在 classpath：" + fixture);
      }
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String whichTesseract() {
    String path = System.getenv("PATH");
    if (path == null) {
      return "(PATH 为空)";
    }
    for (String dir : path.split(java.io.File.pathSeparator)) {
      java.io.File candidate = new java.io.File(dir, "tesseract.exe");
      if (candidate.isFile()) {
        return candidate.getAbsolutePath();
      }
    }
    return "(不在 PATH 上)";
  }
}
