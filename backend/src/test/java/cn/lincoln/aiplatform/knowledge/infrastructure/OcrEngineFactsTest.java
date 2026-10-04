package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 「引擎自述事实」的解析（第 9 周 · 0.3.1）—— ★ <strong>CI 抓出来的那条漏</strong>。
 *
 * <h3>★ 为什么这一层要单测</h3>
 *
 * <p>第一版的可用性探测只认两处：{@code app.ocr.tessdata-path} 与 {@code TESSDATA_PREFIX} 环境变量。 而 Ubuntu 用 {@code
 * apt} 装的 tesseract <strong>不设</strong> {@code TESSDATA_PREFIX}（默认路径编译在可执行文件里） ⇒
 * 在那样的机器上两处都空，于是探测报「可用」却<strong>从没核对过 {@code chi_sim.traineddata} 在不在</strong> ——
 * 而"缺中文包"正是这个类存在的唯一理由。CI（ubuntu-latest + apt 装的 tesseract）把它抓了出来。
 *
 * <p>现在目录与语言清单都由 {@code tesseract --list-langs} 给出，所以<strong>它的解析必须钉住</strong>：
 * 解析错了就会重新退化成"报可用但没核对"，而且同样不报错。
 *
 * <p>放在 {@code infrastructure} 包下是为了能断言包内可见的解析函数（同 {@code modelcatalog.infrastructure} 的先例）。
 */
class OcrEngineFactsTest {

  /**
   * 真实输出形状（Tesseract 5.x）。
   *
   * <pre>
   * List of available languages in "/usr/share/tesseract-ocr/5/tessdata/" (2):
   * chi_sim
   * eng
   * </pre>
   */
  @Test
  void 解析出目录与语言清单() {
    String output =
        """
        List of available languages in "/usr/share/tesseract-ocr/5/tessdata/" (2):
        chi_sim
        eng
        """;

    OcrEngine.EngineFacts facts = OcrEngine.parseListLangs(output);

    assertThat(facts.tessdataPath())
        .as("目录行带引号，要去掉引号")
        .contains("/usr/share/tesseract-ocr/5/tessdata/");
    assertThat(facts.languages()).as("语言码逐行列出").containsExactly("chi_sim", "eng");
  }

  /** ★ 没有目录行时仍要能取到语言清单 —— 两级核对里第二级靠的就是它。 */
  @Test
  void 没有目录行时仍取到语言清单() {
    OcrEngine.EngineFacts facts = OcrEngine.parseListLangs("eng\nosd\n");

    assertThat(facts.tessdataPath()).isEmpty();
    assertThat(facts.languages()).containsExactly("eng", "osd");
  }

  /** 问不出来（空输出 / 报错文案）⇒ 空事实。★ 报错文案绝不能被当成语言码。 */
  @Test
  void 问不出来时是空事实() {
    assertThat(OcrEngine.parseListLangs("").languages()).isEmpty();
    assertThat(OcrEngine.parseListLangs("").tessdataPath()).isEmpty();

    OcrEngine.EngineFacts error =
        OcrEngine.parseListLangs("Error opening data file /x/eng.traineddata");
    assertThat(error.languages()).as("带空格的报错文案不是语言码").isEmpty();
    assertThat(error.tessdataPath()).isEmpty();
  }
}
