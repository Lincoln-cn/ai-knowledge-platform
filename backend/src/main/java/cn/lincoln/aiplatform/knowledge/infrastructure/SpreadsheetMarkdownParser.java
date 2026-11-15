package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构化表格族（xlsx / xls / csv / tsv）→ GFM 表格（0.9.0）。
 *
 * <h3>为什么要有它：解析器认、链路只认一半</h3>
 *
 * <p>表格的<strong>下游</strong>早就有了：{@link cn.lincoln.aiplatform.knowledge.domain.MarkdownChunker} 把
 * GFM 表格当成<strong>独占块</strong>（不与相邻正文合并），{@link
 * cn.lincoln.aiplatform.knowledge.domain.RuleBasedTableStatementRewriter} 还会把它改写成按行的陈述句（合并单元格填充 →
 * {@code 列头：值；列头：值} → 字形归一）。 缺的只是<strong>上游这一步</strong>：把 xlsx/csv 变成 GFM 表格。
 *
 * <p>因此本类<strong>只做格式转换</strong>，<strong>不做</strong>切分、不做改写、不做向量化 —— 那三件事已经在链路上，重做一遍就是两套口径。
 *
 * <h3>四条设计决定</h3>
 *
 * <ul>
 *   <li><strong>合并单元格按左上角值填充</strong>：{@code | 一线城市 | | 500 |} 读起来像"这一列没有值"，其实它是"同上"（{@code
 *       RuleBasedTableStatementRewriter} 的 javadoc 记的是同一件事：不补的后果是<strong>语义错位</strong>）。
 *       这里在<strong>生成 Markdown 时</strong>就把合并区域铺平，因为 GFM 没有合并单元格语法 —— 不铺平，下游拿到的就是空串。
 *   <li><strong>多 sheet 时给每个表加 {@code ## 表名}</strong>：sheet 名是作者给的分组，"汇总表"与"明细表"混成一张表是错的。 单 sheet
 *       不加标题 —— 否则每份 Excel 都凭空多一段，且默认名 {@code Sheet1} 毫无信息。
 *   <li><strong>CSV 编码要探测</strong>：国内导出的 CSV 常是 GB18030。直接按 UTF-8 读会把整篇变成替换字符，而解析"成功" —— 这是 {@code
 *       silent-failure} 的典型形态（入库后检索全乱，日志一条不报）。
 *   <li><strong>表头行只当表头</strong>：第一行进 GFM 表头，<strong>不</strong>再作为数据行重复一遍。
 * </ul>
 *
 * <h3>失败口径</h3>
 *
 * <p>与 {@link TikaMarkdownParser} 一致：{@link ParseResult#failReason()} 是给用户看的句子（「这个 Excel
 * 已加密，无法解析」），不是异常类名。
 */
@org.springframework.stereotype.Component
public class SpreadsheetMarkdownParser {

  private static final Logger log = LoggerFactory.getLogger(SpreadsheetMarkdownParser.class);

  /** 本类认的扩展名。★ 唯一真源 —— {@link TikaMarkdownParser#supportedExtensions()} 从这里取。 */
  private static final Set<String> EXTENSIONS = Set.of("xlsx", "xls", "csv", "tsv");

  private static final Charset GB18030 = Charset.forName("GB18030");

  /** 单个工作表最多读多少行 —— 防止有人传一张 50 万行的流水账把内存撑爆。 */
  private static final int MAX_ROWS_PER_SHEET = 5000;

  /** 单元格值格式化：数字不要带成 {@code 3.0}，日期用可读形式。 */
  private static final DataFormatter FORMATTER = new DataFormatter();

  /** 本类负责的扩展名（小写，不含点）。 */
  public static Set<String> extensions() {
    return EXTENSIONS;
  }

  /**
   * 是否是本类负责的格式。
   *
   * @param extension 小写扩展名（不含点）
   */
  public static boolean supports(String extension) {
    return extension != null && EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
  }

  /**
   * 把表格文件解析成 Markdown。
   *
   * @param content 文件原始字节
   * @param filename 原始文件名（用于错误文案与扩展名判断）
   */
  public ParseResult parse(byte[] content, String filename) {
    String ext = extensionOf(filename);
    try {
      String markdown =
          switch (ext) {
            case "xlsx", "xls" -> fromWorkbook(content);
            case "csv" -> fromDelimited(content, ',', "CSV");
            case "tsv" -> fromDelimited(content, '\t', "TSV");
            default -> null;
          };
      if (markdown == null) {
        return ParseResult.failed("不支持的表格类型（.%s）。当前支持 xlsx / xls / csv / tsv。".formatted(ext), "");
      }
      if (markdown.isBlank()) {
        return ParseResult.failed("表格里没有可读内容：所有单元格都是空的。", "");
      }
      return ParseResult.ok(markdown, 0, 0);
    } catch (Exception e) {
      log.warn("表格解析失败：{}（{}）", filename, e.toString());
      return ParseResult.failed(describeFailure(e, ext), "");
    }
  }

  // ────────────────────────── xlsx / xls ──────────────────────────

  private String fromWorkbook(byte[] content) throws Exception {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      int sheetCount = workbook.getNumberOfSheets();
      List<String> blocks = new ArrayList<>();
      for (int i = 0; i < sheetCount; i++) {
        Sheet sheet = workbook.getSheetAt(i);
        List<List<String>> grid = readSheet(sheet);
        if (grid.isEmpty()) {
          continue;
        }
        // ★ 多 sheet 才加标题：单 sheet 加一个 `## Sheet1` 是噪音（且它多半是默认名）
        String title = sheetCount > 1 ? "## " + safeTitle(sheet.getSheetName()) + "\n\n" : "";
        blocks.add(title + toGfmTable(grid));
      }
      return String.join("\n\n", blocks);
    }
  }

  private List<List<String>> readSheet(Sheet sheet) {
    // ★ 先铺平合并区域：GFM 没有合并单元格，不铺平下游拿到的就是空串
    List<CellRangeAddress> merged = sheet.getMergedRegions();
    int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS_PER_SHEET - 1);
    int width = 0;
    List<String[]> raw = new ArrayList<>();
    for (int r = 0; r <= lastRow; r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        raw.add(new String[0]);
        continue;
      }
      int lastCell = row.getLastCellNum();
      String[] cells = new String[Math.max(lastCell, 0)];
      for (int c = 0; c < lastCell; c++) {
        cells[c] = cellText(row.getCell(c));
      }
      width = Math.max(width, cells.length);
      raw.add(cells);
    }
    if (width == 0) {
      return List.of();
    }
    // 合并区域：左上角的值铺到整个区域
    for (CellRangeAddress range : merged) {
      if (range.getFirstRow() > lastRow) {
        continue;
      }
      int topRow = range.getFirstRow();
      int leftCol = range.getFirstColumn();
      if (topRow >= raw.size() || leftCol >= raw.get(topRow).length) {
        continue;
      }
      String value = raw.get(topRow)[leftCol];
      if (value == null || value.isBlank()) {
        continue;
      }
      for (int r = range.getFirstRow(); r <= Math.min(range.getLastRow(), lastRow); r++) {
        for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
          raw.set(r, widen(raw.get(r), width));
          String[] cells = raw.get(r);
          if (c >= cells.length) {
            continue;
          }
          if (cells[c] == null || cells[c].isBlank()) {
            cells[c] = value;
          }
        }
      }
    }
    // 补齐列宽 + 丢掉全空行
    List<List<String>> grid = new ArrayList<>();
    for (String[] cells : raw) {
      String[] padded = widen(cells, width);
      if (isAllBlank(padded)) {
        continue;
      }
      grid.add(List.of(padded));
    }
    return grid;
  }

  private static String[] widen(String[] cells, int width) {
    if (cells.length >= width) {
      return cells;
    }
    String[] out = new String[width];
    System.arraycopy(cells, 0, out, 0, cells.length);
    for (int i = cells.length; i < width; i++) {
      out[i] = "";
    }
    return out;
  }

  private static boolean isAllBlank(String[] cells) {
    for (String cell : cells) {
      if (cell != null && !cell.isBlank()) {
        return false;
      }
    }
    return true;
  }

  private static String cellText(Cell cell) {
    if (cell == null) {
      return "";
    }
    try {
      return FORMATTER.formatCellValue(cell).trim();
    } catch (RuntimeException e) {
      // ★ 单个单元格坏掉不许拖垮整张表
      return "";
    }
  }

  // ────────────────────────── csv / tsv ──────────────────────────

  private String fromDelimited(byte[] content, char delimiter, String label) {
    Charset charset = detectCharset(content);
    String text = new String(content, charset);
    if (!StandardCharsets.UTF_8.equals(charset)) {
      // ★ 这一行是「读对了」的可观测面：读错编码时整篇是乱码而解析"成功"，日志里必须有痕迹
      log.info("{} 不是 UTF-8（探测为 {}），已按该编码读取", label, charset.name());
    }
    List<List<String>> rows = splitDelimited(text, delimiter);
    if (rows.isEmpty()) {
      return "";
    }
    int width = 0;
    for (List<String> row : rows) {
      width = Math.max(width, row.size());
    }
    List<List<String>> grid = new ArrayList<>();
    for (List<String> row : rows) {
      List<String> padded = new ArrayList<>(row);
      while (padded.size() < width) {
        padded.add("");
      }
      if (padded.stream().allMatch(String::isBlank)) {
        continue;
      }
      grid.add(padded);
    }
    return toGfmTable(grid);
  }

  /**
   * RFC 4180 的最小实现：引号包起来的字段里可以有分隔符与换行，连续两个引号是一个引号。
   *
   * <p>★ 不引新库：仓库今天没有 CSV 依赖，{@code TextAndCSVParser} 输出的是格式化文本（不是表格结构），拿不到单元格边界。
   */
  private static List<List<String>> splitDelimited(String text, char delimiter) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          field.append(ch);
        }
        continue;
      }
      if (ch == '"') {
        inQuotes = true;
      } else if (ch == delimiter) {
        row.add(field.toString().trim());
        field.setLength(0);
      } else if (ch == '\r') {
        // 吃掉，等下个 \n 或直接结束
      } else if (ch == '\n') {
        row.add(field.toString().trim());
        field.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else {
        field.append(ch);
      }
    }
    if (field.length() > 0 || !row.isEmpty()) {
      row.add(field.toString().trim());
      rows.add(row);
    }
    return rows;
  }

  /**
   * 探测编码：BOM → UTF-16/UTF-8；否则用**严格解码**试 UTF-8，失败即 GB18030。
   *
   * <p>★ 为什么不用 Tika 的 {@code CharsetDetector}：它给的是置信度，而这里要的是"敢不敢按 UTF-8 读"。严格解码是二值判断， 且不需要额外依赖 ——
   * 一个 GB18030 的中文句子几乎不可能同时是合法 UTF-8。
   */
  private static Charset detectCharset(byte[] bytes) {
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF) {
      return StandardCharsets.UTF_8;
    }
    if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
      return StandardCharsets.UTF_16LE;
    }
    if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
      return StandardCharsets.UTF_16BE;
    }
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes));
      return StandardCharsets.UTF_8;
    } catch (CharacterCodingException e) {
      return GB18030;
    }
  }

  // ────────────────────────── GFM ──────────────────────────

  /**
   * 二维表 → GFM 表格（第一行是表头）。
   *
   * <p>★ 单元格里的 {@code |} 必须转义成 {@code \|}，否则一个值就能把整张表的列对齐打乱 —— 而 Markdown 渲染器不会报错， 它只会把后面几列挤错位。
   */
  private static String toGfmTable(List<List<String>> grid) {
    if (grid.isEmpty()) {
      return "";
    }
    int width = 0;
    for (List<String> row : grid) {
      width = Math.max(width, row.size());
    }
    if (width == 0) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    appendRow(out, grid.get(0), width);
    out.append('|');
    for (int c = 0; c < width; c++) {
      out.append(" --- |");
    }
    out.append('\n');
    for (int r = 1; r < grid.size(); r++) {
      appendRow(out, grid.get(r), width);
    }
    return out.toString();
  }

  private static void appendRow(StringBuilder out, List<String> row, int width) {
    out.append('|');
    for (int c = 0; c < width; c++) {
      String value = c < row.size() ? row.get(c) : "";
      out.append(' ').append(escape(value)).append(" |");
    }
    out.append('\n');
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("|", "\\|").replaceAll("[\\r\\n]+", " ").trim();
  }

  private static String safeTitle(String name) {
    if (name == null || name.isBlank()) {
      return "表";
    }
    return escape(name);
  }

  // ────────────────────────── 其它 ──────────────────────────

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** 可读原因。★ 不把异常类名 / 堆栈放进 failReason —— 用户改不了也看不懂。 */
  private static String describeFailure(Exception e, String ext) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("encrypt") || message.contains("密码")) {
      return "这个表格已加密，无法解析。请先取消密码后重新上传。";
    }
    if ("xlsx".equals(ext) || "xls".equals(ext)) {
      return "Excel 文件已损坏或不是有效的 xlsx/xls，无法解析。";
    }
    return "表格文件格式异常，无法解析。请检查文件是否完整后重试。";
  }
}
