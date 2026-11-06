package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 页级「疑似漏行」判据（Issue {@code #96} 处置 A + C）。
 *
 * <h3>为什么词级置信度不够</h3>
 *
 * <p>{@code 12 §11.6} 设计的 L3 低质量判据是 {@code x_wconf < 70 ⇒ needsReview}（词级置信度）， 但 {@code #71} L1
 * 实测暴露了一类置信度<strong>根本给不出来</strong>的错误：整行丢失 / 乱码回吐 / 无中生有。
 * 那一行要么<strong>不存在</strong>、要么变成一串"看起来很有把握"的拉丁字母（实测 {@code 适用外包人员须另签协议} → {@code BABA RMA SHIN}），
 * 词级置信度对"压根没进结果"的形态无效 —— 而它直接压 B1 命中率 / B3 引用准确率（{@code silent-failure}）。
 *
 * <h3>★ 判据（可机械核对）</h3>
 *
 * <pre>
 * 偏差 = |渲染行数 − OCR 输出行数| / 渲染行数
 * 偏差 &gt; 容差（默认 5%）⇒ 整页标 needsReview（页级，不是词级）
 * </pre>
 *
 * <p>渲染行数 = 版面渲染出的文本行数（参考/渲染/OCR 三源之一，{@code #151} 判据 1 同口径）； OCR 输出行数 = 识别产物的非空行数。
 *
 * <h3>★ 已知边界（2026-11-06 现场取证，tesseract 5.5.0 · 300dpi · psm AUTO，不臆造）</h3>
 *
 * <ul>
 *   <li><b>抓得到</b>：整行丢失（行不存在）· 无中生有（版面分析把空白区切成文本块，实测第 3 页 10 行 → 14 行）。
 *   <li><b>抓不到</b>：乱码回吐（行还在、内容变拉丁乱码）—— 实测第 2 页参考 15 行 / OCR 15 行，行数不缺。 该形态由 {@code #71}
 *       的逐页字符准确率基线（{@code OcrAccuracyBaselineTest}）覆盖，两者互补。 ★ 由此暴露 {@code #96} 判据 1 的括号注解与实测不符： 「整页
 *       psm AUTO 会丢」实测是<strong>乱码回吐</strong>（行没消失）⇒ 判据 1 的"整行丢失"夹具按现象表「那一行不存在」形态合成。
 * </ul>
 *
 * <h3>排期与取舍</h3>
 *
 * <p>{@code #96} 排期 L3（后置）：生产接线（版面连通域数渲染行数 + 真去标 needsReview）随 L3 落，
 * 本类先把<strong>判据与夹具</strong>留下（issue 原话"判据 1 的夹具现在就该留着"）。 容差取 5%：宁可误报（人工复核）不许漏报 —— 漏报是本项目头号病 {@code
 * silent-failure}； 误报的代价只是多看一页。
 */
public final class OcrLineLossGuard {

  /** 默认容差：行数偏差超过渲染行数的 5% 即整页标 {@code needsReview}。 */
  public static final double DEFAULT_TOLERANCE = 0.05;

  private final double tolerance;

  public OcrLineLossGuard() {
    this(DEFAULT_TOLERANCE);
  }

  /**
   * @param tolerance 行数偏差容差（0–1 之间的小数，如 0.05 表示 5%）
   */
  public OcrLineLossGuard(double tolerance) {
    if (!(tolerance >= 0 && tolerance <= 1)) {
      throw new IllegalArgumentException("容差必须在 [0,1] 内，收到：" + tolerance);
    }
    this.tolerance = tolerance;
  }

  /** 页级判定读数（处置 C 的巡检量：渲染行数 / OCR 行数 变成可数的量）。 */
  public record LineCountVerdict(
      boolean needsReview, int renderedLines, int ocrLines, double deviation) {}

  /**
   * 按行数偏差判定一页是否「疑似漏行」。
   *
   * @param renderedLines 版面渲染出的文本行数（参考/渲染/OCR 三源之一）
   * @param ocrLines OCR 输出产物的非空行数
   */
  public LineCountVerdict check(int renderedLines, int ocrLines) {
    if (renderedLines <= 0) {
      throw new IllegalArgumentException("渲染行数必须为正，收到：" + renderedLines);
    }
    if (ocrLines < 0) {
      throw new IllegalArgumentException("OCR 行数不许为负，收到：" + ocrLines);
    }
    double deviation = (double) Math.abs(renderedLines - ocrLines) / renderedLines;
    return new LineCountVerdict(deviation > tolerance, renderedLines, ocrLines, deviation);
  }
}
