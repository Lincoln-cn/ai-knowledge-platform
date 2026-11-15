package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 结构化表格族 → GFM 表格（0.9.0）。
 *
 * <p>夹具在测试里用 POI <strong>现生成</strong>，不用二进制文件：二进制夹具改不了、看不出改了什么，而这几条断言要看的是 "合并区域铺平后到底是什么文本"。
 */
class SpreadsheetMarkdownParserTest {

  private final SpreadsheetMarkdownParser parser = new SpreadsheetMarkdownParser();

  /**
   * 最基本的形状：表头 + 两行数据，输出必须是 GFM 表格（{@code | a | b |} + 分隔行）。
   *
   * <p>★ 这条同时钉住"表头行只当表头"—— 第一行若又被当成数据行，输出里会出现两次 {@code 部门}。
   */
  @Test
  void xlsxBecomesGfmTable() throws Exception {
    byte[] bytes =
        xlsx(
            wb -> {
              Sheet sheet = wb.createSheet("台账");
              row(sheet, 0, "部门", "人数");
              row(sheet, 1, "技术部", "12");
              row(sheet, 2, "财务部", "5");
            });

    String markdown = parseOk(bytes, "台账.xlsx");

    assertThat(markdown)
        .isEqualTo(
            """
            | 部门 | 人数 |
            | --- | --- |
            | 技术部 | 12 |
            | 财务部 | 5 |
            """);
    assertThat(markdown).containsOnlyOnce("部门");
  }

  /**
   * 合并单元格必须按左上角的值铺平。
   *
   * <p>★ 不铺平的后果不是"少一个词"，是<strong>语义错位</strong>：{@code | 一线城市 | | 500 |} 读起来像"这一列没有值"， 其实它是"同上"。GFM
   * 没有合并单元格语法，只能在生成 Markdown 时补。
   */
  @Test
  void mergedCellsAreFilled() throws Exception {
    byte[] bytes =
        xlsx(
            wb -> {
              Sheet sheet = wb.createSheet("s");
              row(sheet, 0, "城市", "区", "数量");
              row(sheet, 1, "北京", "朝阳", "500");
              row(sheet, 2, "", "海淀", "300");
              sheet.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            });

    String markdown = parseOk(bytes, "s.xlsx");

    assertThat(markdown).contains("| 北京 | 朝阳 | 500 |");
    // 第二行的"北京"是合并区域铺下来的，不是空串
    assertThat(markdown).contains("| 北京 | 海淀 | 300 |");
  }

  /** 多 sheet 时每张表带自己的标题 —— 否则"汇总表"与"明细表"会被读成一张表的两半。 */
  @Test
  void multiSheetGetsTitles() throws Exception {
    byte[] bytes =
        xlsx(
            wb -> {
              row(wb.createSheet("汇总"), 0, "合计", "17");
              row(wb.createSheet("明细"), 0, "部门", "人数");
            });

    String markdown = parseOk(bytes, "多表.xlsx");

    assertThat(markdown).contains("## 汇总").contains("## 明细");
  }

  /** 单个 sheet 不再补标题：默认名 {@code Sheet1} 没有信息量，多一段就是噪音。 */
  @Test
  void singleSheetHasNoTitle() throws Exception {
    byte[] bytes =
        xlsx(
            wb -> {
              row(wb.createSheet("Sheet1"), 0, "a", "b");
            });

    String markdown = parseOk(bytes, "单表.xlsx");

    assertThat(markdown).startsWith("| a | b |").doesNotContain("## ");
  }

  /** 单元格里的竖线必须转义 —— 不转义会让整张表的列对齐错位，而渲染器不会报错。 */
  @Test
  void pipeInCellIsEscaped() throws Exception {
    byte[] bytes =
        xlsx(
            wb -> {
              Sheet sheet = wb.createSheet("s");
              row(sheet, 0, "表达式", "说明");
              row(sheet, 1, "a|b", "按位或");
            });

    String markdown = parser.parse(bytes, "s.xlsx").markdown();

    assertThat(markdown).contains("a\\|b");
  }

  /** CSV 走同一条 GFM 生成路径，且表头只出现一次。 */
  @Test
  void csvBecomesGfmTable() {
    String csv = "部门,人数\n技术部,12\n财务部,5\n";
    ParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "台账.csv");

    assertThat(result.success()).isTrue();
    assertThat(result.markdown())
        .isEqualTo(
            """
            | 部门 | 人数 |
            | --- | --- |
            | 技术部 | 12 |
            | 财务部 | 5 |
            """);
  }

  /**
   * 引号里的逗号与换行是字段内容，不是分隔符（RFC 4180）。
   *
   * <p>★ 这条防的是"按 {@code split(",")} 切"那种实现：一列里带逗号的备注会把整行切歪。
   */
  @Test
  void quotedFieldKeepsDelimiterAndNewline() {
    String csv = "名称,备注\nA,\"含,逗号\"\nB,\"第一行\n第二行\"\n";
    ParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "x.csv");

    assertThat(result.markdown()).contains("| A | 含,逗号 |");
    // 字段内换行被收成一个空格（GFM 单元格不能跨行）
    assertThat(result.markdown()).contains("| B | 第一行 第二行 |");
  }

  /**
   * GB18030 的 CSV 必须按 GB18030 读。
   *
   * <p>★ 读错的形态是最坏的那种：整篇变成替换字符，而解析<strong>成功</strong>、日志一条不报，入库后检索全乱。
   */
  @Test
  void gb18030CsvIsDecoded() {
    Charset gb = Charset.forName("GB18030");
    String csv = "部门,人数\n技术部,12\n";
    ParseResult result = parser.parse(csv.getBytes(gb), "台账.csv");

    assertThat(result.success()).isTrue();
    assertThat(result.markdown()).contains("技术部").doesNotContain("\uFFFD");
  }

  /** TSV 用制表符分隔，不是逗号。 */
  @Test
  void tsvUsesTabDelimiter() {
    String tsv = "部门\t人数\n技术部\t12\n";
    ParseResult result = parser.parse(tsv.getBytes(StandardCharsets.UTF_8), "x.tsv");

    assertThat(result.markdown()).contains("| 技术部 | 12 |");
  }

  /** 空表格给可读原因，不是空串当成功。 */
  @Test
  void emptyTableFails() {
    ParseResult result = parser.parse("\n\n".getBytes(StandardCharsets.UTF_8), "空.csv");

    assertThat(result.success()).isFalse();
    assertThat(result.failReason()).contains("没有可读内容");
  }

  /** 不认识的扩展名不归本类管（由调用方挡在前面）。 */
  @Test
  void unknownExtensionFails() {
    ParseResult result = parser.parse("abc".getBytes(StandardCharsets.UTF_8), "x.docx");

    assertThat(result.success()).isFalse();
    assertThat(result.failReason()).contains("不支持");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private String parseOk(byte[] bytes, String filename) {
    ParseResult result = parser.parse(bytes, filename);
    assertThat(result.success()).as("解析应成功：%s", result.failReason()).isTrue();
    return result.markdown();
  }

  private static byte[] xlsx(Consumer<XSSFWorkbook> filler) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      filler.accept(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void row(Sheet sheet, int index, String... values) {
    Row row = sheet.getRow(index);
    if (row == null) {
      row = sheet.createRow(index);
    }
    for (int c = 0; c < values.length; c++) {
      row.createCell(c).setCellValue(values[c]);
    }
  }
}
