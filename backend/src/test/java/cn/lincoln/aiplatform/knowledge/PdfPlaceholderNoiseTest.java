package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.NoiseStripper;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * 解析器图片占位标记（`#51`）的端到端读数：**自己造一份源文本里带占位行的 PDF**，走完整的 解析 → 噪声剥离链，断言占位标记消失、图注还在。
 *
 * <h3>★ 为什么这条测试要自造 PDF</h3>
 *
 * <p>用户报的样本（清华版 DeepSeek PDF）不在仓库里，而这条缺陷的取证结论本身就是 「我们这条链**不产生** {@code [PDF N]}」—— 4 份夹具与一份自造图文混排
 * PDF（PDFBox 画了图） 解析后命中数都是 0。既然它只可能来自**源 PDF 的文本层**，那唯一能稳定复现的办法就是： 把源文件造成那样。
 *
 * <p>★ 并因此把两件事分开写在断言里：
 *
 * <ol>
 *   <li>解析器**不会**凭空产生标记 —— 源文本里没有标记，走完 {@link TikaMarkdownParser} 就一个都没有；
 *   <li>源文件**带着**标记时，{@link NoiseStripper} 必须整行剥掉，且图注一个字不动。
 * </ol>
 */
class PdfPlaceholderNoiseTest {

  /** 占位标记形态（与 `NoiseStripper` 同一口径；这里另写一份，是为了独立断言「剥离后不再出现」）。 */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("^\\\\?\\[\\s*(?:PDF|pdf|Pdf)\\s*\\d{1,3}\\s*\\\\?\\]$");

  /**
   * 这段文本里还有没有占位标记（逐行匹配）。
   *
   * <p>★ 不用 {@code find()} 直接扫整段：这条正则是带 {@code ^…$} 的整行判据， 不加 {@link Pattern#MULTILINE} 时 {@code
   * ^}/{@code $} 只认整段文本的首尾 —— 那样写出来的断言对「中间几行有标记」永远为假，看着很绿其实什么都没测。
   */
  private static boolean hasPlaceholder(String markdown) {
    for (String line : markdown.split("\\R?\\n")) {
      if (PLACEHOLDER.matcher(line.strip()).matches()) {
        return true;
      }
    }
    return false;
  }

  private static TikaMarkdownParser parser() {
    return new TikaMarkdownParser(
        new OcrEngine(new OcrProperties(false, null, null, "chi_sim+eng", 300, 120, true)));
  }

  /** 造一份源文本里带着占位行与图注的 PDF（Helvetica 只有 ASCII，所以正文用英文）。 */
  private static byte[] pdfWith(String... textLines) throws Exception {
    Path out = Path.of("target", "placeholder-fixture.pdf");
    Files.createDirectories(out.getParent());
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        cs.setLeading(18f);
        cs.newLineAtOffset(50, 720);
        for (String line : textLines) {
          cs.showText(line);
          cs.newLine();
        }
        cs.endText();
      }
      doc.save(out.toFile());
    }
    return Files.readAllBytes(out);
  }

  @Test
  void 源文件带着占位行时剥离后一个都不剩而图注还在() throws Exception {
    byte[] pdf =
        pdfWith(
            "Chapter 1 Overview",
            "[PDF 1]",
            "Body paragraph about reimbursement.",
            "[PDF 2]",
            "Figure 1: architecture overview",
            "[PDF 3]",
            "Closing paragraph.");
    ParseResult parsed = parser().parse(pdf, "placeholder.pdf");
    assertThat(parsed.success()).as("解析要成功，否则这条读数没有意义").isTrue();
    assertThat(hasPlaceholder(parsed.markdown()))
        .as("前提：源文本里的占位行确实进了解析产物 —— 否则下面的剥离断言是空转")
        .isTrue();

    NoiseStripper.Result stripped = NoiseStripper.strip(parsed.markdown());
    assertThat(hasPlaceholder(stripped.markdown())).as("剥离后不许再出现占位标记（#51 判据 1）").isFalse();
    assertThat(stripped.removedLines()).as("三条整行占位 ⇒ 剥 3 行").isEqualTo(3);
    // ★ 判据 2 的负向断言：图注是**真信息**，剥占位的时候不许顺手一起删
    assertThat(stripped.markdown()).contains("Figure 1: architecture overview");
    assertThat(stripped.markdown()).contains("Body paragraph about reimbursement.");
    assertThat(stripped.markdown()).contains("Closing paragraph.");
  }

  @Test
  void 源文本里没有占位行时解析链不会凭空造出来() throws Exception {
    // ★ 这条钉的是取证结论本身：哪天换了 Tika 版本、它开始给图片插占位，这条会红。
    byte[] pdf =
        pdfWith("Chapter 1 Overview", "Figure 1: architecture overview", "Body paragraph.");
    ParseResult parsed = parser().parse(pdf, "clean.pdf");

    assertThat(parsed.success()).isTrue();
    assertThat(hasPlaceholder(parsed.markdown())).as("解析链不产生占位标记 —— 这一条是 #51 取证结论的可回归形式").isFalse();
  }
}
