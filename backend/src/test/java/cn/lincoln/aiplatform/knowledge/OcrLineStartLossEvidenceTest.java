package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Issue #151 判据 1 的证据钉：行首丢字的根因定位 =「<strong>OCR 的问题</strong>（整页版面行切分）」，不是参考、不是渲染。
 *
 * <p><strong>三条可复核读数</strong>（2026-11-06 现场实测，tesseract 5.5.0 + pypdfium2；脚本 {@code
 * backend/target/probe151_root_cause.py} / {@code probe151_psm_sweep.py} / {@code probe151_v2.py}）：
 *
 * <ul>
 *   <li>(a) <strong>参考完整</strong>（本测试自动钉住）：{@code noisy.pdf} 第 3 页文本层逐字含「1.1 制定目的」「- 适用正式员工」⇒
 *       丢字不是参考夹具缺字头；
 *   <li>(b) <strong>渲染没截边</strong>：{@code scanned.pdf} 第 3 页 300dpi 整页墨迹
 *       bbox=(131,143,1415,3406)，左边距 131px（约 11mm），行内墨迹 x_min=134；
 *   <li>(c) <strong>裁行后全部读对</strong>：把第 2 行单独裁出，tesseract psm 7/3/6 都 100% 读对「1.1 制定目的」⇒
 *       同一像素裁出来就能读对 ⇒ 根因是整页版面行切分（与 #71 第 2 页结论同族互证）。
 * </ul>
 *
 * <p><strong>psm 扫描（判据 2 的否证）</strong>：psm 3 基线页1/2/3 = 90.1/93.2/84.9（微平均 90.53%，与 issue 一致复现）；psm
 * 4 页3 升到 95.9% 但页1 塌到 69.5%；psm 6 页3 93.2% 但页1 84.7%；psm 3/6 + bbox 裁白边、psm 11/12 全部更差 ⇒
 * <strong>无任何配置满足判据 2</strong>「第 3 页提升且另两页不得下降」——换 PSM 是取舍不是提升（#96 已同向结论）。唯一 100% 路径是裁行 psm 7
 * 的行级重试（L2/L3 大改，未实施）。
 *
 * <p><strong>样本声明</strong>：{@code noisy.pdf} / {@code scanned.pdf} 为 {@code make_ocr_fixtures.py}
 * 合成夹具，<strong>不是客户真实件</strong>。
 */
class OcrLineStartLossEvidenceTest {

  /**
   * #151 判据 1 读数 (a) 的自动钉：参考夹具的文本层必须保住丢失行的字头 —— 防 {@code make_ocr_fixtures.py}
   * 重新生成夹具后参考变残缺、被误读成"OCR 退化"。
   */
  @Test
  void referenceTextLayerKeepsTheLostLineHeads() throws Exception {
    try (InputStream in =
        OcrLineStartLossEvidenceTest.class.getResourceAsStream("/fixtures/noisy.pdf")) {
      assertThat(in).as("夹具 noisy.pdf 不在 classpath").isNotNull();
      try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(3);
        stripper.setEndPage(3);
        String page3 = stripper.getText(doc).replaceAll("\\s+", "");
        assertThat(page3)
            .as("noisy.pdf 第 3 页文本层必须含「1.1 制定目的」字头（#151 判据 1 读数 (a)：参考完整 ⇒ 不是参考的问题）")
            .contains("1.1制定目的")
            .as("noisy.pdf 第 3 页文本层必须含「- 适用正式员工」字头（#151 判据 1 读数 (a) 同上）")
            .contains("-适用正式员工");
      }
    }
  }
}
