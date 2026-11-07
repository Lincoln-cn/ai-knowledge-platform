package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.apache.tika.sax.ToMarkdownContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
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

  private static final Logger log = LoggerFactory.getLogger(TikaMarkdownParser.class);

  /** 扩展名白名单（{@code 09 §10.2 #2}：改扩展名绕过 ⇒ MIME 嗅探不匹配即拒）。 与批 1 解析范围对齐：DOCX / PDF / TXT / MD。 */
  private static final Set<String> ALLOWED_EXTENSIONS =
      Set.of("docx", "doc", "pdf", "txt", "md", "markdown");

  private final OcrEngine ocr;

  public TikaMarkdownParser(OcrEngine ocr) {
    this.ocr = ocr;
  }

  /**
   * 逐页回调（第 9 周 · 0.3.1）—— 每**处理完**一页调一次，用来回写"已识别 N/M 页"。
   *
   * <p>★ 这个接口存在的前提是一条**实测事实**：Tika 的 PDF 输出里每页是一个 {@code <div class="page">}，而走了 OCR 的页在里面还有一个
   * {@code <div class="ocr">}。 三个夹具实测（纯文本 0 / 混合 2 / 扫描件 3），{@code class="ocr"} 的个数与元数据 {@code
   * pdf:ocrPageCount} **完全一致** ⇒ "第 N 页做完了"是可观测事实，不必自己拆 PDF 逐页跑。
   *
   * <p>★ 为什么不用覆写解析器：{@code TesseractOCRParser.parse(...)} 是 public，但 Tika 内部真正逐页调的是 {@code
   * org.apache.tika.parser.pdf.AbstractPDF2XHTML#doOCROnCurrentPage} ——
   * <strong>包内方法，覆写不了</strong>（实测覆写两个 public 重载后计数恒为 0）。
   */
  @FunctionalInterface
  public interface PageProgressListener {

    /**
     * @param pageIndex 第几页（1 起，按处理顺序）
     * @param ocrPage 这一页是否走了 OCR
     */
    void onPageDone(int pageIndex, boolean ocrPage);
  }

  /**
   * 数页边界的 SAX 装饰器：把"一页做完了"翻译成回调。
   *
   * <p>用 Tika 自带的 {@link ContentHandlerDecorator}（默认全量转发）而不是手写一层委托 —— 手写就得把 SAX
   * 那一堆方法逐个转发，漏一个就是"某种文档解析出来是空的"。
   */
  private static final class PageMarkerHandler extends ContentHandlerDecorator {

    private final PageProgressListener listener;
    private int depth = 0;

    /** 当前 page div 所在的深度（-1 = 不在 page 里）。 */
    private int pageDepth = -1;

    private boolean pageUsedOcr = false;
    private int pageIndex = 0;

    PageMarkerHandler(ContentHandler inner, PageProgressListener listener) {
      super(inner);
      this.listener = listener;
    }

    @Override
    public void startElement(String uri, String localName, String name, Attributes atts)
        throws SAXException {
      depth++;
      if ("div".equals(localName) || "div".equals(name)) {
        String classes = atts.getValue("class");
        if (classes != null && classes.contains("page")) {
          pageDepth = depth;
          pageUsedOcr = false;
        } else if (classes != null && classes.contains("ocr") && pageDepth >= 0) {
          pageUsedOcr = true;
        }
      }
      super.startElement(uri, localName, name, atts);
    }

    @Override
    public void endElement(String uri, String localName, String name) throws SAXException {
      super.endElement(uri, localName, name);
      if (depth == pageDepth) {
        pageIndex++;
        listener.onPageDone(pageIndex, pageUsedOcr);
        pageDepth = -1;
      }
      depth--;
    }
  }

  /**
   * 把文件字节解析成 Markdown（不带逐页回调）。
   *
   * @param content 文件原始字节
   * @param filename 原始文件名（含扩展名，用于格式白名单与错误文案）
   */
  public ParseResult parse(byte[] content, String filename) {
    return parse(content, filename, null);
  }

  /**
   * 把文件字节解析成 Markdown。
   *
   * <p>★ 失败时 {@link ParseResult#markdown()} 仍返回已缓冲的部分（{@code 12 §2.1}）， {@link
   * ParseResult#failReason()} 给出<strong>可读</strong>原因（{@code DOC-I2}）—— 不是异常堆栈。
   *
   * @param content 文件原始字节
   * @param filename 原始文件名（含扩展名，用于格式白名单与错误文案）
   * @param listener 逐页回调（可为 null）；★ 会被解析线程**同步**调用，实现要短 —— 见 {@link PageProgressListener}
   */
  public ParseResult parse(byte[] content, String filename, PageProgressListener listener) {
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
    // ── #96：逐页「OCR 产出了几行」的可观测面 ─────────────────────────────────────
    //
    // ★ 为什么不是 OcrLineLossGuard 的行数比对：那个 guard 要 (renderedLines, ocrLines) 两个数，
    //   而**这一页之所以走 OCR，正是因为它没有文本层** ⇒ renderedLines 对 OCR 页永远取不到。
    //   这是逻辑上的不可能，不是实现偷懒（#96 的判据 1 因此改不成读数，见 issue 评论）。
    //
    // ⇒ 这里退一步，只留「这一页 OCR 出了几行」这一个可数的量：
    //   整页丢（行数 0）与「只出来一两个字」（行数 ≤ 1）能被一眼看到，
    //   这是 silent-failure 的最低止血；细粒度丢行仍需人工抽查（#87 的抽查表）。
    //
    // ★ 用 markdown 的**累计行数差**作页级读数：PageMarkerHandler 在页码 div 结束时回调，
    //   此刻 markdown 里已经有这一页的全部产出。第 i 页的行数 = 第 i 页累计 − 第 i−1 页累计。
    List<int[]> pageLines = new ArrayList<>(); // 每项 = {pageIndex, 累计行数, 是否 OCR 页}
    PageProgressListener counting =
        (idx, ocrPage) ->
            pageLines.add(new int[] {idx, countLines(markdown.toString()), ocrPage ? 1 : 0});
    PageProgressListener effective =
        listener == null
            ? counting
            : (idx, ocrPage) -> {
              counting.onPageDone(idx, ocrPage);
              listener.onPageDone(idx, ocrPage);
            };
    WordListNormalizingHandler handler =
        new WordListNormalizingHandler(new PageMarkerHandler(markdown, effective));
    Metadata metadata = new Metadata();
    if (filename != null && !filename.isBlank()) {
      metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
    }

    boolean ocrAvailable = ocr.available();
    try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
      // ★ AutoDetectParser 靠 SPI 装配 parser 模块；编码探测在 text 模块内完成（12 §一）
      ParseContext context = new ParseContext();
      PDFParserConfig pdfConfig = new PDFParserConfig();
      // ★ 不要吞掉中间 IOException：默认会 catch 掉内容流解码错误 ⇒「页面丢了」静默发生。
      //   关掉后中途失败会带着 handler 里已缓冲的片段抛出（12 §2.1 的 catch 场景）。
      pdfConfig.setCatchIntermediateIOExceptions(false);
      // ★★ OCR 的全部配置（引擎、产物形态、语言、DPI、逐页判定阈值…）交给 OcrEngine ——
      //    本类<strong>不出现任何 Tesseract 类型</strong>：引擎知识只有一处。
      //    判据：`grep -rn "Tesseract" backend/src/main` 只应命中 OcrEngine（javadoc 提及除外）。
      ocr.configure(pdfConfig, context);
      context.set(PDFParserConfig.class, pdfConfig);
      new AutoDetectParser().parse(in, handler, metadata, context);
      // ★ 0 字符 ≠ 成功。输入非空却抽出 0 字符（PDFBox 吞掉损坏、扫描件无文本层）时
      //   若仍算成功，界面会显示「已解析」而内容是空的 —— 这是最坏的失败形态（静默）。
      //   ★ 第 9 周起这里要分档：引擎在不在，下一步做的事完全不同。
      String extracted = markdown.toString();
      int pageCount = pageCountOf(metadata);
      int ocrPageCount = intOf(metadata.get("pdf:ocrPageCount"));
      if (ocrPageCount > 0) {
        // ★ 这一行是「这份文件有几页是扫描件」的可观测面（12 §11.3 要求）。
        //   现在落在日志里；等 T249.3 把两个字段接进契约与库之后，它会同时出现在界面上
        //   （「已识别 3/10 页」的进度就靠它）。★ 先让它可见，而不是先加一个没人读的字段。
        log.info("OCR：{} 共 {} 页，其中 {} 页走了 OCR", filename, pageCount, ocrPageCount);
        logOcrPageLines(filename, pageLines);
      }
      if (extracted.isBlank()) {
        return ocrAvailable
            ? ParseResult.failed(
                describeEmptyResult(filename, content.length), null, "", pageCount, ocrPageCount)
            : ocrUnavailableResult(filename, content.length, ocr.status(), pageCount, ocrPageCount);
      }
      return ParseResult.ok(extracted, pageCount, ocrPageCount);
    } catch (EncryptedDocumentException e) {
      return ParseResult.failed(describeEncrypted(filename), markdown.toString());
    } catch (TikaException | SAXException | RuntimeException e) {
      return ParseResult.failed(describeFailure(e, filename), markdown.toString());
    } catch (Exception e) {
      // ★ 兜底：任何异常都不许把堆栈当原因抛给用户（DOC-I2）
      return ParseResult.failed(describeFailure(e, filename), markdown.toString());
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 数一数当前 Markdown 缓冲里有多少非空行（`#96` 的页级读数单位）。 */
  private static int countLines(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int lines = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        lines++;
      }
    }
    return text.charAt(text.length() - 1) == '\n' ? lines : lines + 1;
  }

  /**
   * `#96`：把「每一页 OCR 出了几行」打进日志，整页丢 / 只出一两个字时额外 WARN。
   *
   * <p>★ 这里<strong>不</strong>做「渲染行数 vs OCR 行数」的比对：那一页之所以走 OCR，正是因为它没有文本层 —— 参考值对 OCR 页永远不存在（`#96`
   * 判据 1 因此改不成读数，见 issue 评论）。能数出来的只有"这一页产出了多少"。
   *
   * <p>★ 永远不抛：这条观测是**附加信息**，不许它把一次成功的解析变成失败。
   */
  private static void logOcrPageLines(String filename, List<int[]> pageLines) {
    try {
      if (pageLines.isEmpty()) {
        return;
      }
      int previous = 0;
      for (int[] row : pageLines) {
        int pageIndex = row[0];
        int cumulative = row[1];
        boolean ocrPage = row[2] == 1;
        int lines = Math.max(0, cumulative - previous);
        previous = cumulative;
        if (!ocrPage) {
          continue;
        }
        if (lines <= 1) {
          // 整页丢字（0 行）或只出来一两个字（1 行）—— `#96` 要止血的正是这两种形态。
          log.warn("#96 OCR 页产出过少：{} 第 {} 页只产出 {} 行，疑似整页丢字，请人工核对", filename, pageIndex, lines);
        } else {
          log.info("#96 OCR 页产出：{} 第 {} 页 {} 行", filename, pageIndex, lines);
        }
      }
    } catch (RuntimeException e) {
      log.debug("#96 页级 OCR 读数统计失败（不影响解析）：{}", e.toString());
    }
  }

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
      String filename, int byteLength, OcrEngine.Status status, int pageCount, int ocrPageCount) {
    String reason =
        "解析结果为空：这份文件很可能没有文本层（扫描件 / 图像型 PDF），"
            + "而本环境没有可用的 OCR 引擎 —— %s（文件 %s，%d 字节）"
                .formatted(status.reason(), filename == null ? "?" : filename, byteLength);
    return ParseResult.failed(reason, status.hint(), "", pageCount, ocrPageCount);
  }

  /**
   * 总页数：优先 `pdf:charsPerPage` 的**取值个数**（那是逐页的），退回 `xmpTPg:NPages`。
   *
   * <p>★ 两者都来自 Tika 的元数据 —— 与 OCR 页数一样，**不自己数**。
   */
  private static int pageCountOf(Metadata metadata) {
    String[] perPage = metadata.getValues("pdf:charsPerPage");
    if (perPage != null && perPage.length > 0) {
      return perPage.length;
    }
    return intOf(metadata.get("xmpTPg:NPages"));
  }

  private static int intOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
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
