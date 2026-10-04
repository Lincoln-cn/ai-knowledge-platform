package cn.lincoln.aiplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 分块器（第 7 周 · 0.2.2）—— 四层优先级（{@code 12 §3.2}）。
 *
 * <p>★ Spring AI 的 {@code TokenTextSplitter} 用不了（{@code 12 §3.1} 核实过源码）： 单位是 token 而非字符、无
 * overlap、无结构感知、无长度上限拦截 ⇒ 自研。
 *
 * <p>四层：
 *
 * <pre>
 * L1 结构层：按 Markdown 标题切「章节块」，记 titlePath（H1 &gt; H2 &gt; H3）
 * L2 单元层：章节内按语义单元切 —— 第X条/（一）/1.1 不跨切；段落/列表/代码围栏/表格整块保留
 * L3 长度层：单元仍超长 ⇒ 按句边界切（。！？；……）
 * L4 兜底层：仍超长 ⇒ 硬切 + oversizedRisk（契约：保证不超长，否则 SEG-I1 拒绝整篇）
 * </pre>
 *
 * <p>★ 纯领域实现，不依赖 Spring AI：{@code 12 §3.1} 建议"继承官方 {@code TextSplitter}"是为了拿 {@code
 * DocumentTransformer} 契约与 metadata 继承 —— 那是<strong>链路装配</strong>的事，由编排层适配器委托本类完成， 领域层不为此引入框架依赖。
 *
 * <p>★ 两条不出现在 {@code 12 §3.2} 文字里、但被测试钉住的纪律：
 *
 * <ol>
 *   <li><strong>标题行不单独成块</strong> —— 标题只带层级信息、没有语义，切成 6 个字的块进向量就是噪声； 它必须和下面第一个单元同行（章节链里的父级标题也一起带上）
 *   <li><strong>表格与代码围栏是独占块</strong>（{@code 12 §3.2} 硬规则 1 / 2 的推论）—— 不与相邻正文合并， 否则"表头不在同一块"的那一半失去意义
 * </ol>
 *
 * <p>★ 重叠（{@code 12 §3.3}）在装箱<strong>之后</strong>按后处理补前缀：先按 {@code segmentLength - overlap}
 * 装箱，再从同章节前块尾部取句/行对齐的重叠区贴到后块开头。表/代码独占块不参与。
 */
public class MarkdownChunker {

  /** {@code 12 §3.4}：无标题块的 title = 首个句子截取 ≤256 字。 */
  private static final int TITLE_MAX = 256;

  /** 句末标点（L3 切分锚点）。{@code …} 是单个字符 U+2026。 */
  private static final String SENTENCE_END = "。！？；!?;…";

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

  /** 句边界切分：在句末标点<strong>之后</strong>断开，标点跟着前一句（保留原文标点）。 */
  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；!?;…])");

  /** GFM 表格分隔行：{@code | --- | :---: |}。 */
  private static final Pattern TABLE_SEPARATOR =
      Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

  /**
   * ★ L2 中文契约的「法律效力单元」锚点（{@code 12 §3.2}）：{@code 第X章 / 第X条 / （一）/ 1.1} ——
   * 边界必须<strong>优先</strong>落在这里，而不是落进一条法条的中间。
   *
   * <p>没有它会发生什么：一个段落里连着两条法条时，L3 按句装箱会把「第一条的尾巴 + 第二条的前半」拼成一块 ——
   * 不报错、不告警，但召回出来的上下文残缺、引用时用户看到半句话（正是分块器最贵的静默故障）。
   */
  private static final Pattern LEGAL_UNIT_ANCHOR =
      Pattern.compile(
          "第[〇零一二三四五六七八九十百千0-9]{1,12}[章节条款项]"
              + "|[（(][〇零一二三四五六七八九十百千0-9]{1,12}[）)]"
              + "|[0-9]{1,3}(?:\\.[0-9]{1,3})+");

  /** L2 通用单元锚点：列表项（{@code -} / {@code 1.} / {@code 1、}）。 */
  private static final Pattern LIST_ITEM_ANCHOR =
      Pattern.compile("([-*+]|[0-9]{1,3}[.)、）])[ \\u3000]");

  /**
   * 按四层优先级切分（不重叠）。
   *
   * @param markdown 解析产物 Markdown（{@code Document.parsedContent}）
   * @param segmentLength 分块目标长度（字符）
   * @return 有序 chunk 列表；空输入返回空列表
   */
  public List<SegmentChunk> chunk(String markdown, int segmentLength) {
    return chunk(markdown, segmentLength, 0);
  }

  /**
   * 按四层优先级切分，并在同章节相邻块之间加重叠（{@code 12 §3.3}）。
   *
   * <p>★ 重叠的四条硬规则（{@code 12 §3.3}，缺一条就会产生静默故障）：
   *
   * <ol>
   *   <li><strong>只在同一章节内</strong>（判据 {@code titlePath} 相同）—— 跨章节重叠会把上一章尾巴混进下一章
   *   <li>重叠区从<strong>前块尾部</strong>取，起点落在句/行边界；取不到就不重叠（宁可少重叠）
   *   <li>重叠区<strong>不算独立内容</strong>：贴到后块 {@code content} 开头，不额外产生 chunk
   *   <li>任何块（含重叠后）不得超过 {@code segmentLength} —— 所以先按 {@code segmentLength - overlap}
   *       装箱再补前缀；补完仍超限则该块不加重叠（契约见 {@code 12 §3.2} 硬规则 3）
   * </ol>
   *
   * @param markdown 解析产物 Markdown（{@code Document.parsedContent}）
   * @param segmentLength 分块目标长度（字符，<strong>含</strong>重叠前缀）
   * @param overlap 重叠字符数；必须 {@code 0 ≤ overlap < segmentLength}
   * @return 有序 chunk 列表；空输入返回空列表
   * @throws IllegalArgumentException 参数非法（禁止静默纠正）
   */
  public List<SegmentChunk> chunk(String markdown, int segmentLength, int overlap) {
    return chunk(markdown, segmentLength, overlap, ChunkSource.TEXT);
  }

  /**
   * 按四层优先级切分，并按来源选规则集（第 9 周 · 0.3.1）。
   *
   * <p>★ {@code source = OCR} 时走 {@code 12 §11.5} 定的另一套：L2（法条锚点）为主、 相邻行合并修标题（L1'）、**跳过 L3 句边界直接 L4
   * 硬切**。理由见 {@link ChunkSource}。
   *
   * @param markdown 解析产物 Markdown（{@code Document.parsedContent}）
   * @param segmentLength 分块目标长度（字符，<strong>含</strong>重叠前缀）
   * @param overlap 重叠字符数；必须 {@code 0 ≤ overlap < segmentLength}
   * @param source 规则集来源（文本型 / OCR 型）
   */
  public List<SegmentChunk> chunk(
      String markdown, int segmentLength, int overlap, ChunkSource source) {
    if (segmentLength <= 0) {
      throw new IllegalArgumentException("分块长度必须为正数，当前：" + segmentLength);
    }
    if (overlap < 0 || overlap >= segmentLength) {
      throw new IllegalArgumentException(
          "重叠必须满足 0 ≤ overlap < segmentLength，当前 overlap="
              + overlap
              + "、segmentLength="
              + segmentLength);
    }
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    ChunkSource effective = source == null ? ChunkSource.TEXT : source;
    // 有效目标长度：为 overlap 预留 —— 先按 segmentLength - overlap 装箱，再补重叠前缀
    int packLimit = segmentLength - overlap;
    List<SegmentChunk> chunks =
        assemble(parse(markdown.replace("\r\n", "\n").replace('\r', '\n')), packLimit, effective);
    return applyOverlap(chunks, overlap, segmentLength);
  }

  // ────────────────────────── L1 + L2：Markdown ⇒ 语义单元序列 ──────────────────────────

  /** 语义单元类型。 */
  private enum Kind {
    HEADING,
    TEXT,
    TABLE,
    CODE
  }

  /**
   * 语义单元。
   *
   * @param kind 类型
   * @param level 标题层级（仅 HEADING 有意义）
   * @param text 原始行文本（HEADING 含 {@code #} 前缀）
   * @param heading 标题正文（去掉 {@code #} 与首尾空白）
   */
  private record Unit(Kind kind, int level, String text, String heading) {

    static Unit heading(int level, String raw, String heading) {
      return new Unit(Kind.HEADING, level, raw, heading);
    }

    static Unit of(Kind kind, String text) {
      return new Unit(kind, 0, text, null);
    }
  }

  private List<Unit> parse(String markdown) {
    String[] lines = markdown.split("\n", -1);
    List<Unit> units = new ArrayList<>();
    int i = 0;
    while (i < lines.length) {
      String line = lines[i];
      if (line.isBlank()) {
        i++;
        continue;
      }
      if (isFence(line)) {
        i = readFence(lines, i, units);
        continue;
      }
      Matcher heading = HEADING.matcher(line);
      if (heading.matches()) {
        units.add(
            Unit.heading(
                heading.group(1).length(), line.stripTrailing(), heading.group(2).strip()));
        i++;
        continue;
      }
      if (isTableRow(line)) {
        i = readTable(lines, i, units);
        continue;
      }
      i = readParagraph(lines, i, units);
    }
    return units;
  }

  /** 代码围栏：整块保留（{@code 12 §3.2} 硬规则 2）。未闭合的围栏吃到文末 —— 宁可整块留下，也不要切开半段配置。 */
  private int readFence(String[] lines, int start, List<Unit> units) {
    List<String> block = new ArrayList<>();
    block.add(lines[start]);
    int i = start + 1;
    while (i < lines.length) {
      block.add(lines[i]);
      boolean closed = isFence(lines[i]);
      i++;
      if (closed) {
        break;
      }
    }
    units.add(Unit.of(Kind.CODE, String.join("\n", block)));
    return i;
  }

  /** 表格：整表一块（{@code 12 §3.2} 硬规则 1）；超长在 L4 按行分组并重复表头（{@code 12 §12.5}）。 */
  private int readTable(String[] lines, int start, List<Unit> units) {
    List<String> block = new ArrayList<>();
    int i = start;
    while (i < lines.length && isTableRow(lines[i])) {
      block.add(lines[i].stripTrailing());
      i++;
    }
    units.add(Unit.of(Kind.TABLE, String.join("\n", block)));
    return i;
  }

  /**
   * 空行段落（L2）—— 段内<strong>再按语义单元锚点断开</strong>：法条（第X条/（一）/1.1）与列表项各自成单元。
   *
   * <p>★ 单元只影响「边界优先落在哪」（{@code 12 §3.2} 的 L2）：单元本身短的话，下面的装箱仍会把它们拼进同一块 —— 「不跨条切」约束的是边界位置，不是块数。
   */
  private int readParagraph(String[] lines, int start, List<Unit> units) {
    List<String> block = new ArrayList<>();
    int i = start;
    while (i < lines.length && !lines[i].isBlank() && !isUnitBoundary(lines[i])) {
      if (!block.isEmpty() && isUnitAnchor(lines[i])) {
        units.add(Unit.of(Kind.TEXT, String.join("\n", block)));
        block.clear();
      }
      block.add(lines[i].stripTrailing());
      i++;
    }
    if (!block.isEmpty()) {
      units.add(Unit.of(Kind.TEXT, String.join("\n", block)));
    }
    return i;
  }

  private static boolean isUnitAnchor(String line) {
    String stripped = line.stripLeading();
    return LEGAL_UNIT_ANCHOR.matcher(stripped).lookingAt()
        || LIST_ITEM_ANCHOR.matcher(stripped).lookingAt();
  }

  private boolean isUnitBoundary(String line) {
    return isFence(line) || isTableRow(line) || HEADING.matcher(line).matches();
  }

  private static boolean isFence(String line) {
    String t = line.stripLeading();
    return t.startsWith("```") || t.startsWith("~~~");
  }

  private static boolean isTableRow(String line) {
    return line.stripLeading().startsWith("|");
  }

  private static boolean isSeparatorRow(String line) {
    return TABLE_SEPARATOR.matcher(line.strip()).matches();
  }

  // ────────────────────────── L1 章节 + L2/L3/L4 装箱 ──────────────────────────

  /** 章节标题帧（H1 &gt; H2 &gt; H3）。 */
  private record Frame(int level, String title) {}

  /**
   * 装箱草稿：内容与所属章节已定，标题留到最后编号。
   *
   * @param seq 本章节内的序号（从 0 起；标题编号用它，见 {@code 12 §3.4}）
   */
  private record Draft(
      String text, String base, String titlePath, Kind kind, boolean oversized, int seq) {}

  /** 切分产物（L3/L4 的中间形态）。 */
  private record Split(String text, boolean oversized) {}

  private List<SegmentChunk> assemble(List<Unit> units, int limit, ChunkSource source) {
    List<Draft> drafts = new ArrayList<>();
    List<Frame> stack = new ArrayList<>();
    StringBuilder buffer = new StringBuilder();
    String base = "";
    String titlePath = "";
    int seq = 0;
    // 缓冲里是否"只有标题行"：只有标题时不能冲成独立块，要等后面的内容一起走
    boolean headingOnly = false;

    for (Unit unit : units) {
      if (unit.kind() == Kind.HEADING) {
        if (!headingOnly) {
          seq = flush(drafts, buffer, base, titlePath, seq);
        }
        while (!stack.isEmpty() && stack.getLast().level() >= unit.level()) {
          stack.removeLast();
        }
        stack.add(new Frame(unit.level(), unit.heading()));
        base = unit.heading();
        titlePath = String.join(" > ", stack.stream().map(Frame::title).toList());
        seq = 0;
        appendLine(buffer, unit.text());
        headingOnly = true;
        continue;
      }

      if (unit.kind() == Kind.CODE || unit.kind() == Kind.TABLE) {
        // 独占块：不吞相邻正文，也不被相邻正文吞
        String caption = headingOnly ? buffer.toString() : null;
        if (caption == null) {
          seq = flush(drafts, buffer, base, titlePath, seq);
        } else {
          buffer.setLength(0);
        }
        headingOnly = false;
        for (Split split : splitExclusive(unit.text(), limit, unit.kind(), caption)) {
          drafts.add(new Draft(split.text(), base, titlePath, unit.kind(), split.oversized(), seq));
          seq++;
        }
        continue;
      }

      // L2 段落/列表：能装就装，装不下才切
      String text = unit.text();
      if (buffer.length() + (buffer.isEmpty() ? 0 : 1) + text.length() <= limit) {
        appendLine(buffer, text);
        headingOnly = false;
        continue;
      }
      if (headingOnly) {
        // 标题与正文一起切：首块带上章节标识，避免"几个字的标题块"
        text = buffer + "\n" + text;
        buffer.setLength(0);
      } else {
        seq = flush(drafts, buffer, base, titlePath, seq);
      }
      headingOnly = false;
      if (text.length() <= limit) {
        buffer.append(text);
      } else {
        seq = emit(drafts, text, base, titlePath, Kind.TEXT, seq, limit, source);
      }
    }

    flush(drafts, buffer, base, titlePath, seq);

    List<SegmentChunk> chunks = new ArrayList<>(drafts.size());
    for (int idx = 0; idx < drafts.size(); idx++) {
      Draft d = drafts.get(idx);
      chunks.add(
          new SegmentChunk(
              idx,
              d.text(),
              titleOf(d),
              d.titlePath(),
              d.kind() == Kind.TABLE,
              d.kind() == Kind.CODE,
              d.oversized()));
    }
    return chunks;
  }

  private static void appendLine(StringBuilder buffer, String text) {
    if (!buffer.isEmpty()) {
      buffer.append('\n');
    }
    buffer.append(text);
  }

  /** 冲掉缓冲；返回新的章节内序号。 */
  private int flush(
      List<Draft> drafts, StringBuilder buffer, String base, String titlePath, int seq) {
    if (buffer.isEmpty()) {
      return seq;
    }
    drafts.add(new Draft(buffer.toString(), base, titlePath, Kind.TEXT, false, seq));
    buffer.setLength(0);
    return seq + 1;
  }

  /** 把一段超长文本按 L3/L4 切开并落成草稿（{@code source} 决定用哪套切分规则，见 {@link ChunkSource}）。 */
  private int emit(
      List<Draft> drafts,
      String text,
      String base,
      String titlePath,
      Kind kind,
      int seq,
      int limit,
      ChunkSource source) {
    int next = seq;
    for (Split split : splitLong(text, limit, source)) {
      drafts.add(new Draft(split.text(), base, titlePath, kind, split.oversized(), next));
      next++;
    }
    return next;
  }

  // ────────────────────────── 重叠（12 §3.3 · 批 2a） ──────────────────────────

  /**
   * 同章节相邻块补重叠前缀（{@code 12 §3.3}）。 不产生额外 chunk，也不改 ordinal/title。
   *
   * <p>★ 长度契约：装箱时已按 {@code packLimit = segmentLength - overlap} 预留， 故「前缀 ≤ overlap + 正文 ≤
   * packLimit」⇒ 拼接后 ≤ {@code segmentLength}。 仍超限的极端情况直接放弃该块的重叠（宁可少重叠，不可超长 —— 超长会被 {@code SEG-I1}
   * 拒整篇）。
   */
  private List<SegmentChunk> applyOverlap(
      List<SegmentChunk> chunks, int overlap, int segmentLength) {
    if (overlap == 0 || chunks.size() < 2) {
      return chunks;
    }
    List<SegmentChunk> out = new ArrayList<>(chunks.size());
    out.add(chunks.getFirst());
    for (int i = 1; i < chunks.size(); i++) {
      SegmentChunk prev = chunks.get(i - 1);
      SegmentChunk curr = chunks.get(i);
      out.add(overlapInto(prev, curr, overlap, segmentLength));
    }
    return out;
  }

  /** 规则 1/4/5：同章节、非表/代码、拼完不超长 —— 任一不满足就原样返回。 */
  private SegmentChunk overlapInto(
      SegmentChunk prev, SegmentChunk curr, int overlap, int segmentLength) {
    if (prev.isTable()
        || prev.isCode()
        || curr.isTable()
        || curr.isCode()
        || !prev.titlePath().equals(curr.titlePath())) {
      return curr;
    }
    String tail = extractOverlapTail(prev.content(), overlap);
    if (tail.isEmpty()) {
      return curr;
    }
    String joined = tail + curr.content();
    if (joined.length() > segmentLength) {
      return curr;
    }
    return new SegmentChunk(
        curr.ordinal(),
        joined,
        curr.title(),
        curr.titlePath(),
        curr.isTable(),
        curr.isCode(),
        curr.oversizedRisk());
  }

  /**
   * 从 {@code prev} 尾部取重叠区（{@code 12 §3.3}）：长度 ≤ overlap，起点必须落在句/行边界。
   *
   * <p>★ 取不到边界就返回空 —— <strong>宁可少重叠，也不要从半个字开始</strong>。 句边界看句末标点之后，行边界看 {@code \n}
   * 之后；起点取「窗口内最早的合法边界」⇒ 在对齐前提下尽量吃满 overlap。
   */
  private static String extractOverlapTail(String prev, int overlap) {
    if (prev.isEmpty() || overlap <= 0) {
      return "";
    }
    int minStart = Math.max(0, prev.length() - overlap);
    for (int start = minStart; start < prev.length(); start++) {
      if (isBoundaryStart(prev, start)) {
        return prev.substring(start);
      }
    }
    return "";
  }

  /** 位置 {@code index} 是否可作「句/行起点」：文首，或前一字符是句末标点 / 换行。 */
  private static boolean isBoundaryStart(String text, int index) {
    if (index <= 0) {
      return true;
    }
    char before = text.charAt(index - 1);
    return before == '\n' || SENTENCE_END.indexOf(before) >= 0;
  }

  // ────────────────────────── L3 / L4 ──────────────────────────

  /** L3 句边界 → L4 硬切（文本型，句边界是可靠信号）。 */
  private List<Split> splitLong(String text, int limit) {
    return splitLong(text, limit, ChunkSource.TEXT);
  }

  /**
   * L3 句边界 → L4 硬切，**按来源分两套规则**（{@code 12 §11.5}）。
   *
   * <p>★★ OCR 型<strong>跳过 L3</strong>：OCR 文本的行尾经常没有标点、还会在词中间断行，
   * 按"句"装箱会切出大量半句块。所以按<strong>行</strong>装箱、超长行直接硬切 —— 在 OCR 文档上 L4 不再是兜底而是主力（这是 {@code 12 §11.5}
   * 的实测结论，不是保守选择）。
   *
   * <p>★ 文本型保持原样：句边界在文本型文档上可靠得多，用它比硬切强。
   *
   * <p>⚠️ {@code 12 §11.5} 还给 OCR 列了「相邻行合并（正则 + 字号）」修被劈开的标题。 这一条**本版没做**，理由是**实测**：夹具（{@code
   * scanned.pdf}，答案见 {@code fixtures/ocr-ground-truth.json}）走 TXT 路径出来的文本里，标题 （{@code 第一章 总则} /
   * {@code 1.1 制定目的}）**本来就是完整的一行**，段落也按行分好了 —— 合并规则在这个数据上修不了任何东西，却能把"没写句末标点的一行"和下一行粘起来（正文被改坏的风险）。
   * 字号启发在 TXT 路径上也不可用（Tesseract 的字号在 HOCR 里，而我们选了 TXT 保中文质量，见 {@code §11.4.1}）。 ⇒
   * 等**真的遇到**被劈开的标题再接（届时有可复现的样本，也才验得了）。
   */
  private List<Split> splitLong(String text, int limit, ChunkSource source) {
    if (source == ChunkSource.OCR) {
      List<Split> out = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      for (String raw : text.split("\n", -1)) {
        String line = raw.strip();
        if (line.isEmpty()) {
          continue;
        }
        if (current.isEmpty()) {
          current.append(line);
        } else if (current.length() + 1 + line.length() <= limit) {
          current.append('\n').append(line);
        } else {
          addWithHardCut(out, current.toString(), limit);
          current.setLength(0);
          current.append(line);
        }
      }
      if (!current.isEmpty()) {
        addWithHardCut(out, current.toString(), limit);
      }
      return out;
    }
    List<Split> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String sentence : SENTENCE_SPLIT.split(text)) {
      if (sentence.isBlank()) {
        continue;
      }
      if (current.isEmpty()) {
        current.append(sentence.stripLeading());
      } else if (current.length() + sentence.length() <= limit) {
        current.append(sentence);
      } else {
        addWithHardCut(out, current.toString(), limit);
        current.setLength(0);
        current.append(sentence.stripLeading());
      }
    }
    if (!current.isEmpty()) {
      addWithHardCut(out, current.toString(), limit);
    }
    return out;
  }

  /**
   * 表格 / 代码围栏的 L4 路径。
   *
   * <p>★ 表格<strong>按行分组并重复表头与表名</strong>（{@code 12 §3.2} 硬规则 1 + {@code §12.5}，Docling 的 {@code
   * repeat_table_header} 同款）—— 被切开的每一组都要自己带列名，否则一半表失去意义。
   *
   * @param caption 紧邻其上的标题行（表名，{@code 12 §12.4}：进 {@code content} 首行）；无则 null
   */
  private List<Split> splitExclusive(String text, int limit, Kind kind, String caption) {
    boolean hasCaption = caption != null && !caption.isEmpty();
    String head = hasCaption ? caption + "\n" : "";
    if (head.length() + text.length() <= limit) {
      return List.of(new Split(head + text, false));
    }
    List<String> lines = List.of(text.split("\n", -1));
    if (kind == Kind.TABLE && lines.size() > 2 && isSeparatorRow(lines.get(1))) {
      String prefix = head + lines.get(0) + "\n" + lines.get(1);
      List<String> rows = lines.subList(2, lines.size());
      boolean groupable =
          prefix.length() <= limit
              && rows.stream().allMatch(row -> prefix.length() + 1 + row.length() <= limit);
      if (groupable) {
        List<Split> out = new ArrayList<>();
        StringBuilder group = new StringBuilder(prefix);
        for (String row : rows) {
          if (group.length() > prefix.length() && group.length() + 1 + row.length() > limit) {
            out.add(new Split(group.toString(), false));
            group.setLength(0);
            group.append(prefix);
          }
          group.append('\n').append(row);
        }
        out.add(new Split(group.toString(), false));
        return out;
      }
    }
    // 退化路径：表名/表头重复不下时按行分组，单行自己也超限才硬切（超宽表 / 无标点长行）
    List<String> all = new ArrayList<>();
    if (hasCaption) {
      all.addAll(caption.lines().toList());
    }
    all.addAll(lines);
    return lineGroups(all, limit);
  }

  private List<Split> lineGroups(List<String> lines, int limit) {
    List<Split> out = new ArrayList<>();
    StringBuilder buffer = new StringBuilder();
    for (String line : lines) {
      if (buffer.isEmpty()) {
        if (line.length() <= limit) {
          buffer.append(line);
        } else {
          addWithHardCut(out, line, limit);
        }
        continue;
      }
      if (buffer.length() + 1 + line.length() <= limit) {
        buffer.append('\n').append(line);
        continue;
      }
      out.add(new Split(buffer.toString(), false));
      buffer.setLength(0);
      if (line.length() <= limit) {
        buffer.append(line);
      } else {
        addWithHardCut(out, line, limit);
      }
    }
    if (!buffer.isEmpty()) {
      out.add(new Split(buffer.toString(), false));
    }
    return out;
  }

  /** L4 硬切 —— 唯一会打 {@code oversizedRisk} 的地方（{@code 12 §3.2} 规则 4）。 */
  private void addWithHardCut(List<Split> out, String piece, int limit) {
    if (piece.length() <= limit) {
      out.add(new Split(piece, false));
      return;
    }
    for (int i = 0; i < piece.length(); i += limit) {
      out.add(new Split(piece.substring(i, Math.min(piece.length(), i + limit)), true));
    }
  }

  // ────────────────────────── 分段标题（12 §3.4） ──────────────────────────

  /**
   * 分段标题取值（{@code 12 §3.4}）：
   *
   * <ul>
   *   <li>有标题：本章节最后一级标题；同章节后续块加序号（"第三章 差旅费（2）"）
   *   <li>无标题（PDF 段落流）：首个句子截取 ≤256 字
   *   <li>表格块：最近标题 / 表头首行 + "（表格）"
   * </ul>
   */
  private String titleOf(Draft d) {
    if (d.base().isEmpty()) {
      if (d.kind() == Kind.TABLE) {
        return clamp(firstLine(d.text())) + "（表格）";
      }
      if (d.kind() == Kind.CODE) {
        return clamp(firstLine(d.text()));
      }
      return clamp(firstSentence(d.text()));
    }
    if (d.kind() == Kind.TABLE) {
      return d.base() + "（表格）";
    }
    return d.seq() == 0 ? d.base() : d.base() + "（" + (d.seq() + 1) + "）";
  }

  private static String firstLine(String text) {
    int nl = text.indexOf('\n');
    return (nl < 0 ? text : text.substring(0, nl)).strip();
  }

  /**
   * 无标题块的标题（{@code 12 §3.4}）：取首个句子，句末标点<strong>或行尾</strong>为止，截 ≤256 字。
   *
   * <p>★ 行尾也算断点：PDF / OCR 的段落流里没有句号（实测结构化 PDF 的一段通篇无 {@code 。}）， 只看标点会把整段正文变成标题。
   */
  private static String firstSentence(String text) {
    int end = text.length();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || SENTENCE_END.indexOf(c) >= 0) {
        end = i + 1;
        break;
      }
    }
    return text.substring(0, end).strip();
  }

  private static String clamp(String title) {
    return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX);
  }
}
