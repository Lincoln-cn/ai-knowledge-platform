package cn.lincoln.aiplatform.knowledge.infrastructure;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.helpers.DefaultHandler;

/**
 * OCR 页数的**快扫**（第 9 周 · 0.3.1）—— 不问 Tika"要不要 OCR 整篇"，而是问它"每页多少字"。
 *
 * <h3>★ 它解决什么</h3>
 *
 * <p>{@code 12 §11.8} 要求进度按页回写（「已识别 3/10 页」）—— 而分母（需要 OCR 的页数）必须在**上传后立刻** 知道。全量解析给出 {@code
 * pdf:ocrPageCount}，但那是**跑完 OCR 之后**的事（实测 3 页扫描件约 11 秒）， 拿它当分母等于"进度只有在结束时才知道"。
 *
 * <h3>★ 做法：把 OCR 关掉跑一次，读每页字符数</h3>
 *
 * <p>关掉 OCR 的那一遍只做文本层提取（实测 **0.25–0.3 秒**，对比全量 9–11 秒）， 而 {@code pdf:charsPerPage} 是 **逐页**的字符数数组 ⇒
 * 数一数有多少页 &lt; 阈值就是"需要 OCR 的页数"。 实测三个夹具（扫描件 3 页 / 混合 2 页 / 纯文本 0 页）与 Tika 的 {@code
 * pdf:ocrPageCount} **完全一致**。
 *
 * <p>★★ 关键：阈值取的是 **Tika 自己 AUTO 策略用的那个数**（{@link OcrEngine#AUTO_OCR_CHAR_THRESHOLD}）， 不是另外抄一个 ——
 * 抄一个就迟早漂移，而"分母与 Tika 的判定不一致"会表现成进度条永远到不了 100%。
 *
 * <p>★ 非 PDF 不做（返回 {@link #none()}）：OCR 只发生在图像型 PDF 上；其它格式的"页"概念也不成立。
 */
@Component
public class OcrFastScanner {

  private static final Logger log = LoggerFactory.getLogger(OcrFastScanner.class);

  /** 不涉及 OCR 时的空结果（分母 0 ⇒ 界面不显示进度行）。 */
  public static OcrPageInventory none() {
    return new OcrPageInventory(0, 0);
  }

  /**
   * 快扫：总页数 + 需要 OCR 的页数。
   *
   * <p>读出问题（损坏、加密、非 PDF）一律返回 {@link #none()} 并只记 DEBUG —— 真正的失败由后面的全量解析给出可读原因（{@code
   * DOC-I2}），这里不该抢先报一个"快扫失败"， 那会盖住用户真正需要看到的那个原因。
   */
  public OcrPageInventory scan(byte[] content, String filename) {
    if (!isPdf(filename)) {
      return none();
    }
    try {
      Metadata metadata = new Metadata();
      if (filename != null && !filename.isBlank()) {
        metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, filename);
      }
      ParseContext context = new ParseContext();
      PDFParserConfig pdfConfig = new PDFParserConfig();
      pdfConfig.setCatchIntermediateIOExceptions(false);
      // ★ 关掉 OCR：这一遍只读文本层，不渲染、不 spawn tesseract
      pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
      context.set(PDFParserConfig.class, pdfConfig);

      try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
        // 正文不进任何 handler：这一遍只要元数据
        new AutoDetectParser().parse(in, new DefaultHandler(), metadata, context);
      }

      String[] perPage = metadata.getValues("pdf:charsPerPage");
      if (perPage == null || perPage.length == 0) {
        // 逐页字符数拿不到 ⇒ 分母只能不给（进度退化成"进行中"），不猜
        int pages = intOf(metadata.get("xmpTPg:NPages"));
        log.debug("OCR 快扫：{} 拿不到 pdf:charsPerPage，总页数 {}", filename, pages);
        return new OcrPageInventory(pages, 0);
      }
      int ocrPages = 0;
      for (String value : perPage) {
        if (intOf(value) < OcrEngine.AUTO_OCR_CHAR_THRESHOLD) {
          ocrPages++;
        }
      }
      return new OcrPageInventory(perPage.length, ocrPages);
    } catch (Exception e) {
      log.debug("OCR 快扫失败（{}）：{} —— 交给全量解析给出可读原因", filename, e.toString());
      return none();
    }
  }

  private static boolean isPdf(String filename) {
    return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
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

  /**
   * 快扫结论。
   *
   * @param pageCount 总页数（0 = 不是分页格式 / 读不出来）
   * @param ocrPages 其中需要 OCR 的页数（= 进度分母）
   */
  public record OcrPageInventory(int pageCount, int ocrPages) {}
}
