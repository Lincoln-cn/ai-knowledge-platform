package cn.lincoln.aiplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 噪声剥离（第 9 周 · 0.3.1，{@code 12 §2.4}）—— 解析之后、分块之前跑一遍，把页眉页脚/页码/目录剔掉。
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
   * 剥掉页眉/页脚/页码/目录行。
   *
   * @param markdown 解析产物（{@code Document.parsedContent}）；null / 空 ⇒ 原样返回
   */
  public static Result strip(String markdown) {
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

    // ② 独立成行的页码
    for (int i = 0; i < lines.length; i++) {
      String trimmed = lines[i].strip();
      if (!trimmed.isEmpty() && !inFence[i] && PAGE_NUMBER.matcher(trimmed).matches()) {
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
