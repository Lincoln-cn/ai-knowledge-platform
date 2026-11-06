package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.NoiseStripper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #177 取证钉 + 设计剥离开关（{@code keepDesignLines}）的判据。
 *
 * <p><strong>#177 取证结论（2026-11-06，tesseract 5.5.0 + pypdfium2 现场实测）</strong>：目录页与页脚的"整段丢失"
 * <strong>不是 OCR 丢</strong> —— OCR 原始输出里目录 6 行、页脚三页、页首标题三页全部存在，OCR 原始逐页相似度 0.90/0.93/0.85；丢失全部发生在
 * {@link NoiseStripper} ②②b③④ 的<strong>设计剥离</strong>（{@code 12 §2.4}： 页眉页脚/页码/目录行剔掉，防污染分块与向量）。issue
 * #177 要求"若为设计行为 ⇒ 写清口径 + 可关的开关" ⇒ {@code keepDesignLines}。
 *
 * <p><strong>样本声明</strong>：{@code scanned.pdf} OCR 快照与 {@code ocr-ground-truth.json} 均为 {@code
 * make_ocr_fixtures.py} 合成夹具，<strong>不是客户真实件</strong>；OCR 快照逐字抄自 {@link
 * OcrAccuracyBaselineTest}（2026-11-06 现场实测复核一致）。
 *
 * <p><strong>口径声明</strong>：本类的"逐页相似度"= 1 - Levenshtein(ref, act) / max(len)（两侧去空白）；issue #177
 * 原文的"宽松相似度"公式未写明（其读数 0.1277/0.7925/0.3630 无法精确复现），故判据 2 的"&gt;0.6"按本口径核对，差异如实入报告。
 * 逐页剥离用<strong>单页口径</strong>（strip 单页文本；跨页重复行规则在单页内不触发），全文口径的行级剥离另有 {@link NoiseStripperTest} 与本类
 * {@link #defaultModeReproducesIssue177Loss()} 的全文断言。
 */
class DesignStripSwitchTest {

  /**
   * OCR 实测快照 —— 逐字抄自 {@link OcrAccuracyBaselineTest} 的 actuals（合成夹具 {@code scanned.pdf} 的 tesseract
   * 5.5.0 产物；本 worktree 2026-11-06 用同参数复跑逐字一致）。
   */
  private static final List<String> SNAPSHOT =
      List.of(
          // ── 第 1 页（目录页：点线 + 页码）──
          """
          某某集团内部管理制度
          第一章 总则 ..………... 1
          1.1 制定目的 ......….. 2
          1.1.1 适用范围 ..….….……. 3
          第二章 报销标准 .....…….. 5
          2.1 住宿标准 ....….…….. 6
          2. 2 交通标准 ........ 7
          3
          2024 某某集团有限公司 版权所有
          内部资料 请勿外传
          """,
          // ── 第 2 页（正文页）──
          """
          某某集团内部管理制度
          第一章 总则
          本制度规定如下要点:
          适用公司各部门
          适用正式员工
          BABA RMA SHIN
          提交申请
          部门审批
          归档备案
          住宿标准为一线城市每人每天不超过伍佰元，超标部分不予报销。
          交通费用实报实销，需提供合规票据作为报销依据。
          出差前须提交书面申请，经部门负责人批准后方可出行。
          本规定自发布之日起施行，由人力资源部负责解释。
          第7页共12页
          内部资料 请勿外传
          """,
          // ── 第 3 页（列表页）──
          """
          某某集团内部管理制度
          .1 制定目的
          - 适用公司各部门
          用正式员
          - 适用外包人员须另签协议
          1，提交申请
          2，部门审批
          3. 归档备案
          vil
          一人
          |
          i
          H
          内部资料 请勿外传
          """);

  /**
   * 逐字抄自 {@code fixtures/ocr-ground-truth.json} 的 {@code scanned.pdf} 三页参考（读 JSON，同
   * NoiseStripperTest 口径）。
   */
  private static List<String> references() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<String> references = new ArrayList<>();
    try (InputStream in =
        DesignStripSwitchTest.class.getResourceAsStream("/fixtures/ocr-ground-truth.json")) {
      assertThat(in).as("夹具 ocr-ground-truth.json 不在 classpath").isNotNull();
      JsonNode pages = mapper.readTree(in).get("scanned.pdf");
      pages.propertyNames().forEach(field -> references.add(pages.get(field).asText()));
    }
    assertThat(references).as("夹具里应有 3 页参考原文").hasSize(3);
    return references;
  }

  private static String normalize(String text) {
    return text.replaceAll("\\s+", "");
  }

  private static int levenshtein(String a, String b) {
    int[] prev = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      int[] cur = new int[b.length() + 1];
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
      }
      prev = cur;
    }
    return prev[b.length()];
  }

  /** 逐页相似度（本类口径声明的公式）。 */
  private static double similarity(String reference, String actual) {
    String r = normalize(reference);
    String a = normalize(actual);
    int max = Math.max(r.length(), a.length());
    return max == 0 ? 1.0 : 1.0 - (double) levenshtein(r, a) / max;
  }

  /**
   * <strong>#177 根因钉 + 修复前读数</strong>：默认剥离下，目录行/页脚/页首标题/页码/版权全被剥（= issue 说的"整段丢失"）， 且目录页（页 1）相似度断崖
   * —— 丢失是 {@code NoiseStripper} 设计剥离，不是 OCR。
   */
  @Test
  void defaultModeReproducesIssue177Loss() throws Exception {
    List<String> refs = references();
    String joined = String.join("\n", SNAPSHOT);
    NoiseStripper.Result res = NoiseStripper.strip(joined);

    assertThat(res.markdown())
        .as("默认口径下目录行被剥（#177「目录页整段丢失」的成因，12 §2.4 设计行为）")
        .doesNotContain("第一章 总则 ..")
        .as("默认口径下页脚被剥")
        .doesNotContain("内部资料")
        .as("默认口径下页首重复标题被剥")
        .doesNotContain("某某集团内部管理制度")
        .as("默认口径下版权行被剥")
        .doesNotContain("版权所有");
    assertThat(res.removedLines()).as("默认口径剥掉的非空行数").isGreaterThanOrEqualTo(13);

    double sim1 = similarity(refs.get(0), NoiseStripper.strip(SNAPSHOT.get(0)).markdown());
    double sim2 = similarity(refs.get(1), NoiseStripper.strip(SNAPSHOT.get(1)).markdown());
    double sim3 = similarity(refs.get(2), NoiseStripper.strip(SNAPSHOT.get(2)).markdown());
    System.out.printf("修复前（默认剥离）逐页相似度 = %.4f / %.4f / %.4f%n", sim1, sim2, sim3);
    assertThat(sim1)
        .as("修复前目录页（页 1）相似度必须断崖（issue #177 实测 0.1277 级；本口径单页剥离下 <0.55）")
        .isLessThan(0.55);
  }

  /**
   * <strong>判据 2 + 修复后读数</strong>：{@code keepDesignLines=true} 时三类丢失内容全部回来，且三页相似度 &gt; 0.6 （issue
   * #177 判据 2 原文阈值，按本类口径声明的公式核对）。
   */
  @Test
  void keepDesignLinesRecoversLostPassages() throws Exception {
    List<String> refs = references();
    String joined = String.join("\n", SNAPSHOT);
    NoiseStripper.Result res = NoiseStripper.strip(joined, true);

    assertThat(res.markdown())
        .as("keep 模式下目录行必须保留（章节锚点）")
        .contains("第一章 总则 ..")
        .as("keep 模式下页脚必须保留（「共几页」问答依据）")
        .contains("内部资料 请勿外传")
        .as("keep 模式下页首标题必须保留")
        .contains("某某集团内部管理制度")
        .as("keep 模式下页码行必须保留")
        .contains("第7页共12页");
    assertThat(res.removedLines()).as("keep 模式下排版噪声一行都不剥").isZero();

    for (int i = 0; i < 3; i++) {
      double sim = similarity(refs.get(i), NoiseStripper.strip(SNAPSHOT.get(i), true).markdown());
      System.out.printf("修复后（keep 模式）页 %d 相似度 = %.4f%n", i + 1, sim);
      assertThat(sim).as("keep 模式页 %d 相似度必须 > 0.6（#177 判据 2）", i + 1).isGreaterThan(0.6);
    }
  }

  /**
   * 开关边界：{@code keepDesignLines} 只放行<strong>排版噪声</strong>（12 §2.4），{@code #51} 的占位标记是解析垃圾，
   * 任何模式都必须继续剥 —— 防止开关被读成"全关"。
   */
  @Test
  void placeholderStrippingStaysOnWhenKeepingDesignLines() {
    String withPlaceholder = "[PDF 1]\n第一章 总则 ........ 1\n正文一行";
    NoiseStripper.Result res = NoiseStripper.strip(withPlaceholder, true);
    assertThat(res.markdown())
        .as("keep 模式下 [PDF 1] 仍必须剥（#51 判据 1 不受 #177 开关影响）")
        .doesNotContain("[PDF 1]")
        .as("keep 模式下目录行保留")
        .contains("第一章 总则 ........ 1");
  }
}
