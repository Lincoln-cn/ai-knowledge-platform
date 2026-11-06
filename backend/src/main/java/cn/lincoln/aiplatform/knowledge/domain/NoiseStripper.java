package cn.lincoln.aiplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 噪声剥离（第 9 周 · 0.3.1，{@code 12 §2.4}）—— 解析之后、分块之前跑一遍，把页眉页脚/页码/目录/**PDF 占位标记**剔掉。
 *
 * <h3>★ 为什么必须有这一环</h3>
 *
 * <p>页眉页脚会**污染分块与向量**：同一行页眉在 50 个 chunk 里重复 ⇒ 它们互相"很像" ⇒ 召回被稀释。 而扫描件上这件事更严重：{@code 12 §11.6} 指出
 * Tesseract <strong>默认不产出</strong> {@code ocr_header} / {@code ocr_footer} / {@code
 * ocr_pageno}，所以页眉页脚**必须我们自己认**。 好消息是规则在扫描件上天然更容易达标：每页都有 ⇒ 重复次数够。
 *
 * <h3>★ 纪律：规则要保守 + 剥离数量要可观测</h3>
 *
 * <p>{@code 12 §10.2} 风险 2 写的就是这个：误杀正文 = **内容静默缺失**。所以
 *
 * <ul>
 *   <li>三条规则都要求"成片/高频"特征，不做单行启发式
 *   <li>{@code removedNoiseLines} <strong>必须记数</strong>，并在切分预览里可见 —— 否则"为什么这份文档少了一页内容"没人能解释（与
 *       {@code 03 §3.5} 的失败样本留存同一性质）
 *   <li>代码围栏内的行<strong>不参与</strong>（配置片段里重复的行是内容，不是页眉）
 * </ul>
 */
public final class NoiseStripper {

  /** 跨页重复行判定的最小重复次数（{@code 12 §2.4}：≥3 处）。 */
  public static final int REPEAT_MIN_OCCURRENCES = 3;

  /** 跨页重复行判定的最大长度（{@code 12 §2.4}：< 60 字）。超过就是正文段落被误判。 */
  public static final int REPEAT_MAX_LENGTH = 60;

  /** 目录页判定的最短连续行数（{@code 12 §2.4}：≥5 行）。 */
  public static final int TOC_MIN_RUN = 5;

  /**
   * 版权声明行的最大长度（{@code #271}）。
   *
   * <p>★ 这是一道<b>防误杀</b>的护栏，不是排版要求：真版权行都很短（实测最长 24 字）。长段落里出现 「©」或「版权所有」多半是正文在讨论版权，整段剥掉就是内容静默缺失。
   */
  public static final int COPYRIGHT_MAX_LENGTH = 120;

  /** 独立成行的页码：{@code 12} / {@code - 12 -} / {@code 第 3 页} / {@code 第 3 页 共 10 页} / 罗马数字。 */
  private static final Pattern PAGE_NUMBER =
      Pattern.compile(
          "^(?:[-–—\\s]*\\d{1,4}[-–—\\s]*|第\\s*\\d{1,4}\\s*页(?:\\s*[/／]?\\s*共\\s*\\d{1,4}\\s*页)?|[ivxlcdmIVXLCDM]{1,7})$");

  /**
   * 目录行的特征：点线/省略号引导 + 结尾页码。
   *
   * <p>★★ 点线与页码之间**允许夹杂标点**：实测真实 Tesseract 输出会把那一处识别成逗号 —— {@code 第一章 总则 ..……….….， 1}（点线后是「，」再是页码）。
   * 第一版正则要求"点线紧跟数字"，于是**理想文本上通过、真实输出上一条都没剔掉**。 放宽这一段的代价很小：真正的护栏是下面的「连续 ≥5 行」。
   */
  private static final Pattern TOC_LINE =
      Pattern.compile(".*?[.．…·]{2,}[\\s.．，,、;；:：]*\\d{1,4}\\s*$");

  /**
   * 解析器留下的**图片占位标记**（`#51`）：整行只有 `[PDF 1]` / `[pdf 12]` / `[图片 3]` / `[image 4]` 这种 方括号 + 格式名 + 序号。
   *
   * <p>★★ 取证结论（2026-11-01，不是推断）：**我们这条链不产生它** —— 4 份夹具与一份自造的图文混排 PDF （PDFBox 画了图）解析后 {@code [PDF
   * N]} 命中数都是 <b>0</b>，Tika 对嵌入式图片是「直接丢弃、不留标记」。 因此它只可能来自 <b>源 PDF 的文本层</b>（生成该 PDF 的工具把图写成了这行占位文字）。
   *
   * <p>★ 为什么还是要剥：它一旦进 {@code search_text} 就同时污染向量与全文索引，而且**不会让任何闸门变红** （{@code 12 §10.2} 风险 2
   * 的反面：内容里多了一点垃圾，然后没人发现）。
   *
   * <p>★ 受控形态名单（{@code #51} 判据 1：「{@code [PDF N]} / {@code [图片 N]} 这类标记」）： {@code PDF} · {@code 图片}
   * · {@code image} · {@code img}（后三者大小写不敏感）。 ★ 名单是**受控枚举、不是"像占位就删"**：{@code [图 2]} / {@code [表 2]}
   * 是图注表格的 <b>简写</b>（实测文档里有），不是工具占位 —— 它们<b>不进名单</b>。 宁可漏剥一个没见过的工具变体，不许误删一行真图注。
   *
   * <p>★ 边界（宁可漏剥，不许误杀）：
   *
   * <ul>
   *   <li><b>只认整行</b>：{@code [PDF 1] 图注} 这种行内还有内容的**不动** —— 那多半是真图注；
   *   <li><b>只认名单里的格式名 + 数字</b>：{@code 图 1} / {@code 表 1} / {@code Table 1} / {@code [图 2]}
   *       这类图注表格标题**不动**；
   *   <li><b>两种写法都认</b>：Tika 的 Markdown handler 会把方括号转义 —— 源文本 {@code [PDF 1]} 解析产物里是 {@code \[PDF
   *       1\]}（实测）。只认裸形态的话，这条规则在生产上永远不生效。
   *   <li>代码围栏内不动（与其余规则同口径）。
   * </ul>
   */
  private static final Pattern PARSER_PLACEHOLDER =
      Pattern.compile(
          "^\\\\?\\[\\s*(?:PDF|图片|image|img)\\s*\\d{1,3}\\s*\\\\?\\]$", Pattern.CASE_INSENSITIVE);

  /**
   * 版权声明行（`#271`）：整行、行首、短行。
   *
   * <p>★★ 判据（2026-11-01 用户拍板）：<b>算噪声，只在行首剥离</b>。
   *
   * <p>为什么要有它：版权行是"每份文档都有、可能每页都重复"的排版噪声，进了 {@code search_text}
   * 既污染向量也污染引用文本；而它<b>不会让任何闸门变红</b>（{@code 12 §10.2} 风险 2 的反面）。 此前生产实现没有这条规则，于是实测表上 {@code
   * noisy.docx} 恒为 11/12 —— 差的那一条就是它。
   *
   * <p>为什么<b>只认行首</b>：正文里合法出现"版权所有"的句子（例如「本文件版权所有，未经许可不得复制」）
   * 行首不是版权标记，剥掉就是<b>删信息</b>；那比留下脏一行严重得多。三种行首形态是实测见过的：
   *
   * <ul>
   *   <li>{@code © 2024 某某集团有限公司 版权所有}
   *   <li>{@code Copyright (c) 2024 …}
   *   <li>{@code 2024 某某集团有限公司 版权所有}（OCR 丢了 © 符号，只剩年份 + 结尾）
   * </ul>
   */
  private static final Pattern COPYRIGHT_LINE =
      Pattern.compile(
          "^\\s*(?:©|\\(c\\)|（c）|copyright\\b).*$"
              + "|^\\s*版权所有.*$"
              + "|^\\s*(?:19|20)\\d{2}\\s+\\S.*版权所有\\s*$",
          Pattern.CASE_INSENSITIVE);

  private static final String FENCE = "```";

  private NoiseStripper() {}

  /**
   * 剥离结果。
   *
   * @param markdown 剥离后的 Markdown（原文的顺序与其余内容不变）
   * @param removedLines 剥掉了多少行（**非空行**计数；0 表示这份文档没有噪声）
   * @param samples 被剥掉的样例行（最多几条）—— 给"切分预览"显示，让删掉的东西**看得见**
   */
  public record Result(String markdown, int removedLines, List<String> samples) {

    static Result untouched(String markdown) {
      return new Result(markdown, 0, List.of());
    }
  }

  /**
   * 剥掉页眉/页脚/页码/目录行（默认口径 {@code 12 §2.4}）。
   *
   * @param markdown 解析产物（{@code Document.parsedContent}）；null / 空 ⇒ 原样返回
   */
  public static Result strip(String markdown) {
    return strip(markdown, false);
  }

  /**
   * 剥离入口（带 {@code #177} 设计剥离开关）。
   *
   * <p><strong>口径（#177 取证结论，2026-11-06）</strong>：目录行/页脚/页首重复标题/页码/版权行的"整段丢失"是本类 ②②b③④
   * 规则的<strong>设计行为</strong>（{@code 12 §2.4}：页眉页脚会污染分块与向量），不是 OCR 丢 —— 实测 OCR 原始输出里三类内容全部存在（目录 6
   * 行、页脚三页、页首标题三页），OCR 原始逐页相似度 0.90/0.93/0.85，而走剥离后跌到 issue #177 的 0.13/0.79/0.36。
   *
   * <p><strong>keepDesignLines = true</strong>：跳过 ②②b③④ 全部排版噪声规则，只保留 ① 占位标记剥离（{@code #51} 的 {@code
   * [PDF N]} 是解析垃圾，任何模式都剥）。给"裁定保留章节锚点/页脚"的消费方（issue #177 要求的"可关的开关"）；<strong>默认 false
   * 不改变现有行为</strong> —— 默认口径是否变更待 12 §2.4 裁定，不在本 worktree 拍板。
   *
   * @param markdown 解析产物（{@code Document.parsedContent}）；null / 空 ⇒ 原样返回
   * @param keepDesignLines true = 保留排版噪声行（目录/页脚/页首重复标题/页码/版权）
   */
  public static Result strip(String markdown, boolean keepDesignLines) {
    if (markdown == null || markdown.isBlank()) {
      return Result.untouched(markdown == null ? "" : markdown);
    }
    String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);

    // ① 先标出代码围栏的行 —— 它们一律不参与（配置片段里重复的行是内容）
    boolean[] inFence = new boolean[lines.length];
    boolean fence = false;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].stripLeading().startsWith(FENCE)) {
        inFence[i] = true;
        fence = !fence;
        continue;
      }
      inFence[i] = fence;
    }

    boolean[] drop = new boolean[lines.length];

    // ① 图片占位标记（`#51`）：`[PDF 1]` 这种整行剥离。★ 先于其余规则 —— 它是"解析器/源文件留下的垃圾"，
    //   而其余规则都是"排版噪声"，两者成因不同，混在一条分支里以后说不清是谁剥的。
    for (int i = 0; i < lines.length; i++) {
      if (inFence[i]) {
        continue;
      }
      if (PARSER_PLACEHOLDER.matcher(lines[i].strip()).matches()) {
        drop[i] = true;
      }
    }

    // ②②b③④ 一起受 #177 开关 keepDesignLines 控制：它们同属「排版噪声」规则（12 §2.4），
    //   true 时整体跳过（只留 ① 占位剥离）。★ 分组成因：把它们混进 ① 会说不清是谁剥的。
    if (!keepDesignLines) {
      // ② 独立成行的页码
      for (int i = 0; i < lines.length; i++) {
        String trimmed = lines[i].strip();
        if (!trimmed.isEmpty() && !inFence[i] && PAGE_NUMBER.matcher(trimmed).matches()) {
          drop[i] = true;
        }
      }

      // ②b 版权声明行（`#271`）：整行 + 行首 + 不超长。
      //   ★ 必须先于 ③「跨页重复行」：它只出现 1 次时那条规则认不出来 —— `noisy.docx` 的 11/12
      //     差的就是这一条（夹具基准把它记作 noiseLines，而生产实现此前没有这条规则）。
      for (int i = 0; i < lines.length; i++) {
        if (inFence[i]) {
          continue;
        }
        String trimmed = lines[i].strip();
        if (!trimmed.isEmpty()
            && trimmed.length() < COPYRIGHT_MAX_LENGTH
            && COPYRIGHT_LINE.matcher(trimmed).matches()) {
          drop[i] = true;
        }
      }

      // ③ 跨页重复行：同一行出现 ≥3 次且长度 < 60 字 ⇒ 判为页眉页脚，**全部**剔掉
      //
      //   ★★ 保守性护栏（实测踩到过，必须留）：**重复行必须是少数派**。
      //     "跨页重复行"这条规则的隐含前提是"页面里还有别的内容"；而一份
      //     "一行短句重复 N 次"的文档会被整篇剥空（实测：闸门夹具就是这种文本 ⇒ 预览 0 段、
      //     整篇文档看起来"有内容却切不出段"）。这类误杀属于 §10.2 风险 2 *内容静默缺失*，
      //     比漏剔严重得多 —— 所以宁可在这类文档上一条都不剥。
      Map<String, List<Integer>> occurrences = new LinkedHashMap<>();
      int nonBlank = 0;
      for (int i = 0; i < lines.length; i++) {
        if (inFence[i] || lines[i].isBlank()) {
          continue;
        }
        nonBlank++;
        String trimmed = lines[i].strip();
        if (drop[i] || trimmed.length() >= REPEAT_MAX_LENGTH) {
          continue;
        }
        occurrences.computeIfAbsent(trimmed, k -> new ArrayList<>()).add(i);
      }
      int repeatedTotal = 0;
      for (Map.Entry<String, List<Integer>> entry : occurrences.entrySet()) {
        if (entry.getValue().size() >= REPEAT_MIN_OCCURRENCES) {
          repeatedTotal += entry.getValue().size();
        }
      }
      if (repeatedTotal * 2 <= nonBlank) {
        for (Map.Entry<String, List<Integer>> entry : occurrences.entrySet()) {
          if (entry.getValue().size() >= REPEAT_MIN_OCCURRENCES) {
            entry.getValue().forEach(index -> drop[index] = true);
          }
        }
      }

      // ④ 目录页：连续 ≥5 行都是"点线 + 结尾页码"（★ 空行不打断）
      //
      //   ★★ 实测教训（这条规则踩了两次，都记在这里）：
      //     第一次：点线与页码之间的标点。真实 OCR 输出是「第一章 总则 ..……….….， 1」（中间是个逗号），
      //             而第一版正则要求点线紧跟数字 ⇒ 一条都没剔掉。
      //     第二次（本次）：**Tika 的 Markdown 输出每行之间都有空行**，于是"连续 5 行"在物理行上
      //             永远不成立 ⇒ 真栈上依然一条没剔，而单测（理想文本无空行）全绿。
      //   ⇒ 两次的共性：**规则的判据不能按物理行写，要按"有内容的行"写**。
      List<Integer> tocRun = new ArrayList<>();
      for (int i = 0; i < lines.length; i++) {
        if (inFence[i]) {
          tocRun.clear();
          continue;
        }
        if (lines[i].isBlank()) {
          continue; // 空行既不算入，也不打断
        }
        if (TOC_LINE.matcher(lines[i].strip()).matches()) {
          tocRun.add(i);
        } else {
          if (tocRun.size() >= TOC_MIN_RUN) {
            tocRun.forEach(index -> drop[index] = true);
          }
          tocRun.clear();
        }
      }
      if (tocRun.size() >= TOC_MIN_RUN) {
        tocRun.forEach(index -> drop[index] = true);
      }
    }

    // ⑤ 拼回去：被剔掉的行不占位（留下空行会让后续按段落切分多出空块）
    StringBuilder kept = new StringBuilder();
    List<String> samples = new ArrayList<>();
    int removed = 0;
    for (int i = 0; i < lines.length; i++) {
      if (drop[i]) {
        if (!lines[i].isBlank()) {
          removed++;
          if (samples.size() < 5) {
            samples.add(lines[i].strip());
          }
        }
        continue;
      }
      kept.append(lines[i]).append('\n');
    }
    return new Result(kept.toString(), removed, List.copyOf(samples));
  }
}
