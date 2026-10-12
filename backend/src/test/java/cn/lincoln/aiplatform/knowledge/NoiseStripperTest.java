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
 * 噪声剥离（第 9 周 · 0.3.1，{@code 12 §2.4}）—— ★ 拿**答案已知**的扫描件夹具来验。
 *
 * <h3>★ 为什么这组测试值得单独存在</h3>
 *
 * <p>"页眉页脚要剔掉"这条规则最危险的地方不是漏剔，而是**误杀正文**（{@code 12 §10.2} 风险 2： 内容静默缺失）。只写"剔掉了页眉"是不够的 ——
 * 必须同时断言<strong>正文一行没少</strong>。
 *
 * <h3>★★ 更要紧的一条：理想文本 ≠ 真实输出</h3>
 *
 * <p>这条规则在真栈上<strong>踩了两次</strong>，两次都是"单测全绿、线上一条都没生效"：
 *
 * <ol>
 *   <li>点线与页码之间夹着标点 —— 真实 OCR 输出是 {@code 第一章 总则 ..……….….， 1}（中间是个逗号）
 *   <li><b>空行</b> —— Tika 的 Markdown 输出**每行之间都有空行**，于是"连续 ≥5 行"在物理行上永远不成立
 * </ol>
 *
 * <p>所以下面用到的文本分两类，标得清清楚楚：
 *
 * <ul>
 *   <li>{@code ocr-ground-truth.json}：夹具的**理想文本**（由 {@code make_ocr_fixtures.py} 生成， 逐页一行行接起来）
 *   <li><b>实测拷贝</b>：从真栈解析产物里抄下来的形态（含空行、含那个逗号）
 * </ul>
 */
class NoiseStripperTest {

  /** 从夹具的逐页答案拼出"理想文本"（每页内部按 {@code \r\n} 分行，页与页之间接一个换行）。 */
  private static String scannedGroundTruth() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in =
        NoiseStripperTest.class.getResourceAsStream("/fixtures/ocr-ground-truth.json")) {
      assertThat(in).as("夹具 ocr-ground-truth.json 不在 classpath").isNotNull();
      JsonNode root = mapper.readTree(in);
      JsonNode pages = root.get("scanned.pdf");
      List<String> all = new ArrayList<>();
      // ★ Jackson 3 里 fieldNames()（迭代器）改成了 propertyNames()（Collection）
      pages.propertyNames().forEach(field -> all.add(pages.get(field).asText()));
      return String.join("\n", all);
    }
  }

  @Test
  void 三页扫描件上的页眉页脚页码目录都被剥掉而正文不动() throws Exception {
    String raw = scannedGroundTruth();

    NoiseStripper.Result result = NoiseStripper.strip(raw);

    // ── ① 噪声确实没了 ──
    assertThat(result.markdown())
        .as("页眉（每页都有的那一行）必须剔掉 —— 它在 50 个 chunk 里重复会稀释召回")
        .doesNotContain("某某集团内部管理制度");
    assertThat(result.markdown()).as("页脚同样").doesNotContain("内部资料 请勿外传");
    assertThat(result.markdown()).as("目录行（点线 + 页码）").doesNotContain("........");
    assertThat(result.markdown())
        .as("独立成行的页码：阿拉伯数字 / 罗马数字 / 「第 X 页 共 Y 页」三种形态")
        .doesNotContain("第 7 页 共 12 页")
        .doesNotContain("\nvii\n");

    // ── ② 剥掉的行数：3 页眉 + 3 页脚 + 3 页码 + 6 目录行 ──
    assertThat(result.removedLines())
        .as("★ 剥掉的行数必须记准（removedNoiseLines 要进切分预览，否则少的内容没人能解释）")
        .isEqualTo(15);

    // ── ③ 正文一行不动（这是更重要的那一半：误杀 = 内容静默缺失） ──
    assertThat(result.markdown())
        .contains("住宿标准为一线城市每人每天不超过伍佰元，超标部分不予报销。")
        .contains("交通费用实报实销，需提供合规票据作为报销依据。")
        .contains("本规定自发布之日起施行，由人力资源部负责解释。")
        .contains("适用外包人员须另签协议")
        .contains("归档备案");

    // ── ④ 章节标题要留下：它在正文里只出现 1~2 次（不到 3 次），不能被重复行规则误判 ──
    assertThat(result.markdown())
        .as("★ 正文里的「第一章 总则」必须留下 —— 章节标题是结构化前缀的主体（12 §4.2）")
        .contains("第一章 总则")
        .contains("1.1 制定目的");

    // ── ⑤ 只出现一次的那行版权信息不属于噪声 ──
    assertThat(result.markdown()).contains("2024 某某集团有限公司 版权所有");
  }

  /**
   * ★★ 真实形态的目录行（**实测拷贝**）—— 这两件事是单测最容易漏、而线上一定踩的：
   *
   * <ul>
   *   <li>点线与页码之间夹着标点（真实 OCR 输出是 {@code ..……….….， 1}）
   *   <li>Tika 的 Markdown 输出**每行之间都有空行** ⇒ "连续 ≥5 行"必须按**有内容的行**判
   * </ul>
   *
   * <p>下面这份文本是从真栈 {@code parsedContent} 里抄下来的形状，一个字符都没改。
   */
  @Test
  void 真实输出的目录行也要剔掉_空行不打断且允许夹标点() {
    NoiseStripper.Result result =
        NoiseStripper.strip(
            """
            某某集团内部管理制度

            第一章 总则 ..……….….， 1

            1.1 制定目的 ......….. 2

            1.1.1 适用范围 ..…….……….. 3

            第二章 报销标准 ...….….……. 5

            2.1 住宿标准 .....…….， 6

            2. 2 交通标准 ....……….. 7

            2024 某某集团有限公司 版权所有

            内部资料 请勿外传
            """);

    assertThat(result.removedLines()).as("6 行目录被剔掉（★ 空行不打断；点线与页码之间夹着「，」也要认）").isEqualTo(6);
    assertThat(result.markdown())
        .as("★ 页眉/页脚在这一段里只出现 1 次 ⇒ 按「≥3 次」规则不该剥（三页场景由上一个用例覆盖）")
        .contains("某某集团内部管理制度")
        .contains("2024 某某集团有限公司 版权所有");
    assertThat(result.markdown()).doesNotContain("第一章 总则 ..");
  }

  @Test
  void 短行重复不到三次不剔() {
    String markdown = "第一段\n重复行\n第二段\n重复行\n第三段\n";
    NoiseStripper.Result result = NoiseStripper.strip(markdown);
    assertThat(result.removedLines()).as("只出现 2 次 ⇒ 不到 §2.4 的「≥3 处」，不动").isZero();
    assertThat(result.markdown()).contains("重复行");
  }

  @Test
  void 长行重复也不剔() {
    String longLine = "这是一行很长的正文内容，长度远超六十个字符，即使它在文档里重复出现多次也属于正文而不是页眉，因为页眉不会这么长，重复出现的段落是内容";
    assertThat(longLine.length()).isGreaterThanOrEqualTo(NoiseStripper.REPEAT_MAX_LENGTH);
    String markdown = longLine + "\n" + longLine + "\n" + longLine + "\n";
    NoiseStripper.Result result = NoiseStripper.strip(markdown);
    assertThat(result.removedLines()).as("≥60 字的长行不是页眉（12 §2.4 的长度条件）").isZero();
  }

  @Test
  void 代码围栏里的重复行不剔() {
    String markdown =
        """
        ```yaml
        timeout: 30
        retries: 3
        timeout: 30
        retries: 3
        timeout: 30
        ```
        """;
    NoiseStripper.Result result = NoiseStripper.strip(markdown);
    assertThat(result.removedLines()).as("★ 配置片段里重复的行是内容 —— 围栏内一律不参与剥离").isZero();
  }

  /**
   * ★★ 保守性护栏：**重复行必须是少数派** —— 否则会把整篇文档剥空。
   *
   * <p>这条来自一次**真实事故**（本轮 CI 抓的）：真栈闸门的夹具是 "一行短句重复 20 次"，被"重复行 ≥3 次且 &lt;60 字"规则整篇剔掉 ⇒ 预览 0 段。
   * 症状极其误导：文档**有内容却切不出段**，看起来像分块器坏了。
   *
   * <p>⇒ 判据：重复行占非空行的一半以上时，**一条都不剥**（宁可漏剔，不可误杀 —— {@code 12 §10.2} 风险 2 的"内容静默缺失"比漏剔严重）。
   */
  @Test
  void 重复行占多数时不剥_避免整篇被剥空() {
    String markdown = "闸门临时文档：用于验证建档三项必填。\n".repeat(20);

    NoiseStripper.Result result = NoiseStripper.strip(markdown);

    assertThat(result.removedLines()).as("★ 20 行里全是同一句 ⇒ 重复行占多数 ⇒ 这是**正文**，不是页眉").isZero();
    assertThat(result.markdown().strip()).as("整篇内容原样保留").isEqualTo(markdown.strip());
  }

  /** 对照组：重复行**是少数派**时照常剥（三页扫描件那份用例就是这个形态）。 */
  @Test
  void 重复行是少数派时照常剥() {
    // ★ 正文每一行都**互不相同** —— 否则正文自己也构成"重复行"，把整篇拖成多数派。
    //   这恰好也是真实文档的样子：页眉重复、正文不重复。
    StringBuilder builder = new StringBuilder();
    for (int page = 1; page <= 3; page++) {
      builder.append("某某集团内部管理制度\n");
      for (int line = 1; line <= 5; line++) {
        builder.append("第 ").append(page).append(" 页第 ").append(line).append(" 段正文\n");
      }
    }
    NoiseStripper.Result result = NoiseStripper.strip(builder.toString());

    assertThat(result.removedLines()).as("页眉 3 次、正文 15 行各不相同 ⇒ 少数派 ⇒ 照剥").isEqualTo(3);
    assertThat(result.markdown()).doesNotContain("某某集团内部管理制度");
    assertThat(result.markdown()).contains("第 1 页第 1 段正文");
  }

  @Test
  void 空输入原样返回() {
    assertThat(NoiseStripper.strip(null).markdown()).isEmpty();
    assertThat(NoiseStripper.strip("").removedLines()).isZero();
    assertThat(NoiseStripper.strip("   \n\n  ").removedLines()).isZero();
  }
}
