package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties;
import cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tika 解析成 Markdown（第 6 周 · 0.2.1 批 1）—— 标题 / 列表 / 表格 三类结构 + 失败可读。
 *
 * <p>★ 断言都对着「会静默出错」的地方（与 {@code DocumentRegistrationTest} 同一条纪律）：
 *
 * <ol>
 *   <li><strong>DOCX 三类结构在</strong>：多级标题 + 有序/无序列表 + 表格 ⇒ Markdown 里三类都能认出来 ——
 *       缺一类就等于分块器少了一路信号（{@code 12 §2.1}）
 *   <li><strong>PDF 至少保住正文</strong>：普通 PDF 不带 structure tag，标题/表格/列表的 <em>结构</em>会丢（{@code 12
 *       §2.2}），但<strong>字面文本必须在</strong> —— 断言写清保住的是什么
 *   <li><strong>GBK 中文不乱码</strong>：编码检测走 Tika {@code CharsetDetector}（{@code 12 §一}），不另引库
 *   <li><strong>失败可读 + 部分正文仍在</strong>：加密/损坏 ⇒ {@code SEGMENT_FAILED} + 可读 {@code failReason}
 *       （{@code DOC-I2}）+ 已缓冲的部分内容不整篇丢（{@code 12 §2.1} 的 catch 分支）
 *   <li><strong>状态推进</strong>：成功 ⇒ {@code PENDING_EMBEDDING}；失败 ⇒ {@code SEGMENT_FAILED} （取值 {@code
 *       SCREAMING_SNAKE_CASE}，{@code 14 §2.1}）
 * </ol>
 *
 * <p>★ 夹具由 {@code backend/tools/generate_parse_fixtures.py} 可复现生成，落在 {@code
 * src/test/resources/fixtures/} —— 二进制坏了能从脚本重造，而不是猜。
 */
class DocumentParseTest {

  private final TikaMarkdownParser parser = new TikaMarkdownParser(ocrOff());

  /**
   * ★ 单测用<strong>关掉 OCR</strong>的引擎：这些用例验的是"文本型文档的解析"，而 OCR 是<strong>系统级依赖</strong> （装了 tesseract
   * 才跑）。让它随开发机有没有装 tesseract 而变，等于拿"环境恰好有"当测试前提 —— 那是本项目最不想要的一类前提。OCR 自己的验收在 {@code OcrParserTest}
   * 与真栈闸门 {@code check_ocr_e2e.py}。
   */
  private static OcrEngine ocrOff() {
    return new OcrEngine(new OcrProperties(false, null, null, "chi_sim+eng", 300, 120, true));
  }

  private static byte[] fixture(String name) {
    try (InputStream in = DocumentParseTest.class.getResourceAsStream("/fixtures/" + name)) {
      assertThat(in).as("夹具 %s 不在 classpath —— 先跑 generate_parse_fixtures.py", name).isNotNull();
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("读夹具失败：" + name, e);
    }
  }

  private static Document newDocument() {
    return new Document(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "解析测试.docx",
        "local",
        "制度",
        "采购部",
        ClassificationLevel.INTERNAL,
        UUID.randomUUID());
  }

  // ────────────────────────── 1 · DOCX：标题 / 列表 / 表格 ──────────────────────────

  /**
   * ★ DOCX 是三种格式里结构保真度最好的（{@code 12 §2.2}）：标题层级 + 表格 Tika 都能出。 列表必须以 <strong>Markdown
   * 列表语法</strong>出现（{@code -}/{@code 1.} 开头的行）， 而不只是「字面编号还在」—— 分块器认的是列表语法，不是段首的「1.」两个字符。
   */
  @Test
  void DOCX解析出标题列表与表格三类Markdown结构() {
    ParseResult result = parser.parse(fixture("structured.docx"), "structured.docx");

    assertThat(result.success()).as("结构完好的 DOCX 必须解析成功：%s", result.failReason()).isTrue();
    String md = result.markdown();

    // 标题：至少三级（# / ## / ###）都要在
    assertThat(md).as("多级标题丢失 ⇒ 章节路径拼不出来（12 §3.4）").contains("# 第一章 总则");
    assertThat(md).contains("## 1.1 制定目的");
    assertThat(md).contains("### 1.1.1 适用范围");

    // 列表：无序 + 有序，都要是 Markdown 列表行（不是纯段落）
    assertThat(md)
        .as("无序列表必须以 Markdown 列表语法出现（- 或 * 开头行）")
        .containsPattern("(?m)^\\s*[-*+]\\s+适用公司各部门\\s*$");
    assertThat(md)
        .as("有序列表必须以 Markdown 列表语法出现（1. 开头行）")
        .containsPattern("(?m)^\\s*\\d+\\.\\s+提交申请\\s*$");

    // 表格：GFM 管道表（| 分隔 + 分隔行）
    assertThat(md).as("表格丢失 ⇒ 分块器无法整表保护（12 §3.2 规则 1）").contains("|");
    assertThat(md).contains("500 元/天");
    assertThat(md).contains("实报实销");
  }

  // ────────────────────────── 2 · PDF：至少保住正文 ──────────────────────────

  /**
   * ★ 普通 PDF（无 structure tag）的已知上限（{@code 12 §2.2}）： Tika 只出段落流，
   * <strong>不推断标题层级、不计算表格行列边界</strong>。所以本条断言写清「保住的是字面文本」：
   *
   * <ul>
   *   <li>标题文字、列表项文字、表格单元格文字 ⇒ <strong>必须还在</strong>（丢了就是解析器 bug）
   *   <li>{@code #}/{@code |}/{@code -} 等结构语法 ⇒ <strong>不断言</strong>（普通 PDF 本来就没有）
   * </ul>
   */
  @Test
  void PDF至少保住标题列表表格的字面文本() {
    ParseResult result = parser.parse(fixture("structured.pdf"), "structured.pdf");

    assertThat(result.success()).as("文本型 PDF 必须解析成功：%s", result.failReason()).isTrue();
    String md = result.markdown();

    // 标题字面
    assertThat(md).as("PDF 标题文字丢了").contains("第一章 总则");
    assertThat(md).contains("1.1 制定目的");
    // 列表项字面
    assertThat(md).contains("适用公司各部门");
    assertThat(md).contains("提交申请");
    // 表格单元格字面
    assertThat(md).as("PDF 表格单元格文字丢了").contains("500 元/天");
    assertThat(md).contains("实报实销");
  }

  // ────────────────────────── 3 · GBK TXT：中文不乱码 ──────────────────────────

  /**
   * ★ 编码检测选型见 {@code 12 §一}：用 Tika 自带 {@code CharsetDetector}，不另引编码库。 GBK 是中文企业文档的历史包袱 —— 探测成 UTF-8
   * 会得到一串替换字符，正文等于没解析。
   */
  @Test
  void GBK编码TXT中文不乱码() {
    byte[] bytes = fixture("gbk-text.txt");
    ParseResult result = parser.parse(bytes, "gbk-text.txt");

    assertThat(result.success()).as("GBK TXT 必须解析成功：%s", result.failReason()).isTrue();
    String md = result.markdown();

    assertThat(md)
        .as("GBK 被误判成其它编码 ⇒ 中文乱码（12 §一 编码检测）")
        .contains("差旅费报销规定")
        .contains("本规定适用于公司全体员工")
        .contains("住宿标准");
    // 乱码特征：不应出现大量 U+FFFD 替换字符
    long replacement = md.chars().filter(c -> c == '\uFFFD').count();
    assertThat(replacement).as("出现替换字符 ⇒ 编码探测失败").isZero();
  }

  // ────────────────────────── 4 · 加密 / 损坏：失败可读 + 部分正文仍在 ──────────────────────────

  /**
   * ★ 加密 PDF：整篇打不开 ⇒ 失败原因必须<strong>可读</strong>（「PDF 加密，无法解析」这种， 不是 {@code TikaException} 堆栈），满足
   * {@code DOC-I2}（{@code *FAILED ⇒ fail_reason 非空}）。
   */
  @Test
  void 加密PDF解析失败_原因可读() {
    ParseResult result = parser.parse(fixture("encrypted.pdf"), "encrypted.pdf");

    assertThat(result.success()).as("加密 PDF 不能算解析成功").isFalse();
    assertThat(result.failReason())
        .as("failReason 必须给人看（DOC-I2 / 08 §4.1）—— 堆栈和类名不算可读原因")
        .isNotBlank()
        .contains("加密");
    assertThat(result.markdown()).as("失败时 markdown 字段不许为 null").isNotNull();
  }

  /**
   * ★ <strong>容器级失败</strong>（ZIP 尾部截断）：POI 连正文 XML 都读不出来 ⇒ <strong>没有可保留的正文</strong>， partial
   * 允许为空。这不是缺陷而是事实 —— 「保留部分内容」只对<strong>中途失败</strong>成立（见下一条）。 这里守住的是「失败原因必须可读」（{@code DOC-I2}）。
   */
  @Test
  void 容器级失败_损坏DOCX_原因可读() {
    ParseResult result = parser.parse(fixture("truncated.docx"), "truncated.docx");

    assertThat(result.success()).as("截断 DOCX 不能算解析成功").isFalse();
    assertThat(result.failReason())
        .as("failReason 必须给人看（DOC-I2）")
        .isNotBlank()
        .doesNotContain("org.apache")
        .doesNotContain("Exception");
    assertThat(result.markdown()).as("容器级失败允许无正文，但字段不许为 null").isNotNull();
  }

  /**
   * ★ <strong>中途失败</strong>要保留已缓冲正文（{@code 12 §2.1}）。
   *
   * <p>⚠️ <strong>为什么用合成的 {@link ParseResult} 而不是真实夹具</strong>：PDFBox 对坏流/悬空引用/Crypt/JBIG2
   * 一律吞掉、仍算成功；POI 解析 DOCX 是<strong>先 DOM 读整个 {@code document.xml}</strong> —— XML
   * 一坏就整体失败、一字不吐。"吐一半再抛"的夹具在两种解析器的容错下都无法稳定构造（试过：zip 截断、document.xml 截断、坏 zlib、坏 xref）。
   *
   * <p>⇒ 这条钉住的是<strong>契约</strong>：handler 缓冲了什么，失败时就必须留下什么。{@code TikaMarkdownParser} 的 catch
   * 分支已按此实现（返回 {@code markdown.toString()}）；真实中途失败出现时（如多页 PDF
   * 的某页流损坏）走的就是那条路径。<strong>记为欠账</strong>：等能稳定产出中途失败夹具时补一条端到端断言。
   */
  @Test
  void 中途失败_保留已缓冲正文_契约钉住() {
    ParseResult mid = ParseResult.failed("第 3 页内容流损坏，无法继续解析", "# 第一章 总则\n\n前两段已经解析出来。");

    Document doc = newDocument();
    doc.recordParseFailure(mid.failReason(), "尝试修复 PDF 后重传。", mid.markdown());

    assertThat(doc.parsedContent())
        .as("handler 已缓冲的部分必须留下（12 §2.1）—— 整篇丢弃会让用户对着空文档猜内容")
        .isNotBlank()
        .contains("第一章");
    assertThat(doc.parsedContent()).as("回写的就是 handler 那一份，不多不少").isEqualTo(mid.markdown());
    assertThat(doc.status()).isEqualTo(DocumentStatus.SEGMENT_FAILED);
    assertThat(doc.failReason()).isNotBlank();
  }

  /** ★ 完全不是文档的字节垃圾：失败态 + 原因可读（不能是 NPE / 堆栈）。 */
  @Test
  void 非文档字节解析失败_原因可读() {
    ParseResult result = parser.parse(fixture("garbage.bin"), "garbage.bin");

    assertThat(result.success()).isFalse();
    assertThat(result.failReason()).isNotBlank().doesNotContain("Exception");
    assertThat(result.failReason().length()).as("原因要能读，不能是空串").isGreaterThan(4);
  }

  /**
   * ★ **0 字符 ≠ 成功**：输入非空却抽出 0 字符时若仍算成功，界面会显示「已解析」而内容是空的 —— 这是最坏的失败形态（静默）。原因要指路（扫描件走 OCR / 空文档 /
   * 文件损坏），与第 9 周 OCR 链衔接。
   */
  @Test
  void 解析出零字符不算成功_原因指向扫描件或空文档() {
    ParseResult result = parser.parse(fixture("empty.docx"), "empty.docx");

    assertThat(result.success()).as("抽出 0 字符不能算解析成功").isFalse();
    assertThat(result.failReason())
        .as("原因要指路，不能只说「解析失败」")
        .isNotBlank()
        .contains("为空")
        .contains("OCR");
  }

  // ────────────────────────── 5 · 状态推进 + DOC-I2 ──────────────────────────

  /**
   * ★ 解析成功 ⇒ 状态走到「待向量化」（{@code PENDING_EMBEDDING}）； 失败 ⇒ 「分段失败」（{@code SEGMENT_FAILED}）+ {@code
   * failReason} 非空。 取值用 {@code SCREAMING_SNAKE_CASE}（{@code 14 §2.1}）。
   */
  @Test
  void 解析结果回写Document_成功到待向量化_失败到分段失败且原因非空() {
    // 成功
    Document ok = newDocument();
    ParseResult good = parser.parse(fixture("structured.docx"), "structured.docx");
    ok.recordParseSuccess(good.markdown());
    assertThat(ok.status()).isEqualTo(DocumentStatus.PENDING_EMBEDDING);
    assertThat(ok.failReason()).as("成功路径要清掉上次失败原因").isNull();
    assertThat(ok.charCount()).as("charCount 回填解析后正文长度").isGreaterThan(0);
    assertThat(ok.parsedContent()).isNotBlank();

    // 失败
    Document bad = newDocument();
    ParseResult encrypted = parser.parse(fixture("encrypted.pdf"), "encrypted.pdf");
    bad.recordParseFailure(encrypted.failReason(), "去掉 PDF 密码后重新上传。", encrypted.markdown());
    assertThat(bad.status()).isEqualTo(DocumentStatus.SEGMENT_FAILED);
    assertThat(bad.failReason()).as("DOC-I2：*FAILED ⇒ fail_reason 非空").isNotBlank().contains("加密");
    assertThat(bad.failHint()).contains("密码");
  }
}
