package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.RuleBasedTableStatementRewriter;
import cn.lincoln.aiplatform.knowledge.domain.SegmentPlan;
import cn.lincoln.aiplatform.knowledge.domain.Segmenter;
import cn.lincoln.aiplatform.knowledge.domain.TableStatementRewriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 表格改写（第 10 周 · 0.3.2 判据 ①②③）—— <strong>表驱动</strong>单测。
 *
 * <h3>为什么用表驱动</h3>
 *
 * <p>这条规则要处理的是"文档里长什么样"的枚举：正常表、横向合并、纵向合并、全角字形、 表格后面紧跟正文、超长表…… 每一种都是<strong>一个可以并排看的输入/输出</strong>，
 * 逐条写 {@code @Test} 会让"到底覆盖了哪些形状"看不出来。
 *
 * <h3>★ 最后一条用例是判据 ③ 本身</h3>
 *
 * <p>{@link #segmenterKeepsContentAsTableAndPutsStatementsInSearchText()} 断言的是 「{@code content} 是原表
 * / {@code search_text} 是陈述，且两列不同」—— 它必须走 {@link Segmenter}（而不是只测改写器），因为判据管的是**两列的最终产物**。
 */
class TableStatementRewriterTest {

  private final TableStatementRewriter rewriter = new RuleBasedTableStatementRewriter();

  private static final String TABLE =
      """
      | 住宿标准 | 城市 | 限额 |
      | --- | --- | --- |
      | 一线城市 | 北京 | 500 |
      | 二线城市 | 杭州 | 300 |
      """;

  @Test
  void rewritesEachRowIntoOneSelfContainedStatement() {
    assertThat(rewriter.rewrite(TABLE))
        .as("每行一句、列头带上值 —— 这样「住宿标准是多少」才与某一行的词面重合")
        .isEqualTo("住宿标准：一线城市；城市：北京；限额：500\n住宿标准：二线城市；城市：杭州；限额：300");
  }

  /** ★ 判据 ② 的两半：横向合并（列数变少）与纵向合并（格子为空）都要补。 */
  @Test
  void fillsMergedCells() {
    List<String[]> cases =
        List.of(
            new String[] {
              // 横向合并：表头 3 列、数据行只有 2 格 ⇒ 最后一格向右重复
              """
              | 名称 | 单价 | 单位 |
              | --- | --- | --- |
              | 打印纸 | 30 | 包 |
              | 墨盒 | 200 |
              """,
              "名称：打印纸；单价：30；单位：包\n名称：墨盒；单价：200；单位：200"
            },
            new String[] {
              // 纵向合并：第二行第一格为空 ⇒ 继承上一行同列
              """
              | 类别 | 项目 | 金额 |
              | --- | --- | --- |
              | 差旅 | 住宿 | 500 |
              |  | 交通 | 300 |
              """,
              "类别：差旅；项目：住宿；金额：500\n类别：差旅；项目：交通；金额：300"
            });

    for (String[] testCase : cases) {
      assertThat(rewriter.rewrite(testCase[0]))
          .as("合并单元格必须补齐 —— 不补不是「少一个词」，而是语义错位（空 ≠ 同上）")
          .isEqualTo(testCase[1]);
    }
  }

  /** ★ 字形归一：全角数字/斜杠收成半角、连续空白收一个、行尾句号去掉（归一的是字形，不是量纲）。 */
  @Test
  void normalizesGlyphs() {
    String table =
        """
        | 标准 | 限额 |
        | --- | --- |
        | 一线城市 | ５００ 元／天。 |
        """;
    assertThat(rewriter.rewrite(table)).isEqualTo("标准：一线城市；限额：500 元/天");
  }

  @Test
  void nonTableReturnsAsIs() {
    String prose = "本制度规定如下要点：\n- 适用公司各部门\n- 适用正式员工";
    assertThat(rewriter.rewrite(prose)).as("不是表格就原样返回：实现自己判断，调用方不必先判断对").isEqualTo(prose);
  }

  @Test
  void malformedTablesReturnAsIs() {
    // 没有分隔行 ⇒ 正文里恰好有竖线，不是表格
    assertThat(rewriter.rewrite("| 这不是表格 |\n| 只是竖线 |")).contains("不是表格");
    assertThat(rewriter.rewrite("| 只有表头 | 没有分隔行 |")).isEqualTo("| 只有表头 | 没有分隔行 |");
    assertThat(rewriter.rewrite("")).isEmpty();
    assertThat(rewriter.rewrite(null)).isNull();
  }

  /** ★ 表体后面紧跟正文时，不能把正文吞进陈述（否则那一段的语义会被"表格化"）。 */
  @Test
  void stopsAtTableEnd() {
    String markdown =
        """
        | 项 | 值 |
        | --- | --- |
        | 甲 | 1 |

        本章其余内容为正文，不是表格。
        """;
    assertThat(rewriter.rewrite(markdown)).isEqualTo("项：甲；值：1");
  }

  /**
   * ★ 长度护栏：改写结果**绝不长于原表**。
   *
   * <p>理由不是"好看"：{@code Segmenter} 的 {@code searchText ≤ maxChars} 是按原表长度装箱算出来的，
   * 陈述变长就会「估算器认为装得下、校验器却拒绝」⇒ 整篇 SEG-I1 失败。
   */
  @Test
  void neverLongerThanTheOriginalTable() {
    StringBuilder rows = new StringBuilder("| 很长的列头名称 | 另一个很长的列头名称 |\n| --- | --- |\n");
    for (int i = 0; i < 20; i++) {
      rows.append("| 值").append(i).append(" | 另一个值").append(i).append(" |\n");
    }
    String table = rows.toString();

    String rewritten = rewriter.rewrite(table);
    assertThat(rewritten.length())
        .as("改写结果不能比原表长（按整句截断）—— 原长 %d、改写后 %d", table.length(), rewritten.length())
        .isLessThanOrEqualTo(table.length());
    assertThat(rewritten.lines().count()).as("20 行装不下 ⇒ 应当真的被截断").isLessThan(20);
    for (String statement : rewritten.split("\n")) {
      assertThat(statement)
          .as("截断要落在「整句」边界上：不留半句（每句都带列头、且不以分号结尾）")
          .contains("：")
          .doesNotEndWith("；");
    }
  }

  /** ★ 陈述必须保住**每一个单元格的值** —— 改写的最大风险不是"改得不好看"，而是**改着改着丢信息**， 而丢了以后检索不到、界面也不报错。 */
  @Test
  void statementsKeepEveryCellValue() {
    String table =
        """
        | 类别 | 项目 | 金额 | 备注 |
        | --- | --- | --- | --- |
        | 差旅 | 住宿 | 500 | 一线城市 |
        | 差旅 | 交通 | 300 | 实报实销 |
        """;
    String rewritten = rewriter.rewrite(table);

    for (String value : List.of("差旅", "住宿", "500", "一线城市", "交通", "300", "实报实销")) {
      assertThat(rewritten).as("值「%s」必须还在陈述里（改写不许丢信息）", value).contains(value);
    }
    for (String column : List.of("类别", "项目", "金额", "备注")) {
      assertThat(rewritten).as("列头「%s」必须重复到每一句里（否则那一句的语义丢了归属）", column).contains(column);
    }
  }

  /**
   * ★ 「表格问题能召回」的**文本侧**证据：问句里的词与陈述落在**同一句**里。
   *
   * <p>★ 老实说清边界：这条只证明**词面**上够得着（全文路靠它）。向量路是不是更近， 用桩模型量不出来（桩向量语义随机）—— 那件事要等真模型，属于第 10 周之后的验收口径， 已记在
   * {@code docs/已知问题与欠账.md}。
   */
  @Test
  void statementsCarryQuestionVocabularyInOneSentence() {
    String table =
        """
        | 住宿标准 | 城市 |
        | --- | --- |
        | 500 | 一线城市 |
        """;
    String rewritten = rewriter.rewrite(table);

    assertThat(rewritten).as("问「一线城市的住宿标准是多少」时，陈述句里同时有「住宿标准」与「一线城市」").contains("住宿标准：500；城市：一线城市");
  }

  // ────────────────────────── 判据 ③：两列分工 ──────────────────────────

  /**
   * ★★ 判据 ③ 本身：{@code content} 是<strong>原表</strong>、{@code search_text} 是<strong>陈述</strong>，
   * 且两列**不同**。
   *
   * <p>分工的意义：{@code content} 是给人看的（引用抽屉里要能溯源到用户看到的那张表）， {@code search_text}
   * 是给检索看的（行级陈述才与问题在同一个语言平面上）。
   */
  @Test
  void segmenterKeepsContentAsTableAndPutsStatementsInSearchText() {
    Segmenter segmenter = new Segmenter();
    SegmentPlan plan = segmenter.plan(TABLE, 1200, 0, 1200, "制度", "采购部", "差旅费管理办法");

    assertThat(plan.segments()).as("这张表应当切出一段").hasSize(1);
    var segment = plan.segments().getFirst();

    assertThat(segment.content())
        .as("content 必须是原表（含 | 与分隔行）—— 引用展示要能看到用户看到的那张表")
        .isEqualTo(TABLE.strip())
        .contains("| --- |");
    assertThat(segment.searchText())
        .as("search_text 里应当是陈述句")
        .contains("住宿标准：一线城市")
        .doesNotContain("| --- |");
    assertThat(segment.searchText()).as("★ 两列必须不同 —— 这正是判据 ③ 的断言").isNotEqualTo(segment.content());
    assertThat(segment.isTable()).as("分块器标注的表格标记要跟着走").isTrue();
  }

  /** ★ 非表格分段不受影响：{@code content} 与 {@code search_text} 的正文部分仍然同源。 */
  @Test
  void nonTableSegmentsAreUnchanged() {
    Segmenter segmenter = new Segmenter();
    SegmentPlan plan =
        segmenter.plan("## 第一章 总则\n\n本制度规定如下要点，适用于公司各部门。", 1200, 0, 1200, "制度", "采购部", "差旅费管理办法");

    var segment = plan.segments().getFirst();
    assertThat(segment.searchText()).contains(segment.content());
    assertThat(segment.isTable()).isFalse();
  }
}
