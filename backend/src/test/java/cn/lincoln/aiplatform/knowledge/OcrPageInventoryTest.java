package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrFastScanner;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「需要 OCR 的页数」快扫 + 「每页处理完」逐页信号（第 9 周 · 0.3.1）。
 *
 * <h3>★ 这一组钉的是「已识别 N/M 页」这条链的两个前提</h3>
 *
 * <ol>
 *   <li><b>分母</b>：{@link OcrFastScanner} 关掉 OCR 快扫，从 {@code pdf:charsPerPage} 数出需要 OCR 的页数。 ★
 *       关键断言是<strong>它必须等于 Tika 全量解析报的 {@code pdf:ocrPageCount}</strong> —— 两者不一致的表现是「进度条永远到不了
 *       100%」，而且**不会报错**。
 *   <li><b>分子</b>：{@link TikaMarkdownParser.PageProgressListener} 每处理完一页被调一次， 且能区分这一页是否走了
 *       OCR。它依赖一条实测事实：Tika 的 PDF 输出里每页是 {@code <div class="page">}，走了 OCR 的页里还有 {@code <div
 *       class="ocr">}。
 * </ol>
 *
 * <p>★ 为什么不用覆写 {@code TesseractOCRParser} 拿逐页：它的两个 {@code parse} 重载是 public， 但 Tika 内部逐页真正调的是
 * {@code org.apache.tika.parser.pdf.AbstractPDF2XHTML#doOCROnCurrentPage} ——
 * <strong>包内方法，覆写不了</strong>。实测：覆写那两个 public 重载后计数**恒为 0**， 而同一次解析的 {@code pdf:ocrPageCount} 是 3（说明
 * OCR 确实跑了）。所以逐页信号只能从事件流拿。
 *
 * <p>三个夹具的 OCR 页数已知：{@code structured.pdf}=0 页 · {@code mixed.pdf}=2 页 · {@code scanned.pdf}=3 页。
 */
class OcrPageInventoryTest {

  private static byte[] fixture(String name) throws Exception {
    try (InputStream in = OcrPageInventoryTest.class.getResourceAsStream("/fixtures/" + name)) {
      assertThat(in).as("夹具不在 classpath：" + name).isNotNull();
      return in.readAllBytes();
    }
  }

  private static OcrEngine engine() {
    return new OcrEngine(new OcrProperties(true, null, null, "chi_sim+eng", 300, 120, true));
  }

  @Test
  void 快扫的需求页数必须与全量解析报的ocrPageCount一致() throws Exception {
    OcrEngine engine = engine();
    if (!engine.available()) {
      // 本机没有引擎 ⇒ 全量那一半跑不了。快扫仍然可测（它本来就不需要引擎）。
      // ★ 但"两者一致"这条断言只能在有引擎时验 —— 所以这里**明确跳过全部**，
      //   而不是只跳过一半、把另一半当成"验过了"。
      System.out.println("★ 本机没有可用的 tesseract ⇒ 跳过（真栈闸门 check_ocr_e2e.py 是端到端证据）");
      return;
    }
    OcrFastScanner scanner = new OcrFastScanner();
    TikaMarkdownParser parser = new TikaMarkdownParser(engine);

    for (String[] expect :
        new String[][] {{"structured.pdf", "0"}, {"mixed.pdf", "2"}, {"scanned.pdf", "3"}}) {
      String name = expect[0];
      int expectedOcrPages = Integer.parseInt(expect[1]);
      byte[] bytes = fixture(name);

      OcrFastScanner.OcrPageInventory inventory = scanner.scan(bytes, name);
      ParseResult full = parser.parse(bytes, name);

      assertThat(inventory.ocrPages()).as("快扫算出的需求页数（进度分母）· " + name).isEqualTo(expectedOcrPages);
      assertThat(full.ocrPageCount())
          .as("全量解析报的 ocrPageCount（最终事实）· " + name)
          .isEqualTo(expectedOcrPages);
      assertThat(inventory.ocrPages())
          .as("★ 两处口径必须一致，否则进度条永远到不了 100% · " + name)
          .isEqualTo(full.ocrPageCount());
    }
  }

  @Test
  void 非PDF返回到处为0() {
    OcrFastScanner scanner = new OcrFastScanner();
    OcrFastScanner.OcrPageInventory inventory = scanner.scan("随便什么".getBytes(), "a.txt");
    assertThat(inventory.pageCount()).as("非 PDF 不做快扫（OCR 只发生在图像型 PDF 上）").isZero();
    assertThat(inventory.ocrPages()).isZero();
  }

  @Test
  void 拿不到逐页字符数时不猜分母() {
    OcrFastScanner scanner = new OcrFastScanner();
    // 不是真 PDF 但扩展名对 ⇒ 解析会失败；★ 失败要返回 0 而不是抛，
    // 否则"快扫失败"会盖住全量解析本该给出的可读原因（DOC-I2）
    OcrFastScanner.OcrPageInventory inventory =
        scanner.scan("not a pdf at all".getBytes(), "broken.pdf");
    assertThat(inventory.ocrPages()).isZero();
  }

  @Test
  void 逐页回调的页数与OCR页数都对得上() throws Exception {
    OcrEngine engine = engine();
    if (!engine.available()) {
      System.out.println("★ 本机没有可用的 tesseract ⇒ 跳过逐页回调断言");
      return;
    }
    TikaMarkdownParser parser = new TikaMarkdownParser(engine);

    for (String[] expect :
        new String[][] {
          {"structured.pdf", "1", "0"}, {"mixed.pdf", "3", "2"}, {"scanned.pdf", "3", "3"}
        }) {
      String name = expect[0];
      int expectedPages = Integer.parseInt(expect[1]);
      int expectedOcrPages = Integer.parseInt(expect[2]);

      List<Integer> allPages = new ArrayList<>();
      List<Integer> ocrPages = new ArrayList<>();
      parser.parse(
          fixture(name),
          name,
          (pageIndex, ocrPage) -> {
            allPages.add(pageIndex);
            if (ocrPage) {
              ocrPages.add(pageIndex);
            }
          });

      assertThat(allPages)
          .as("逐页回调的页数与文档页数一致 · " + name)
          .hasSize(expectedPages)
          .isEqualTo(IntRange.of(expectedPages));
      assertThat(ocrPages).as("其中标记为「走了 OCR」的页数 · " + name).hasSize(expectedOcrPages);
    }
  }

  /** 1..n 的整数列表（断言"按顺序、不重不漏"）。 */
  private static final class IntRange {
    static List<Integer> of(int n) {
      List<Integer> list = new ArrayList<>(n);
      for (int i = 1; i <= n; i++) {
        list.add(i);
      }
      return list;
    }
  }
}
