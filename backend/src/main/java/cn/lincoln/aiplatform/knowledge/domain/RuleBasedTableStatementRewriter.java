package cn.lincoln.aiplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 规则版的表格改写（{@link TableStatementRewriter} 的第一个实现）。
 *
 * <h3>三条规则（每条都有对应的表驱动用例）</h3>
 *
 * <ol>
 *   <li><strong>合并单元格填充</strong>（判据 ②）：DOCX 的合并单元格在 GFM 里表现为 <em>空格</em>或<em>列数变少</em>，两种都要补：
 *       <ul>
 *         <li><strong>纵向</strong>（一个单元格占多行）：该格为空 ⇒ 继承<strong>上一行同列</strong>的值；
 *         <li><strong>横向</strong>（一个单元格占多列）：该行列数少于表头 ⇒ 最后一格<strong>向右重复</strong>补齐。
 *       </ul>
 *       ★ 不补的后果不是"少一个词"而是<strong>语义错位</strong>：{@code | 一线城市 | | 500 |} 这种行 读起来像"这一列没有值"，而它其实是"同上"。
 *   <li><strong>行级陈述</strong>：每行 → {@code 列头：值；列头：值}（跳过空列头与空值）。
 *   <li><strong>字形归一</strong>：全角数字/字母/斜杠/冒号收成半角、连续空白收成一个空格、去掉行尾句号。 ★ 归一的是<strong>字形</strong>不是量纲 ——
 *       "500 元/天 与 500 元/次 会混"那件事要靠 "金额与单位另存字段"，是 V1 的事（{@code 12 §835}），别在这里假装解决了。
 * </ol>
 *
 * <h3>★ 长度护栏（不这么做就会打破一个算术恒等式）</h3>
 *
 * <p>{@code Segmenter} 有一个前提：{@code searchText ≤ maxChars}（{@code 12 §10.2} 风险 1 的处置），
 * 而它是按<strong>原表长度</strong>装箱算出来的。陈述（列头每行重复一遍）可能比原表<em>更长</em> —— 于是「估算器认为装得下、校验器却拒绝」，整篇文档 SEG-I1
 * 失败。 ⇒ 本实现保证<strong>改写结果不长于原表</strong>：超了就在"整句边界"上截断。
 */
public class RuleBasedTableStatementRewriter implements TableStatementRewriter {

  /** GFM 分隔行：{@code | --- | :---: |} —— 有它才敢认定这是表格（否则可能是正文里的竖线）。 */
  private static final Pattern SEPARATOR_ROW =
      Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

  /** 全角 → 半角（数字与常见标点）。中文标点（，。；）**不动** —— 它们是中文文本的一部分。 */
  private static final String[][] HALF_WIDTH = {
    {"０", "0"},
    {"１", "1"},
    {"２", "2"},
    {"３", "3"},
    {"４", "4"},
    {"５", "5"},
    {"６", "6"},
    {"７", "7"},
    {"８", "8"},
    {"９", "9"},
    {"Ａ", "A"},
    {"Ｂ", "B"},
    {"Ｃ", "C"},
    {"－", "-"},
    {"／", "/"},
    {"：", ":"},
  };

  @Override
  public String rewrite(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return markdown;
    }
    List<String> lines = markdown.lines().toList();
    int separatorIndex = findSeparatorRow(lines);
    if (separatorIndex <= 0) {
      return markdown; // 不是表格（或畸形表头）⇒ 原样返回，绝不猜
    }

    List<String> header = cellsOf(lines.get(separatorIndex - 1));
    if (header.isEmpty()) {
      return markdown;
    }
    List<List<String>> rows = new ArrayList<>();
    for (int i = separatorIndex + 1; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty()) {
        continue;
      }
      List<String> cells = cellsOf(line);
      if (cells.isEmpty()) {
        // 表体里混了非表格行（例如表后紧接着一段正文）⇒ 停在表尾，别把正文吞进来
        break;
      }
      rows.add(cells);
    }
    if (rows.isEmpty()) {
      return markdown;
    }

    List<List<String>> filled = fillMergedCells(header.size(), rows);
    List<String> statements = new ArrayList<>(filled.size());
    for (List<String> row : filled) {
      StringBuilder statement = new StringBuilder();
      for (int c = 0; c < header.size(); c++) {
        String column = normalize(header.get(c));
        String value = normalize(c < row.size() ? row.get(c) : "");
        if (column.isEmpty() || value.isEmpty()) {
          continue;
        }
        if (statement.length() > 0) {
          statement.append('；');
        }
        statement.append(column).append('：').append(value);
      }
      if (statement.length() > 0) {
        statements.add(statement.toString());
      }
    }
    if (statements.isEmpty()) {
      return markdown;
    }
    return capToOriginalLength(String.join("\n", statements), markdown.length());
  }

  // ────────────────────────── 规则 1 · 合并单元格填充 ──────────────────────────

  /**
   * 补合并单元格留下的两类空洞：列数不足 ⇒ 最后一格向右重复；空格 ⇒ 继承上一行同列。
   *
   * <p>★ 为什么"向右重复"而不是"留空"：{@code | 甲 | 500 |}（表头 3 列）在 GFM 里通常意味着 第 2、3 列被合并成了一个单元格 —— 值在第 2 列，重复到第
   * 3 列才不会让第 3 列的语义变成"空"。
   */
  private static List<List<String>> fillMergedCells(int width, List<List<String>> rows) {
    List<List<String>> filled = new ArrayList<>(rows.size());
    List<String> previous = null;
    for (List<String> row : rows) {
      List<String> next = new ArrayList<>(row);
      // 横向合并：列数不足 ⇒ 用最后一格补，直到与表头等宽
      while (next.size() < width && !next.isEmpty()) {
        next.add(next.get(next.size() - 1));
      }
      if (next.size() > width) {
        next = new ArrayList<>(next.subList(0, width));
      }
      // 纵向合并：空 ⇒ 继承上一行同列
      for (int c = 0; c < next.size(); c++) {
        if (next.get(c).isBlank() && previous != null && c < previous.size()) {
          next.set(c, previous.get(c));
        }
      }
      filled.add(next);
      previous = next;
    }
    return filled;
  }

  // ────────────────────────── 规则 3 · 字形归一 ──────────────────────────

  private static String normalize(String cell) {
    String text = cell == null ? "" : cell.strip();
    for (String[] pair : HALF_WIDTH) {
      text = text.replace(pair[0], pair[1]);
    }
    text = text.replaceAll("\\s+", " ").strip();
    while (text.endsWith("。") || text.endsWith(";") || text.endsWith("；")) {
      text = text.substring(0, text.length() - 1).strip();
    }
    return text;
  }

  // ────────────────────────── 长度护栏 ──────────────────────────

  /** 截到"不长于原表"：优先保留整句（{@code \n} 分行），最后一句放不下就整句丢掉。 */
  private static String capToOriginalLength(String statements, int limit) {
    if (statements.length() <= limit) {
      return statements;
    }
    StringBuilder kept = new StringBuilder();
    for (String statement : statements.split("\n")) {
      int extra = kept.length() == 0 ? statement.length() : statement.length() + 1;
      if (kept.length() + extra > limit) {
        break;
      }
      if (kept.length() > 0) {
        kept.append('\n');
      }
      kept.append(statement);
    }
    return kept.length() == 0 ? statements.substring(0, Math.max(0, limit)) : kept.toString();
  }

  // ────────────────────────── 解析 ──────────────────────────

  private static int findSeparatorRow(List<String> lines) {
    for (int i = 1; i < lines.size(); i++) {
      if (SEPARATOR_ROW.matcher(lines.get(i).strip()).matches()
          && lines.get(i - 1).strip().startsWith("|")) {
        return i;
      }
    }
    return -1;
  }

  /** 拆一行为单元格：去掉首尾的空段（GFM 的 {@code | a | b |} 形式）。 */
  private static List<String> cellsOf(String line) {
    String trimmed = line.strip();
    if (!trimmed.startsWith("|")) {
      return List.of();
    }
    List<String> raw = new ArrayList<>(List.of(trimmed.split("\\|", -1)));
    if (!raw.isEmpty() && raw.get(0).isBlank()) {
      raw.remove(0);
    }
    if (!raw.isEmpty() && raw.get(raw.size() - 1).isBlank()) {
      raw.remove(raw.size() - 1);
    }
    List<String> cells = new ArrayList<>(raw.size());
    for (String cell : raw) {
      cells.add(cell.strip());
    }
    return cells;
  }
}
