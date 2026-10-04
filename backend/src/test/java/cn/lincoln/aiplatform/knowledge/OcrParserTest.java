package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
 * <h3>★★ 本类最重要的一条：一个被推翻的结论，以及它为什么会错</h3>
 *
 * <p>第一版我选了 {@code OUTPUT_TYPE.HOCR}（为了拿词级 {@code x_wconf}，给"低置信度标 needsReview"用）， 结果中文被逐字打散：<code>
 * 某 某 集团 内 部 管理 制度</code>。 当时的结论是"**Tika 的事件流在中文上不可用 ⇒ 必须自解析 hOCR**"，并把它写成了金丝雀。
 *
 * <p>★ 用户追问了一句「**有没有其他解决方案？剥离了标准接口，以后换 PaddleOCR 还得适配，是不是硬编码了**」—— 重做四种组合的实测后，结论被推翻：
 *
 * <table>
 *   <tr><th>OUTPUT_TYPE</th><th>preserveInterwordSpacing</th><th>中文产物</th></tr>
 *   <tr><td><b>TXT（Tika 默认）</b></td><td><b>true（默认）</b></td><td>★ <b>某某集团内部管理制度</b>（正确）</td></tr>
 *   <tr><td>TXT</td><td>false</td><td>某 某 集团 …（打散）</td></tr>
 *   <tr><td>HOCR</td><td>true</td><td>某 某 集团 …（打散）</td></tr>
 *   <tr><td>HOCR</td><td>false</td><td>某 某 集团 …（打散）</td></tr>
 * </table>
 *
 * <p>⇒ ★★ **打散不是 Tika 事件流的固有缺陷，是我自己选 HOCR 造成的。** 标准接口（{@code TesseractOCRParser} + {@code
 * ToMarkdownContentHandler}）本来就能给出干净中文， **不需要自解析 hOCR** —— 那条"退路"会把流水线耦合到 Tesseract 的格式上（换
 * PaddleOCR 就得重写）。
 *
 * <p>而设计册 {@code 12 §11.3} 要的「哪几页走了 OCR」这个可观测面， 标准接口也**已经给了**：PDF 元数据里的 {@code
 * pdf:ocrPageCount}（逐页判定与统计都是 Tika 内建做的）。 实测三档（页数已知）：{@code structured.pdf}=0 · {@code mixed.pdf}=2
 * · {@code scanned.pdf}=3。
 *
 * <p>★ 留下的唯一遗留问题：词级 {@code x_wconf}（needsReview 用）不在 TXT 路径上。 那属于 **Tesseract
 * 适配器内部的可选增强**，不是流水线的一部分 —— 见 issue #1 的重新定性。
 *
 * <h3>夹具自带"答案"</h3>
 *
 * <p>{@code scanned.pdf} / {@code mixed.pdf} 是把 {@code noisy.pdf} 逐页光栅化造的 （{@code
 * backend/tools/make_ocr_fixtures.py}），原文已知 ⇒ 质量可以写成断言。
 *
 * <p>★ 本机没有 tesseract 时<strong>跳过而不是失败</strong>（与 {@code EmbeddingPipelineTest} 同口径）； 端到端证据在真栈闸门
 * {@code check_ocr_e2e.py}。
 */
class OcrParserTest {

  /**
   * 逐页判定阈值：每页字符数 &lt; 10 ⇒ 该页走 OCR（{@code 12 §11.3}）。
   *
   * <p>★ 格式口径按<b>实测</b>修正：设计册抄的是 Tika 4.0 的 <strong>JSON</strong> 块 （{@code
   * {"totalCharsPerPage":10,...}}），而 3.3.1 的 {@code setOcrStrategyAuto(String)} 要的是 <strong>{@code
   * "num[%], num"}</strong> 短格式 —— 照抄设计册会直接抛 {@code Error parsing OCRStrategyAuto}。
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

  // ────────────────────────── 1 · ★ 核心：标准接口下中文是干净的 ──────────────────────────

  /**
   * ★★ 结论级断言：默认配置（TXT + 重建词间空格）下，中文**必须是连续的短语**。
   *
   * <p>断言用<b>精确短语</b>而不是"非空"或"去空白后包含"—— 后者在被打散的产物上也成立， 那正是第一版把自己误导了的地方。
   */
  @Test
  void 标准接口下中文是连续的短语() {
    String markdown = parse("scanned.pdf", TesseractOCRConfig.OUTPUT_TYPE.TXT, true);

    assertThat(markdown)
        .as("章节标题必须连成一个词 —— 它是分块器 L1/L2 的锚点（12 §11.5）")
        .contains("某某集团内部管理制度")
        .contains("第一章");
    assertThat(markdown)
        .as("★ 不许出现逐字打散的形态（第一版就是栽在这里）")
        .doesNotContain("某 某")
        .doesNotContain("第 一 章");
    assertThat(markdown).as("正文长句也要认出来").contains("住宿标准");
  }

  /**
   * ★ 金丝雀（反向断言）：<strong>{@code preserveInterwordSpacing=false} 会把中文打散</strong>。
   *
   * <p>这条钉住的是"我们为什么要在生产配置里**显式写** {@code true}"： 「不重建词间空格」听上去才是更自然的选择，任何人顺手改成 {@code false} 都会打穿
   * L1/L2 锚点， 而产物看起来只是"多了些空格"。
   *
   * <p>★ 若这里不再匹配（Tika 换了行为）⇒ <strong>变红</strong>，提醒回来重新评估： 那个显式设置也许可以撤掉。
   */
  @Test
  void 金丝雀_关掉词间空格重建会把中文打散() {
    String markdown = parse("scanned.pdf", TesseractOCRConfig.OUTPUT_TYPE.TXT, false);

    assertThat(markdown)
        .as("★ 若不再匹配，说明 Tika 已不再打散 —— 请重新评估生产里那个显式 true 还有没有必要")
        .contains("某 某")
        .doesNotContain("某某集团内部管理制度");
  }

  // ────────────────────────── 2 · 逐页判定与"哪几页走了 OCR" ──────────────────────────

  /**
   * ★ 逐页判定在起作用：同一份全扫描件，{@code AUTO} 抽得出正文、{@code NO_OCR} 抽不出。
   *
   * <p>这条比"抽出来了"更有价值：它排除了"其实是别的东西给了文本"。
   */
  @Test
  void 逐页判定在起作用_关掉OCR就抽不出正文() {
    String withOcr = parse("scanned.pdf", TesseractOCRConfig.OUTPUT_TYPE.TXT, true);
    String withoutOcr =
        parse(
                "scanned.pdf",
                TesseractOCRConfig.OUTPUT_TYPE.TXT,
                true,
                PDFParserConfig.OCR_STRATEGY.NO_OCR)
            .markdown();

    assertThat(withOcr).contains("第一章").contains("住宿标准");
    assertThat(withoutOcr).as("NO_OCR 下必须几乎为空 —— 否则上一条的'抽出了正文'根本不是 OCR 的功劳").doesNotContain("住宿标准");
  }

  /**
   * ★★ 「哪几页是扫描件」的可观测面<strong>来自标准元数据</strong> （{@code pdf:ocrPageCount}），不是自己数的 —— 三个页数已知的夹具各给一个读数。
   *
   * <p>★ 这条同时是"不必自解析 hOCR"的证据：设计册要的可观测面，标准接口已经给了。
   */
  @Test
  void OCR页数来自标准元数据() {
    assertThat(ocrPagesOf("structured.pdf")).as("全文本层 ⇒ 一页都不该走 OCR").isZero();
    assertThat(ocrPagesOf("mixed.pdf")).as("首页有文本层、后两页没有 ⇒ 恰好 2 页").isEqualTo(2);
    assertThat(ocrPagesOf("scanned.pdf")).as("三页全无文本层 ⇒ 3 页").isEqualTo(3);
  }

  /** ★ 混合型 PDF 逐页处理：第 1 页的文本来自文本层，第 2–3 页来自 OCR。 */
  @Test
  void 混合型PDF逐页处理() {
    String markdown = parse("mixed.pdf", TesseractOCRConfig.OUTPUT_TYPE.TXT, true);

    assertThat(markdown).as("第 1 页有文本层 ⇒ 它的内容必须在（来自文本层）").contains("某某集团内部管理制度");
    assertThat(markdown).as("第 2–3 页没有文本层 ⇒ 也要有内容（来自 OCR）").contains("住宿标准");
  }

  // ────────────────────────── 3 · 环境诊断 ──────────────────────────

  /**
   * ★ 假设 ①：CLI Tesseract 可用。
   *
   * <p>★ 这里<strong>刻意不断言</strong> {@code parser.getLangs()}：实测它在 3.3.1 上返回**空集**， 而同一台机器的 {@code
   * tesseract --list-langs} 明明列出 chi_sim / eng —— 它读的是 Tika 自己的 tessdata 位置，不是 CLI
   * 的位置。拿它当"语言包装没装"的判据只会得到一个**假的红灯**。
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

  // ────────────────────────── 工具 ──────────────────────────

  private static String parse(
      String fixture, TesseractOCRConfig.OUTPUT_TYPE type, boolean preserveSpacing) {
    return parse(fixture, type, preserveSpacing, PDFParserConfig.OCR_STRATEGY.AUTO).markdown();
  }

  private static int ocrPagesOf(String fixture) {
    return parse(
            fixture, TesseractOCRConfig.OUTPUT_TYPE.TXT, true, PDFParserConfig.OCR_STRATEGY.AUTO)
        .facts()
        .ocrPageCount();
  }

  /** 解析产物 + 页数事实（与生产走**同一套配置**，只把产物形态参数化以便做上面的对照实验）。 */
  private record Parsed(String markdown, Facts facts) {}

  private record Facts(int pageCount, int ocrPageCount) {}

  private static Parsed parse(
      String fixture,
      TesseractOCRConfig.OUTPUT_TYPE type,
      boolean preserveSpacing,
      PDFParserConfig.OCR_STRATEGY strategy) {
    byte[] bytes = readFixture(fixture);

    PDFParserConfig pdf = new PDFParserConfig();
    pdf.setOcrStrategy(strategy);
    pdf.setOcrStrategyAuto(STRATEGY_AUTO);
    pdf.setOcrDPI(300);
    pdf.setOcrRenderingStrategy(PDFParserConfig.OCR_RENDERING_STRATEGY.ALL);

    TesseractOCRConfig tess = new TesseractOCRConfig();
    tess.setLanguage("chi_sim+eng");
    tess.setOutputType(type);
    tess.setPreserveInterwordSpacing(preserveSpacing);
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
    String[] perPage = metadata.getValues("pdf:charsPerPage");
    int pageCount =
        perPage != null && perPage.length > 0
            ? perPage.length
            : intOf(metadata.get("xmpTPg:NPages"));
    return new Parsed(
        handler.toString(), new Facts(pageCount, intOf(metadata.get("pdf:ocrPageCount"))));
  }

  private static int intOf(String raw) {
    try {
      return raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
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

  /** 保留一个小工具：把空白抹掉比较（某些断言只需要"有没有内容"）。 */
  static String squeeze(String text) {
    return Pattern.compile("\\s+").matcher(text).replaceAll("");
  }

  static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
