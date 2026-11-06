package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.OcrLineLossGuard;
import org.junit.jupiter.api.Test;

/**
 * 页级「疑似漏行」判据的三条可核对判据（Issue {@code #96}）+ 一条已知盲区的如实钉住。
 *
 * <h3>★ 数据出处（实测，非推断）</h3>
 *
 * <p>全部行数来自 {@code fixtures/scanned.pdf}（{@code make_ocr_fixtures.py} 从 {@code noisy.pdf} 逐页光栅化造的
 * <b>合成扫描件，不是客户真实件</b>）：
 *
 * <ul>
 *   <li>渲染行数 = {@code fixtures/ocr-ground-truth.json} {@code scanned.pdf.<页>} 的非空行数（10 / 15 / 10）
 *   <li>OCR 行数 = 2026-11-06 现场取证：pypdfium2 300dpi 渲染 → tesseract 5.5.0 {@code -l chi_sim+eng --dpi
 *       300 -c preserve_interword_spaces=1}（psm AUTO）逐页产物的非空行数（10 / 15 / 14）， 与 {@code
 *       OcrAccuracyBaselineTest} 写死的 {@code #71} 实测产物逐字一致（两条独立路径互证）
 * </ul>
 *
 * <p>★ 判据 1 的夹具是<b>合成删行变体</b>：把第 2 页实测 15 行产物里的 {@code BABA RMA SHIN} 行删掉， 模拟 {@code #96}
 * 现象表第一形态「那一行<b>不存在</b>」（整行丢失）。 现场取证表明 {@code #96} 判据 1 括号注解「整页 psm AUTO 会丢」实测是<b>乱码回吐</b>（行没消失，见第
 * 4 条用例）—— 该形态行数比对抓不到，由 {@code #71} 字符准确率基线覆盖，两者互补。
 */
class OcrLineLossGuardTest {

  private final OcrLineLossGuard guard = new OcrLineLossGuard();

  /** 实测行数（2026-11-06 现场取证，与 {@code #71} 记录产物互证）：页 1 / 页 2 / 页 3。 */
  private static final int[] RENDERED = {10, 15, 10};

  private static final int[] OCR = {10, 15, 14};

  /**
   * ★ 判据 1：整行丢失（那行根本没进结果）⇒ 判据必须报红。
   *
   * <p>夹具 = 第 2 页实测产物删掉 {@code BABA RMA SHIN} 行（渲染 15 行 / OCR 14 行，偏差 6.7% &gt; 5%）。
   */
  @Test
  void wholeLineLossIsFlaggedWhenOcrOutputMissesARow() {
    OcrLineLossGuard.LineCountVerdict verdict = guard.check(RENDERED[1], OCR[1] - 1);
    assertThat(verdict.needsReview())
        .as("整行丢失（渲染 15 行 / OCR 14 行，合成删行夹具）必须报 needsReview（#96 判据 1）；偏差=%.4f", verdict.deviation())
        .isTrue();
  }

  /** ★ 判据 2：正常页（第 1 页，10:10 实测）不许被误报 —— 常红的闸门会被关掉。 */
  @Test
  void normalPageIsNotFlagged() {
    OcrLineLossGuard.LineCountVerdict verdict = guard.check(RENDERED[0], OCR[0]);
    assertThat(verdict.needsReview())
        .as("正常页（渲染 10 行 / OCR 10 行）不许误报（#96 判据 2），偏差=%.4f", verdict.deviation())
        .isFalse();
  }

  /** 无中生有（第 3 页实测 10:14，版面分析把空白区切成文本块）同族 ⇒ 必须报红。 */
  @Test
  void phantomRowsFromLayoutSplitAreFlagged() {
    OcrLineLossGuard.LineCountVerdict verdict = guard.check(RENDERED[2], OCR[2]);
    assertThat(verdict.needsReview())
        .as("无中生有（渲染 10 行 / OCR 14 行，实测）必须报 needsReview，偏差=%.4f", verdict.deviation())
        .isTrue();
  }

  /**
   * ★ 已知盲区的如实钉住：乱码回吐（第 2 页实测 15:15）行数不缺 ⇒ 本判据不报。
   *
   * <p>这条断言钉住的是<b>边界本身</b>：后人若以为行数比对能抓乱码回吐，这条会红。 该形态的读数在 {@code OcrAccuracyBaselineTest}（逐页字符准确率）。
   */
  @Test
  void garbledRowWithoutLineLossIsKnownBlindSpot() {
    OcrLineLossGuard.LineCountVerdict verdict = guard.check(RENDERED[1], OCR[1]);
    assertThat(verdict.needsReview())
        .as("乱码回吐（渲染 15 行 / OCR 15 行）行数不缺 ⇒ 行数比对不报 —— 这是已知盲区不是回归")
        .isFalse();
    assertThat(verdict.deviation()).as("乱码回吐的行数偏差应为 0").isZero();
  }

  /** ★ 处置 C 的巡检读数：每页「渲染行数 / OCR 行数」变成可数的量（打表，可进文档）。 */
  @Test
  void lineCountOverviewIsPrintedForInspection() {
    StringBuilder table = new StringBuilder("=== 页级行数巡检（Issue #96 处置 C）===\n");
    table.append("| 页 | 渲染行数 | OCR 行数 | 偏差 | needsReview |\n|---:|---:|---:|---:|:---:|\n");
    for (int i = 0; i < RENDERED.length; i++) {
      OcrLineLossGuard.LineCountVerdict verdict = guard.check(RENDERED[i], OCR[i]);
      table.append(
          String.format(
              java.util.Locale.ROOT,
              "| %d | %d | %d | %.2f%% | %s |%n",
              i + 1,
              verdict.renderedLines(),
              verdict.ocrLines(),
              verdict.deviation() * 100,
              verdict.needsReview() ? "是" : "否"));
    }
    System.out.println(table);
  }
}
