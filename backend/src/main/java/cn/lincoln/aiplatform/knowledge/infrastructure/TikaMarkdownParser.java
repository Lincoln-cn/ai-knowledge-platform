package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika 解析链入口（第 6 周 · 0.2.1）—— DOCX / PDF / TXT / MD → Markdown。
 *
 * <p>★ 选型与版本口径见 {@code docs/系统设计方案/12-文档加工链设计.md §一}： {@code tika-core} + 三个细粒度 parser 模块（不是 {@code
 * tika-parsers-standard-package}，也不是纯 core + 自写 PDFBox/POI 胶水）。 产物形态是 <strong>Markdown</strong>
 * （{@code ToMarkdownContentHandler}，自 Tika 3.3.0 起在 core）， 分块器靠标题/列表/表格语法切语义边界（{@code 12 §2.1}）。
 *
 * <p>★ 编码检测走 Tika {@code CharsetDetector}（{@code 12 §一}），不另引编码库 —— text 模块自带探测， GBK
 * 中文在探测命中时不会变成替换字符。
 *
 * <p>★ 失败口径（{@code DOC-I2} / {@code 12 §2.1}）： {@link ParseResult#failReason()}
 * 必须<strong>可读</strong> （「PDF 加密，无法解析」这种），不是堆栈或异常类名； {@link ParseResult#markdown()} 保留 handler
 * 已缓冲的片段，不整篇丢。
 *
 * <h3>★ OCR（第 9 周 · 0.3.1）：引擎不可用时给<strong>分档</strong>的原因</h3>
 *
 * <p>「扫描件抽不出文本」有两种完全不同的成因，{@code 12 §11} 与不 §11 的下一步建议不一样：
 *
 * <ul>
 *   <li><strong>引擎可用</strong> ⇒ 那就是真的没内容（空文档 / 损坏 / 整页是图但也没字）
 *   <li>★ <strong>引擎不可用</strong> ⇒ 得说清是"这台机器没装 OCR"，并指路装什么 —— 否则用户会以为文件坏了，反复重新导出、重新上传
 * </ul>
 *
 * <p>⇒ 这里注入 {@link OcrEngine} 问一次可用性，并把结论带进失败文案。
 */
@org.springframework.stereotype.Component
public class TikaMarkdownParser {

  /** 扩展名白名单（{@code 09 §10.2 #2}：改扩展名绕过 ⇒ MIME 嗅探不匹配即拒）。 与批 1 解析范围对齐：DOCX / PDF / TXT / MD。 */
  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("docx", "doc", "pdf", "txt", "md", "markdown");

  /**
   * 逐页判定阈值：每页字符数 &lt; 10 ⇒ 该页走 OCR（{@code 12 §11.3}）。
   *
   * <p>★ 格式口径按<b>实测</b>修正：设计册抄的是 Tika 4.0 的 <strong>JSON</strong> 块 （{@code
   * {"totalCharsPerPage":10,...}}），而 3.3.1 的 {@code setOcrStrategyAuto(String)} 要的是 <strong>{@code
   * "num[%], num"}</strong> 短格式 —— 照抄设计册会直接抛 {@code Error parsing OCRStrategyAuto}。
   */
  private static final String OCR_STRATEGY_AUTO = "10, 10";

  private final OcrEngine ocr;

  public TikaMarkdownParser(OcrEngine ocr) {
    this.ocr = ocr;
  }

  /**
   * 把文件字节解析成 Markdown。
   *
   * <p>★ 失败时 {@link ParseResult#markdown()} 仍返回已缓冲的部分（{@code 12 §2.1}）， {@link
   * ParseResult#failReason()} 给出<strong>可读</strong>原因（{@code DOC-I2}）—— 不是异常堆栈。
   *
   * @param content 文件原始字节
   * @param filename 原始文件名（含扩展名，用于格式白名单与错误文案）
   */
  public ParseResult parse(byte[] content, String filename) {
    if (content == null || content.length == 0) {
      return ParseResult.failed("文件内容为空，无法解析。", "");
    }

    // ★ 格式白名单先挡一层：非文档扩展名不必进 Tika（09 §10.2 #2）
    String ext = extensionOf(filename);
    if (!ALLOWED_EXTENSIONS.contains(ext)) {
      return ParseResult.failed(
          "不支持的文件类型（.%s）。当前支持 DOCX / PDF / TXT / Markdown。".formatted(ext.isEmpty() ? "?" : ext),
          "");
    }

    // ★ Word 列表归一：OOXML 只给 p 打 class="list_*"，包成 ul/ol/li 后 Markdown 才有列表语法
    ToMarkdownContentHandler markdown = new ToMarkdownContentHandler();
    WordListNormalizingHandler handler = new WordListNormalizingHandler(markdown);
    Metadata metadata = new Metadata();
    if (filename != null && !filename.isBlank()) {
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
    }

    boolean ocrAvailable = ocr.available();
    try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
      // ★ AutoDetectParser 靠 SPI 装配 parser 模块；编码探测在 text 模块内完成（12 §一）
      ParseContext context = new ParseContext();
      context.set(PDFParserConfig.class, pdfConfig(ocrAvailable));
      if (ocrAvailable) {
        context.set(TesseractOCRConfig.class, tesseractConfig());
        context.set(TesseractOCRParser.class, ocr.newParser());
      }
      new AutoDetectParser().parse(in, handler, metadata, context);
      // ★ 0 字符 ≠ 成功。输入非空却抽出 0 字符（PDFBox 吞掉损坏、扫描件无文本层）时
      //   若仍算成功，界面会显示「已解析」而内容是空的 —— 这是最坏的失败形态（静默）。
      //   ★ 第 9 周起这里要分档：引擎在不在，下一步做的事完全不同。
      String extracted = markdown.toString();
      if (extracted.isBlank()) {
        return ocrAvailable
            ? ParseResult.failed(describeEmptyResult(filename, content.length), "")
            : ocrUnavailableResult(filename, content.length, ocr.status());
      }
      return ParseResult.ok(extracted);
    } catch (EncryptedDocumentException e) {
      return ParseResult.failed(describeEncrypted(filename), markdown.toString());
    } catch (TikaException | SAXException | RuntimeException e) {
      return ParseResult.failed(describeFailure(e, filename), markdown.toString());
    } catch (Exception e) {
      // ★ 兜底：任何异常都不许把堆栈当原因抛给用户（DOC-I2）
      return ParseResult.failed(describeFailure(e, filename), markdown.toString());
    }
  }

  // ────────────────────────── OCR 配置 ──────────────────────────

  /**
   * PDF 解析配置。
   *
   * <p>★ OCR 不可用时显式置 {@code NO_OCR}：Tika 默认是 {@code NO_OCR}，但**别依赖默认值** —— 一旦哪天默认改成
   * AUTO，一台没装引擎的机器会在每页上都尝试一次 spawn 并失败， 症状是"解析变慢且原因不明"。
   */
  private PDFParserConfig pdfConfig(boolean ocrAvailable) {
    PDFParserConfig pdfConfig = new PDFParserConfig();
    // ★ 不要吞掉中间 IOException：默认会 catch 掉内容流解码错误 ⇒「页面丢了」静默发生。
    //   关掉后中途失败会带着 handler 里已缓冲的片段抛出（12 §2.1 的 catch 场景）。
    pdfConfig.setCatchIntermediateIOExceptions(false);
    if (ocrAvailable) {
      pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.AUTO);
      pdfConfig.setOcrStrategyAuto(OCR_STRATEGY_AUTO);
      pdfConfig.setOcrDPI(ocr.properties().dpi());
      pdfConfig.setOcrRenderingStrategy(PDFParserConfig.OCR_RENDERING_STRATEGY.ALL);
    } else {
      pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    }
    return pdfConfig;
  }

  private TesseractOCRConfig tesseractConfig() {
    TesseractOCRConfig tess = new TesseractOCRConfig();
    tess.setLanguage(ocr.properties().languages());
    // ★ HOCR 而不是 TXT：只有 hOCR 带 bbox / x_wconf（低置信度标 needsReview 与页眉页脚去重都靠它，12 §11.4）
    tess.setOutputType(TesseractOCRConfig.OUTPUT_TYPE.HOCR);
    tess.setDensity(ocr.properties().dpi());
    tess.setEnableImagePreprocessing(ocr.properties().preprocess());
    tess.setTimeoutSeconds(ocr.properties().timeoutSeconds());
    return tess;
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 加密文档的可读原因。★ 不泄漏密码策略细节，但要指明下一步。 */
  private static String describeEncrypted(String filename) {
    if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      return "PDF 加密，无法解析。";
    }
    return "文档已加密，无法解析。";
  }

  /**
   * 抽出 0 字符的可读原因（<strong>OCR 可用</strong>时）。★ 这不是「解析异常」，而是「解析出了空气」—— 文案要指路（文件损坏 / 真空文档）， 不能只说「解析失败」。
   */
  private static String describeEmptyResult(String filename, int byteLength) {
    return "解析结果为空：可能是空文档或文件损坏（文件 %s，%d 字节）"
        .formatted(filename == null ? "?" : filename, byteLength);
  }

  /**
   * 抽出 0 字符、<strong>而本环境没有可用的 OCR 引擎</strong>时的可读原因 + 下一步。
   *
   * <p>★ 这一档必须与上一条分开：它们的下一步完全不同 —— 「装 OCR 引擎」vs「换一份文件」。写在一条里，用户会去反复重新导出那个本来就好的扫描件。
   */
  private static ParseResult ocrUnavailableResult(
      String filename, int byteLength, OcrEngine.Status status) {
    String reason =
        "解析结果为空：这份文件很可能没有文本层（扫描件 / 图像型 PDF），"
            + "而本环境没有可用的 OCR 引擎 —— %s（文件 %s，%d 字节）"
                .formatted(status.reason(), filename == null ? "?" : filename, byteLength);
    return ParseResult.failed(reason, status.hint(), "");
  }

  /** 把异常翻成可读原因。★ 纪律：不把异常类名 / 堆栈放进 failReason —— 用户改不了也看不懂（{@code 08 §4.1}）。 */
  private static String describeFailure(Exception e, String filename) {
    String name = filename == null ? "" : filename;
    String type = name.toLowerCase(Locale.ROOT);
    // 损坏的 OOXML / ZIP：多半是截断或非 Office XML
    if (type.endsWith(".docx") || type.endsWith(".doc")) {
      return "文档已损坏或不是有效的 Word 文件，无法解析。";
    }
    if (type.endsWith(".pdf")) {
      return "PDF 已损坏或格式异常，无法解析。";
    }
    if (e instanceof TikaException || e instanceof SAXException) {
      return "文档格式异常，无法解析。";
    }
    return "解析失败，请检查文件是否完整后重试。";
  }
}
