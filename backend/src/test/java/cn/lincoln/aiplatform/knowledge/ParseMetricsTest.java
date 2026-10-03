package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.NoiseStripper;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties;
import cn.lincoln.aiplatform.knowledge.infrastructure.StripResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 解析实测数字表（第 6 周 · 0.2.1 批 2）—— 结构丢失率 + 噪声剥离误杀率。
 *
 * <p>★ 为什么必须是「跑出来」的而不是手抄：{@code ROADMAP} 0.2.1 明确要求<strong>产出实测数字表</strong>， 「效果不错」不算交付。数字由本测试从
 * {@code ground-truth.json} + 真实解析/剥离链路现算， 跑一次 {@code mvn test -Dtest=ParseMetricsTest} 即可刷新 {@code
 * docs/技术结论/0.2.1-解析实测.md}。
 *
 * <p>★ 方法（写进报告，不藏在代码里）：
 *
 * <ul>
 *   <li><strong>结构丢失率</strong> = 1 −（解析输出中可识别的结构单元数 / 基准结构单元数）。 基准来自夹具生成脚本（标题/列表项/表格单元格数量已知）； 输出端按
 *       Markdown 结构语法（{@code #} 标题 / 列表行 / 表格行）计数。
 *   <li><strong>误杀率</strong> = 噪声剥离删掉的<strong>正文行</strong> / 正文总行数。 正文行清单来自 {@code
 *       ground-truth.json} 的 {@code bodyLines} —— 每一行都必须在剥离后的产物里找得到。
 * </ul>
 */
class ParseMetricsTest {

  private final TikaMarkdownParser parser = new TikaMarkdownParser(ocrOff());
  private final NoiseStripper stripper = new NoiseStripper();

  /**
   * ★ 单测用<strong>关掉 OCR</strong>的引擎：这些用例量的是"结构丢失率 / 误杀率"，而 OCR 是<strong>系统级依赖</strong> （装了
   * tesseract 才跑）。让它随开发机有没有装 tesseract 而变，等于拿"环境恰好有"当测试前提 —— 那会让同一份读数在两台机器上不一样。OCR 自己的验收在 {@code
   * OcrParserTest} 与真栈闸门。
   */
  private static OcrEngine ocrOff() {
    return new OcrEngine(new OcrProperties(false, null, null, "chi_sim+eng", 300, 120, true));
  }

  private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+.*");
  private static final Pattern MD_LIST = Pattern.compile("(?m)^\\s*([-*+]|\\d+[.)])\\s+.*");
  private static final Pattern MD_TABLE_ROW = Pattern.compile("(?m)^\\|.*\\|$");

  private static byte[] fixture(String name) {
    try (InputStream in = ParseMetricsTest.class.getResourceAsStream("/fixtures/" + name)) {
      assertThat(in).as("夹具 %s 不在 classpath —— 先跑 generate_parse_fixtures.py", name).isNotNull();
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("读夹具失败：" + name, e);
    }
  }

  private static JsonNode groundTruth() throws IOException {
    try (InputStream in =
        ParseMetricsTest.class.getResourceAsStream("/fixtures/ground-truth.json")) {
      assertThat(in)
          .as("ground-truth.json 不在 classpath —— 先跑 generate_parse_fixtures.py")
          .isNotNull();
      return new ObjectMapper().readTree(in);
    }
  }

  /** 结构单元计数：标题行 + 列表行 + 表格单元格。 */
  private static int countStructureUnits(String md) {
    int headings = countMatches(MD_HEADING, md);
    int lists = countMatches(MD_LIST, md);
    // 表格按「单元格」计：每行 | a | b | c | ⇒ 3 格（去掉首尾空段）
    int tableCells = 0;
    for (String line : md.split("\n")) {
      if (MD_TABLE_ROW.matcher(line).matches() && !line.contains("---")) {
        String[] parts = line.split("\\|", -1);
        // split 后首尾是空串 ⇒ 真实单元格数 = parts.length - 2
        tableCells += Math.max(0, parts.length - 2);
      }
    }
    return headings + lists + tableCells;
  }

  private static int countMatches(Pattern p, String text) {
    int n = 0;
    var m = p.matcher(text);
    while (m.find()) {
      n++;
    }
    return n;
  }

  /**
   * 整行比对（trim 后相等）。
   *
   * <p>★ 不能用 {@code contains}：噪声里的短行（如页码「3」）会误命中正文列表项「3. 归档备案」的子串 —— 那样 误杀率/剥离率都会失真。
   */
  private static boolean linePresent(String md, String line) {
    String target = line.trim();
    for (String s : md.split("\n")) {
      if (s.trim().equals(target)) {
        return true;
      }
    }
    return false;
  }

  /** 行内包含：正文以列表项/段落形态存在也算保留（「- 适用公司各部门」覆盖正文「适用公司各部门」）。 */
  private static boolean lineContains(String md, String body) {
    String target = body.trim();
    for (String s : md.split("\n")) {
      if (s.trim().contains(target)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★ 结构丢失率表：按文档类型分别给数字 —— DOCX 列表 / PDF 结构 / 扫描件占比。
   *
   * <p>基准结构已知（夹具是自己造的），所以「输入有多少标题/列表项/表格单元格，输出还剩多少」可精确计算。
   */
  @Test
  void 结构丢失率数字表_按文档类型() throws IOException {
    JsonNode gt = groundTruth();
    List<String[]> rows = new ArrayList<>();

    for (String fixtureName :
        List.of("structured.docx", "structured.pdf", "noisy.docx", "noisy.pdf")) {
      JsonNode spec = gt.get(fixtureName);
      assertThat(spec).as("ground-truth 缺 %s", fixtureName).isNotNull();
      int baseline =
          spec.path("headings").size()
              + spec.path("listItems").size()
              + spec.path("tableCells").size();
      ParseResult parsed = parser.parse(fixture(fixtureName), fixtureName);
      assertThat(parsed.success()).as("%s 必须解析成功", fixtureName).isTrue();
      int kept = countStructureUnits(parsed.markdown());
      double lossRate = baseline == 0 ? 0.0 : 1.0 - (double) kept / baseline;
      rows.add(
          new String[] {
            fixtureName,
            String.valueOf(baseline),
            String.valueOf(kept),
            String.format(Locale.ROOT, "%.1f%%", lossRate * 100)
          });
    }

    // 扫描件：无文本层 ⇒ 结构 100% 丢失（0 字符 ≠ 成功已在 DocumentParseTest 钉住）
    ParseResult scanned = parser.parse(fixture("scanned-like.pdf"), "scanned-like.pdf");
    assertThat(scanned.success()).as("扫描件样张不应解析成功（无文本层）").isFalse();
    rows.add(new String[] {"scanned-like.pdf（扫描件）", "—", "0", "100.0%"});

    StringBuilder table = new StringBuilder();
    table.append("| 文档类型 | 基准结构单元 | 输出残留 | 结构丢失率 |\n");
    table.append("|---|---:|---:|---:|\n");
    for (String[] r : rows) {
      table
          .append("| ")
          .append(r[0])
          .append(" | ")
          .append(r[1])
          .append(" | ")
          .append(r[2])
          .append(" | ")
          .append(r[3])
          .append(" |\n");
    }

    // ★ 断言与报告同源：DOCX 结构必须基本保住，PDF 结构允许大比例丢失（12 §2.2 已知上限）
    for (String[] r : rows) {
      if (r[0].startsWith("structured.docx") || r[0].startsWith("noisy.docx")) {
        assertThat(Double.parseDouble(r[3].replace("%", "")))
            .as("DOCX 结构丢失率应较低：%s", r[0])
            .isLessThan(40.0);
      }
    }

    writeReportSection("结构丢失率（按文档类型）", table.toString(), structureMethod());
    System.out.println("=== 结构丢失率 ===\n" + table);
  }

  /**
   * ★ 噪声剥离误杀率：剥离规则把多少<strong>正文</strong>误删了。
   *
   * <p>正文行来自 ground-truth，每行都要在剥离产物里找得到 —— 少一行就是一次误杀。
   */
  @Test
  void 噪声剥离误杀率数字表() throws IOException {
    JsonNode gt = groundTruth();
    List<String[]> rows = new ArrayList<>();

    for (String fixtureName : List.of("noisy.docx", "noisy.pdf")) {
      JsonNode spec = gt.get(fixtureName);
      List<String> bodyLines = new ArrayList<>();
      spec.path("bodyLines").forEach(n -> bodyLines.add(n.asText()));
      List<String> noiseLines = new ArrayList<>();
      spec.path("noiseLines").forEach(n -> noiseLines.add(n.asText()));

      ParseResult parsed = parser.parse(fixture(fixtureName), fixtureName);
      assertThat(parsed.success()).as("%s 必须解析成功", fixtureName).isTrue();
      StripResult stripped = stripper.strip(parsed.markdown());

      int falseKills = 0;
      List<String> killed = new ArrayList<>();
      for (String body : bodyLines) {
        // 正文允许以列表项/段落形态存在（「- 适用公司各部门」也算在），故用「行内包含」而非整行相等
        if (!lineContains(stripped.markdown(), body)) {
          falseKills++;
          killed.add(body);
        }
      }
      int noiseRemoved = 0;
      for (String noise : noiseLines) {
        if (!linePresent(stripped.markdown(), noise)) {
          noiseRemoved++;
        }
      }
      double falseKillRate = bodyLines.isEmpty() ? 0.0 : (double) falseKills / bodyLines.size();
      rows.add(
          new String[] {
            fixtureName,
            String.valueOf(bodyLines.size()),
            String.valueOf(falseKills),
            String.format(Locale.ROOT, "%.1f%%", falseKillRate * 100),
            noiseLines.isEmpty() ? "—" : noiseRemoved + "/" + noiseLines.size(),
            killed.isEmpty() ? "（无）" : String.join("；", killed)
          });
    }

    StringBuilder table = new StringBuilder();
    table.append("| 样本 | 正文行数 | 误杀行数 | 误杀率 | 噪声剥离 | 误杀清单 |\n");
    table.append("|---|---:|---:|---:|---|---|\n");
    for (String[] r : rows) {
      table
          .append("| ")
          .append(r[0])
          .append(" | ")
          .append(r[1])
          .append(" | ")
          .append(r[2])
          .append(" | ")
          .append(r[3])
          .append(" | ")
          .append(r[4])
          .append(" | ")
          .append(r[5])
          .append(" |\n");
    }

    // ★ 铁律：误杀率必须是 0 —— 正文不许被剥掉（任务书「正文不许误杀」）
    for (String[] r : rows) {
      assertThat(Integer.parseInt(r[2])).as("误杀正文行数必须为 0：%s 误杀清单=%s", r[0], r[5]).isZero();
    }

    writeReportSection("噪声剥离误杀率", table.toString(), falseKillMethod());
    System.out.println("=== 误杀率 ===\n" + table);
  }

  private static String structureMethod() {
    return """
    **方法**：基准结构单元 = 夹具生成脚本写入的 标题数 + 列表项数 + 表格单元格数（ground-truth.json）。
    输出残留 = 解析成 Markdown 后按结构语法计数（`#` 标题行、`-`/`1.` 列表行、`|` 表格单元格）。
    结构丢失率 = 1 −（输出残留 / 基准结构单元）。扫描件样张无文本层，解析成功为 false，结构按 100% 丢失计。
    样本：`structured.docx` / `structured.pdf`（无噪声基准）、`noisy.docx` / `noisy.pdf`（含噪声）、`scanned-like.pdf`（扫描件）。
    ★ 已知上限见 `12 §2.2`：普通 PDF 只有段落流，标题/表格/列表的结构语法本来就会丢，字面文本仍在。
    """;
  }

  private static String falseKillMethod() {
    return """
    **方法**：正文行清单来自 ground-truth.json 的 `bodyLines`（夹具生成时人工划分：噪声 vs 正文）。
    跑 Tika 解析 → NoiseStripper 剥离 → 逐行检查每条正文是否仍在产物里（**行内包含**：「- 适用公司各部门」覆盖正文「适用公司各部门」；噪声侧用**整行相等**，避免短噪声「3」误命中列表项「3. 归档备案」的子串）。
    误杀行数 = 剥离后找不到的正文行；误杀率 = 误杀行数 / 正文总行数。
    噪声剥离列 = 被剥掉的噪声行 / 噪声总行数（反映剥离召回，越高越好）。
    ★ 规则阈值按 `12 §2.4` 保守取值（重复 ≥3 次 + 长度 <60 字 + 非结构行），宁可漏剥不误杀。
    """;
  }

  /** 把一节写进报告文件。报告按节覆盖，重复运行即可刷新数字。 */
  private static void writeReportSection(String title, String table, String method)
      throws IOException {
    Path report = resolveReportPath();
    String header =
        """
        # 0.2.1 解析实测（第 6 周 · 批 2）

        > 本文件由 `ParseMetricsTest` 自动生成，**不要手改数字**。
        > 刷新：`mvn test -Dtest=ParseMetricsTest`（先跑 `python backend/tools/generate_parse_fixtures.py` 保证夹具与基准最新）。
        > 基准数据源：`backend/src/test/resources/fixtures/ground-truth.json`（生成脚本导出，非手抄）。

        """;
    String section = "## " + title + "\n\n" + table + "\n" + method + "\n---\n\n";

    String existing =
        Files.exists(report) ? Files.readString(report, StandardCharsets.UTF_8) : header;
    if (!existing.startsWith("# 0.2.1")) {
      existing = header + existing;
    }
    // ★ 规范化空白：按节替换是字符串手术，残留的空行会**每次运行累加一行** ⇒ 报告文件反复变脏。
    // 幂等是这份报告的硬要求（它是生成物，diff 应该只反映数字变化）。
    existing = normalizeBlankRuns(existing);
    // 按节替换：找到 "## <title>" 到下一个 "---" 为止
    String marker = "## " + title;
    int start = existing.indexOf(marker);
    if (start < 0) {
      existing = existing + section;
    } else {
      int end = existing.indexOf("\n---\n", start);
      if (end < 0) {
        existing = existing.substring(0, start) + section;
      } else {
        existing = existing.substring(0, start) + section + existing.substring(end + 5);
      }
    }
    Files.createDirectories(report.getParent());
    Files.writeString(report, normalizeBlankRuns(existing), StandardCharsets.UTF_8);
  }

  /** 把连续 3 个以上的换行压成 1 个空行（保证报告幂等：重复运行 ⇒ 字节完全一致）。 */
  private static String normalizeBlankRuns(String text) {
    String normalized = text.replaceAll("\n{3,}", "\n\n");
    return normalized.endsWith("\n") ? normalized : normalized + "\n";
  }

  private static Path resolveReportPath() {
    // 从 backend/ 工作目录回项目根找 docs/
    Path cwd = Path.of("").toAbsolutePath();
    Path root = cwd;
    for (int i = 0; i < 4; i++) {
      if (Files.isDirectory(root.resolve("docs"))) {
        return root.resolve("docs").resolve("技术结论").resolve("0.2.1-解析实测.md");
      }
      root = root.getParent();
      if (root == null) {
        break;
      }
    }
    return cwd.resolve("0.2.1-解析实测.md");
  }
}
