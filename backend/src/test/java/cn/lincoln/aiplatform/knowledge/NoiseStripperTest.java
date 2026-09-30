package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.infrastructure.NoiseStripper;
import cn.lincoln.aiplatform.knowledge.infrastructure.StripResult;
import org.junit.jupiter.api.Test;

/**
 * 噪声剥离规则单测（第 6 周 · 0.2.1 批 2）—— 规则出自 {@code 12 §2.4}。
 *
 * <p>★ 每条规则一组用例：<strong>「能剥掉」+「不误杀」反例</strong>。 噪声剥离的失败形态是<strong>静默少内容</strong> （{@code 12 §10.2
 * 风险 2}）—— 误删正文不会抛异常，只会让召回变差却无从解释。 所以反例断言与正例同样重要。
 *
 * <p>规则清单（{@code 12 §2.4}）：
 *
 * <ol>
 *   <li><strong>页眉页脚 / 水印</strong>：跨页重复行 —— 同一行 ≥3 处出现且长度 &lt; 60 字 ⇒ 剔除（水印用同一条规则覆盖）
 *   <li><strong>页码</strong>：纯数字 / 罗马数字 / 「第 X 页 共 Y 页」的独立行
 *   <li><strong>目录页</strong>：连续 ≥5 行「…… 数字」结尾 ⇒ 整段剔除，但保留章节标题
 *   <li><strong>版权行</strong>：© / Copyright / 版权所有 / 翻印必究 这类声明行
 *   <li><strong>空白段落</strong>：连续空行折叠
 * </ol>
 */
class NoiseStripperTest {

  private final NoiseStripper stripper = new NoiseStripper();

  private static String body(String... lines) {
    return String.join("\n", lines) + "\n";
  }

  // ────────────────────────── 1 · 页眉页脚 / 水印（跨页重复行） ──────────────────────────

  /** ★ {@code 12 §2.4}：同一行 ≥3 处出现且长度 &lt; 60 字 ⇒ 页眉页脚。 页眉在每一页都出现，解析后就是「同一短行重复 N 次」—— 不剥会污染分块与向量。 */
  @Test
  void 跨页重复短行_判为页眉页脚并剥除() {
    String md =
        body(
            "某某集团内部管理制度",
            "第一章 总则",
            "本制度规定了差旅费的报销标准与流程。",
            "某某集团内部管理制度",
            "第二条 出差前须提交书面申请。",
            "某某集团内部管理制度",
            "第三条 住宿标准见下表。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("页眉重复行必须剥掉，否则同一短句在 50 个 chunk 里互相「很像」，召回被稀释（12 §2.4）")
        .doesNotContain("某某集团内部管理制度");
    assertThat(result.markdown()).contains("第一章 总则").contains("本制度规定了差旅费的报销标准与流程。");
    assertThat(result.removedNoiseLines()).as("removedNoiseLines 要可观测（12 §2.4 纪律）").isEqualTo(3);
  }

  /** ★ 反例：只出现 2 次的短行<strong>不许</strong>当噪声。 阈值是 ≥3 次（{@code 12 §2.4}）—— 正文里一句关键结论重复一次很正常，剥了就是误杀。 */
  @Test
  void 只出现两次的短行_不误杀() {
    String md = body("适用公司各部门", "第一章 总则", "适用公司各部门", "本条说明适用范围。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).as("出现 2 次 < 阈值 3 ⇒ 不是跨页重复，正文不许误杀").contains("适用公司各部门");
    assertThat(result.removedNoiseLines()).isZero();
  }

  /**
   * ★ 反例：≥60 字的重复行<strong>不许</strong>当页眉页脚。 长度上限是规则的一部分（{@code 12 §2.4}）——
   * 页眉页脚是短行，长句重复多半是正文强调或条款复述。
   */
  @Test
  void 超过六十字的重复长行_不误杀() {
    String longLine = "本条规定的报销标准适用于公司全体员工，出差前须提交书面申请并经部门负责人批准后方可出行，未经批准的费用不予报销且责任自负。";
    assertThat(longLine.length()).as("夹具前提：该行必须 ≥60 字才测得到长度阈值").isGreaterThanOrEqualTo(60);

    String md = body(longLine, "第一章 总则", longLine, "第二条 出差须提前申请。", longLine);

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("长度 ≥60 字的重复行不是页眉页脚（12 §2.4 长度 <60 字）—— 剥了就是误杀正文")
        .contains(longLine);
    assertThat(result.removedNoiseLines()).isZero();
  }

  /**
   * ★ 反例：Markdown 标题 / 列表 / 表格行即使重复也<strong>不许</strong>进重复行规则。 它们是结构信号（{@code 12 §2.1}）——
   * 分块器靠它们切语义边界，剥掉等于自毁结构。
   */
  @Test
  void 标题列表表格行即使重复_不误杀() {
    String md =
        body(
            "# 第一章 总则",
            "- 适用公司各部门",
            "| 标准 | 备注 |",
            "# 第一章 总则",
            "- 适用公司各部门",
            "| 标准 | 备注 |",
            "# 第一章 总则",
            "- 适用公司各部门",
            "| 标准 | 备注 |",
            "正文段落保持不变。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).as("结构行（标题/列表/表格）不在重复行规则覆盖范围").contains("# 第一章 总则");
    assertThat(result.markdown()).contains("- 适用公司各部门");
    assertThat(result.markdown()).contains("| 标准 | 备注 |");
    assertThat(result.removedNoiseLines()).isZero();
  }

  // ────────────────────────── 2 · 页码 ──────────────────────────

  /** ★ {@code 12 §2.4}：纯数字 / 罗马数字 / 「第 X 页 共 Y 页」的<strong>独立行</strong> ⇒ 页码。 */
  @Test
  void 独立页码行_纯数字罗马数字与第X页共Y页_剥除() {
    String md =
        body("第一章 总则", "42", "正文第一段。", "第 3 页 共 12 页", "正文第二段。", "iii", "正文第三段。", "第3页共12页");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).doesNotContain("42").doesNotContain("iii");
    assertThat(result.markdown()).doesNotContain("第 3 页 共 12 页").doesNotContain("第3页共12页");
    assertThat(result.markdown()).contains("第一章 总则").contains("正文第一段。").contains("正文第三段。");
    assertThat(result.removedNoiseLines()).isEqualTo(4);
  }

  /**
   * ★ 反例：行内的数字、年份、金额<strong>不许</strong>当页码。 页码规则只认<strong>独立行</strong>（{@code 12 §2.4}）—— 「住宿标准 500
   * 元/天」里的 500 是正文。
   */
  @Test
  void 行内数字金额与年份_不误杀() {
    String md = body("2024 年度差旅费报销规定", "住宿标准 500 元/天", "共 3 人参加培训", "第 3 条 适用范围", "1. 提交申请");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("页码规则只匹配独立行 —— 行内数字是正文，剥了就是误杀")
        .contains("2024 年度差旅费报销规定")
        .contains("住宿标准 500 元/天")
        .contains("共 3 人参加培训")
        .contains("第 3 条 适用范围")
        .contains("1. 提交申请");
    assertThat(result.removedNoiseLines()).isZero();
  }

  /** ★ 反例：「第 X 章 / 第 X 条」是<strong>章节编号</strong>不是页码 —— 中文契约靠它做法定效力单元（{@code 12 §3.2}）。 */
  @Test
  void 章节编号第X章第X条_不误杀() {
    String md = body("第一章 总则", "第 3 条 本条是正文条款。", "第X页误判对比例外说明");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).contains("第一章 总则").contains("第 3 条 本条是正文条款。");
  }

  // ────────────────────────── 3 · 目录页 ──────────────────────────

  /** ★ {@code 12 §2.4}：连续 ≥5 行「…… 数字」结尾 ⇒ 整段剔除（目录页）。 */
  @Test
  void 目录页连续五行点线页码_整段剥除() {
    String md =
        body(
            "第一章 总则 ........ 1",
            "1.1 制定目的 ........ 2",
            "1.1.1 适用范围 ........ 3",
            "第二章 报销标准 ........ 5",
            "2.1 住宿标准 ........ 6",
            "正文从这里开始。",
            "本制度规定如下要点。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("目录页会污染分块（12 §2.4）—— 连续 ≥5 行「……数字」结尾要整段剥除")
        .doesNotContain("制定目的 ........ 2")
        .doesNotContain("住宿标准 ........ 6");
    assertThat(result.markdown()).contains("正文从这里开始。").contains("本制度规定如下要点。");
  }

  /** ★ 反例：不足 5 行的「文字 + 数字」尾行不许当目录剥。 阈值 ≥5 是规则的一部分（{@code 12 §2.4}）—— 正文短列表若被当目录整段删掉就是误杀。 */
  @Test
  void 不足五行的点线数字尾行_不误杀() {
    String md = body("采购清单 ........ 3", "办公用品 ........ 5", "正文说明采购流程。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("不足 ≥5 行 ⇒ 不构成目录页（12 §2.4）—— 不许整段剔除")
        .contains("采购清单 ........ 3")
        .contains("办公用品 ........ 5");
    assertThat(result.removedNoiseLines()).isZero();
  }

  /** ★ {@code 12 §2.4} 括号要求：剥目录页时<strong>保留章节标题</strong>（结构抽取要用）。 */
  @Test
  void 目录页内的章节标题行_保留() {
    String md =
        body(
            "# 目录",
            "第一章 总则 ........ 1",
            "1.1 制定目的 ........ 2",
            "1.1.1 适用范围 ........ 3",
            "第二章 报销标准 ........ 5",
            "2.1 住宿标准 ........ 6",
            "# 第一章 总则",
            "正文开始。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("目录页要保留章节标题用于结构（12 §2.4）—— 剥掉标题等于章节树没了")
        .contains("# 目录")
        .contains("# 第一章 总则");
    assertThat(result.markdown()).contains("正文开始。");
    assertThat(result.markdown()).doesNotContain("制定目的 ........ 2");
  }

  // ────────────────────────── 4 · 版权行 ──────────────────────────

  /** ★ 版权声明行会进向量且检索命中无意义 —— 识别并剥除（任务书「版权行」）。 */
  @Test
  void 版权声明行_剥除() {
    String md =
        body(
            "第一章 总则",
            "© 2024 某某集团有限公司 版权所有",
            "本制度规定如下要点。",
            "Copyright (c) 2024 Example Corp. All rights reserved.",
            "正文继续。",
            "版权所有 翻印必究");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).doesNotContain("版权所有").doesNotContain("All rights reserved");
    assertThat(result.markdown()).contains("第一章 总则").contains("本制度规定如下要点。").contains("正文继续。");
  }

  /**
   * ★ 反例：正文里提到「版权/著作权」的<strong>业务条款</strong>不许当版权行剥掉。 版权规则只认<strong>声明形态</strong>的独立短行（© /
   * Copyright / 版权所有 等）， 不认「本合同涉及知识产权归属的约定」这类正文。
   */
  @Test
  void 正文中的知识产权业务条款_不误杀() {
    String md =
        body("第 8 条 知识产权归属按双方另行签署的协议执行，未经授权不得对外披露源代码。", "本合同涉及著作权的，以附件三约定为准。", "技术成果的专利申请权归甲方所有。");

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("业务条款谈版权不等于版权声明行 —— 剥了就是误杀")
        .contains("第 8 条 知识产权归属")
        .contains("本合同涉及著作权的")
        .contains("技术成果的专利申请权归甲方所有。");
    assertThat(result.removedNoiseLines()).isZero();
  }

  // ────────────────────────── 5 · 空白段落 ──────────────────────────

  /** ★ 连续空行会把「段落边界」放大成噪声 —— 折叠为单个空行（任务书「空白段落」）。 */
  @Test
  void 连续空白行_折叠() {
    String md = "第一章 总则\n\n\n\n\n本制度规定如下要点。\n\n\n\n第二条 正文。\n";

    StripResult result = stripper.strip(md);

    assertThat(result.markdown())
        .as("连续空行折叠为单空行 —— 不折会污染字符数与分块长度")
        .contains("第一章 总则")
        .contains("本制度规定如下要点。")
        .contains("第二条 正文。");
    assertThat(result.markdown()).doesNotContain("\n\n\n");
  }

  /** ★ 反例：单个段落分隔空行是 Markdown 结构，不许剥光。 */
  @Test
  void 单个段落分隔空行_保留() {
    String md = "第一章 总则\n\n本制度规定如下要点。\n\n第二条 正文。\n";

    StripResult result = stripper.strip(md);

    assertThat(result.markdown()).as("单空行是段落边界（Markdown 结构），不能连它一起剥").isEqualTo(md);
  }

  // ────────────────────────── 6 · 计数可观测 ──────────────────────────

  /** ★ {@code 12 §2.4} 纪律：剥离计数要落 {@code removedNoiseLines} —— 否则「为什么这份文档少了一页内容」没人能解释。 */
  @Test
  void 剥离行数可观测_多类噪声合计() {
    String md =
        body(
            "某某集团内部管理制度",
            "第一章 总则",
            "1",
            "本制度规定如下要点。",
            "某某集团内部管理制度",
            "第二条 出差须提前申请。",
            "某某集团内部管理制度",
            "© 2024 某某集团 版权所有",
            "正文收尾。");

    StripResult result = stripper.strip(md);

    // 页眉×3 + 页码×1 + 版权×1 = 5
    assertThat(result.removedNoiseLines()).as("剥离计数必须准确（12 §2.4 可观测）").isEqualTo(5);
    assertThat(result.markdown()).contains("第一章 总则").contains("正文收尾。");
  }
}
