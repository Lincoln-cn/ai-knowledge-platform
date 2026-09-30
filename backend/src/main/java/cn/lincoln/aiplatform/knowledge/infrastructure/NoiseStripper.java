package cn.lincoln.aiplatform.knowledge.infrastructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 噪声剥离器（第 6 周 · 0.2.1 批 2）—— 规则出自 {@code 12 §2.4}。
 *
 * <p>★ 为什么必须有这一环：页眉页脚、页码、水印、目录页会<strong>污染分块与向量</strong> —— 同一页眉在 50 个 chunk 里重复 ⇒ 它们互相「很像」⇒
 * 召回被稀释（{@code 12 §2.4}）。
 *
 * <p>★ 纪律两条：
 *
 * <ol>
 *   <li><strong>保守</strong>：误杀正文是静默失败（{@code 12 §10.2 风险 2}）—— 阈值取设计给出的下限 （重复 ≥3 次 + 长度 &lt;60
 *       字），宁可漏剥也不多剥；
 *   <li><strong>可观测</strong>：剥掉多少行记进 {@link StripResult#removedNoiseLines()}，预览里可见。
 * </ol>
 *
 * <p>规则清单：
 *
 * <ol>
 *   <li><strong>页眉页脚 / 水印</strong>：跨页重复行 —— 同一行 ≥3 处出现且长度 &lt; 60 字（水印以「孤立短行 + 高频重复」出现，同一条规则覆盖）
 *   <li><strong>页码</strong>：纯数字 / 罗马数字 / 「第 X 页 共 Y 页」的独立行
 *   <li><strong>目录页</strong>：连续 ≥5 行「…… 数字」结尾 ⇒ 整段剔除，但保留章节标题
 *   <li><strong>版权行</strong>：© / Copyright / 版权所有 / 翻印必究 等声明形态的短行
 *   <li><strong>空白段落</strong>：连续空行折叠为单空行（Markdown 段落边界保留）
 * </ol>
 */
public class NoiseStripper {

  /** 跨页重复阈值（{@code 12 §2.4}：≥3 处）。 */
  private static final int REPEAT_THRESHOLD = 3;

  /** 页眉页脚长度上限（{@code 12 §2.4}：<60 字）。超过的重复多半是正文强调，剥了就是误杀。 */
  private static final int MAX_NOISE_LINE_LENGTH = 59;

  /** 目录页最少连续行数（{@code 12 §2.4}：≥5 行）。不足则不构成目录页。 */
  private static final int MIN_TOC_RUN = 5;

  /** 版权声明行长上限 —— 声明是短行，业务条款谈版权通常更长。 */
  private static final int MAX_COPYRIGHT_LENGTH = 80;

  /** 纯数字页码：1–4 位（页码到不了 5 位；更长的独立数字串当正文）。 */
  private static final Pattern PURE_DIGITS = Pattern.compile("^\\d{1,4}$");

  /** 罗马数字页码（i, ii, iii, iv… 大小写均可）。 */
  private static final Pattern ROMAN_NUMERAL = Pattern.compile("(?i)^[ivxlcdm]{1,8}$");

  /** 「第 X 页」「第 X 页 共 Y 页」「第X页/共Y页」的独立行。 */
  private static final Pattern PAGE_CN =
      Pattern.compile("^第\\s*\\d{1,4}\\s*页(?:\\s*[共/]\\s*\\d{1,4}\\s*页)?$");

  /** 目录行：标题 + 点线/省略号引导 + 结尾页码（{@code 12 §2.4}「…… 数字」结尾）。 */
  private static final Pattern TOC_LINE =
      Pattern.compile("^\\s*\\S.*(?:\\.{2,}|…{1,})\\s*\\d{1,4}\\s*$");

  /** 版权声明行：声明形态的标记（正文里的知识产权业务条款不在此列）。 */
  private static final Pattern COPYRIGHT =
      Pattern.compile(
          "(?i)(?:©|\\(c\\)|copyright|all\\s+rights\\s+reserved|版权所有|翻印必究|未经.{0,8}许可.{0,6}(?:翻印|复制))");

  /**
   * 剥离噪声。
   *
   * @param markdown 解析出的 Markdown（可为空）
   * @return 干净 Markdown + 剥离计数
   */
  public StripResult strip(String markdown) {
    if (markdown == null || markdown.isEmpty()) {
      return StripResult.of("", 0, List.of());
    }
    String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    boolean[] remove = new boolean[lines.length];

    // ★ 顺序有意义：先认「形态明确」的噪声（页码/版权/目录），最后才跑重复行规则 ——
    //   重复行是兜底（页眉页脚/水印），把已判定的行排除在外可避免二次标记与计数翻倍。
    markPageNumbers(lines, remove);
    markCopyright(lines, remove);
    markTocBlocks(lines, remove);
    markRepeatedShortLines(lines, remove);

    return rebuild(lines, remove);
  }

  // ────────────────────────── 规则 ──────────────────────────

  /** 页码：纯数字 / 罗马数字 / 「第 X 页 共 Y 页」的独立行（{@code 12 §2.4}）。 */
  private static void markPageNumbers(String[] lines, boolean[] remove) {
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.isEmpty() || isStructural(t)) {
        continue;
      }
      if (PURE_DIGITS.matcher(t).matches()
          || ROMAN_NUMERAL.matcher(t).matches()
          || PAGE_CN.matcher(t).matches()) {
        remove[i] = true;
      }
    }
  }

  /**
   * 版权行：© / Copyright / 版权所有 / 翻印必究 等<strong>声明形态</strong>短行。
   *
   * <p>★ 不误杀的边界：正文里谈知识产权归属的业务条款（「第 8 条 知识产权归…」「涉及著作权的…」） 不带这些声明标记，不会被认成版权行。
   */
  private static void markCopyright(String[] lines, boolean[] remove) {
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.isEmpty() || isStructural(t) || t.length() > MAX_COPYRIGHT_LENGTH) {
        continue;
      }
      if (COPYRIGHT.matcher(t).find()) {
        remove[i] = true;
      }
    }
  }

  /**
   * 目录页：连续 ≥5 行「…… 数字」结尾 ⇒ 整段剔除（{@code 12 §2.4}）。
   *
   * <p>★ 空行不断开「连续」判定：Tika 的 {@code ToMarkdownContentHandler} 在段落之间插空行，
   * 若把空行当边界，目录条目永远不连续、整段剥不掉（实测噪声召回会掉到 3/12）。
   *
   * <p>★ 保留章节标题：以 {@code #} 开头的行永远不进删除集 —— 结构抽取靠标题树（{@code 12 §3.4}）， 剥掉标题等于章节路径拼不出来。
   */
  private static void markTocBlocks(String[] lines, boolean[] remove) {
    int runStart = -1;
    int runCount = 0;
    for (int i = 0; i <= lines.length; i++) {
      boolean toc = i < lines.length && isTocEntry(lines[i]);
      // ★ 空行只是排版间隙，不算打断 —— 目录在解析产物里常被空行隔开
      boolean gap = i < lines.length && lines[i].isBlank();
      if (toc) {
        if (runStart < 0) {
          runStart = i;
        }
        runCount++;
        continue;
      }
      if (gap) {
        continue;
      }
      if (runStart >= 0) {
        if (runCount >= MIN_TOC_RUN) {
          for (int j = runStart; j < i; j++) {
            if (!lines[j].isBlank() && !isStructural(lines[j].trim())) {
              remove[j] = true;
            }
          }
        }
        runStart = -1;
        runCount = 0;
      }
    }
  }

  /**
   * 页眉页脚 / 水印：同一行 ≥3 处出现且长度 &lt; 60 字（{@code 12 §2.4}）。
   *
   * <p>★ 三条防误杀边界（缺一条就会把正文当页眉删掉）：
   *
   * <ul>
   *   <li>只统计<strong>裸段落</strong>行 —— 标题/列表/表格是结构信号（{@code 12 §2.1}），重复也不剥；
   *   <li>长度 ≥60 字不剥 —— 长句重复多半是条款复述；
   *   <li>次数 &lt;3 不剥 —— 正文关键结论重复一次很正常。
   * </ul>
   */
  private static void markRepeatedShortLines(String[] lines, boolean[] remove) {
    Map<String, Integer> freq = new HashMap<>();
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.isEmpty() || remove[i] || isStructural(t) || t.length() > MAX_NOISE_LINE_LENGTH) {
        continue;
      }
      freq.merge(t, 1, Integer::sum);
    }
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.isEmpty() || remove[i] || isStructural(t) || t.length() > MAX_NOISE_LINE_LENGTH) {
        continue;
      }
      if (freq.getOrDefault(t, 0) >= REPEAT_THRESHOLD) {
        remove[i] = true;
      }
    }
  }

  // ────────────────────────── 重建 ──────────────────────────

  /**
   * 重建 Markdown：删掉标记行 + 把连续空行折叠为单空行。
   *
   * <p>★ 空白折叠<strong>不计</strong>入 {@code removedNoiseLines} —— 那是排版归一，不是噪声剥离； 计数要能对上「被剥掉的噪声行」才有解释力。
   */
  private static StripResult rebuild(String[] lines, boolean[] remove) {
    StringBuilder out = new StringBuilder();
    List<String> removed = new ArrayList<>();
    int removedCount = 0;
    boolean lastBlank = true; // 文首空行不保留

    for (int i = 0; i < lines.length; i++) {
      if (remove[i]) {
        removedCount++;
        removed.add(lines[i]);
        continue;
      }
      boolean blank = lines[i].isBlank();
      if (blank) {
        if (lastBlank) {
          continue;
        }
        lastBlank = true;
        out.append('\n');
        continue;
      }
      lastBlank = false;
      out.append(lines[i]).append('\n');
    }
    // 末尾统一收成单个换行（与输入常见的 trailing newline 对齐）
    String text = out.toString();
    if (!text.isEmpty() && !text.endsWith("\n")) {
      text = text + "\n";
    }
    while (text.endsWith("\n\n")) {
      text = text.substring(0, text.length() - 1);
    }
    return StripResult.of(text, removedCount, removed);
  }

  // ────────────────────────── 判定辅助 ──────────────────────────

  /** 结构行（标题/列表/表格）—— 分块器靠它们切语义边界（{@code 12 §2.1}），任何规则都不许碰。 */
  private static boolean isStructural(String trimmed) {
    if (trimmed.startsWith("#")) {
      return true;
    }
    if (trimmed.startsWith("|")) {
      return true;
    }
    // Markdown 列表：- / * / + / 1. / 1)
    return trimmed.matches("^([-*+]|\\d+[.)])\\s+.*") || trimmed.matches("^([-*+]|\\d+[.)])$");
  }

  private static boolean isTocEntry(String line) {
    String t = line.trim();
    if (t.isEmpty() || isStructural(t)) {
      return false;
    }
    return TOC_LINE.matcher(t).matches();
  }
}
