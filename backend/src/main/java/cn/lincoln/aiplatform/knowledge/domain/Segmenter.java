package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/**
 * 分段计划器（第 7 周 · 0.2.2 批 2a）—— <strong>预览与入库共用的唯一分块入口</strong>。
 *
 * <p>★ 判据 ④：预览与入库必须「同输入 ⇒ 同输出」。做法是两边都只调 {@link #plan}， 不允许各自再拼一遍 {@code search_text} ——
 * 否则「预览好看、入库难看」的分叉只是时间问题（{@code 12 §3.5}）。
 *
 * <p>职责边界：
 *
 * <pre>
 * MarkdownChunker —— 切（含 12 §3.3 重叠），产出 SegmentChunk
 * Segmenter       —— 补前缀 / search_text / contentHash，并用 SegmentContent 做 SEG-I1 硬校验
 * </pre>
 *
 * <p>★ {@code maxChars} <strong>不是常量</strong>：它是随绑定向量模型能力换算出的领域配置 （{@code 02 §2.3} / {@code 12
 * §9.1}）。本批不接模型目录，由调用方传入。
 *
 * <p>★ 确定性：只用 {@link List} 顺序拼装，不遍历 Map、不读时钟 —— 同输入必须逐字节同输出。
 */
public class Segmenter {

  private final MarkdownChunker chunker = new MarkdownChunker();

  /**
   * 有效装箱目标长度（{@code SEG-I5}）—— 唯一计算点，测试直接断言本方法。
   *
   * <p>进向量的是 {@code searchText = 前缀 + 分隔 + 正文}（{@code 12 §4.2}）⇒ 正文装箱目标要扣掉 前缀与分隔符，再与调用方的 {@code
   * segmentLength} 取更紧的一侧。
   *
   * <p><strong>overlap 不在此处扣</strong>：{@link MarkdownChunker#chunk(String, int, int)} 会按 {@code
   * segmentLength - overlap} 装箱再补重叠前缀（{@code 12 §3.3}）。两处各扣一次会把目标算没。
   *
   * <p>★ 调用方必须传<strong>最坏前缀长度</strong>（{@link SearchText#MAX_PREFIX_LENGTH}）而不是自己那篇文档的 前缀长度：{@code
   * titlePath} 每段不同、装箱前取不到，按文档级前缀估会让「估算器认为装得下、校验器却拒绝」 自相矛盾（详见 {@link #plan} 的说明）。
   *
   * @param prefixLength 前缀长度预算（应取 {@link SearchText#MAX_PREFIX_LENGTH}，见上）
   * @return 正文（不含前缀）的装箱上限；≤0 表示向量预算装不下前缀与任何正文
   */
  public static int effectiveTargetLength(int segmentLength, int maxChars, int prefixLength) {
    return Math.min(segmentLength, maxChars - prefixLength - SearchText.SEPARATOR.length());
  }

  /**
   * 产出有序分段计划（预览与入库共用）。
   *
   * <p>★ <strong>预算按最坏前缀估</strong>（{@code 12 §10.2} 风险 1 定的处置方式是「设前缀上限」，不是「拒绝整篇」）： 前缀由 {@link
   * SearchText#prefix} 保证 ≤ {@link SearchText#MAX_PREFIX_LENGTH}，于是
   *
   * <pre>
   * searchText ≤ (maxChars − 80 − 1) + 1 + 80 = maxChars
   * </pre>
   *
   * 是算术恒等式 ⇒ <strong>自动切分路径永远不会触发 SEG-I1 整篇失败</strong>。若改用「文档级前缀」估（更宽松）， 一个 48 字的章节标题就足以让 {@code
   * segmentLength = 1200}（= 默认 {@code modelLimit}，滑块上很自然的值） 的文档在入库时被判死 —— 估算器与校验器自相矛盾，而用户只是拖了一下滑块。
   * 代价是文档级前缀很短时每段少用几十字预算（≤ 4%），换「自动路径不可能整篇失败」，值。
   *
   * <p>★ 硬校验仍然保留：{@link SegmentContent#ofSearchText} 是 SEG-I1 的执法点，
   * 它真正要拦的是<strong>用户编辑过的切分计划</strong>（{@code 12 §3.5}：用户可能粘进超长内容）—— 那条路径绕过本方法，由批 2b 的端点校验。
   *
   * @param markdown 解析产物 Markdown（{@code Document.parsedContent}）
   * @param segmentLength 分段目标长度（字符，正文口径）
   * @param overlap 重叠字符数（{@code 12 §3.3}；0 表示不重叠）
   * @param maxChars 向量模型字符上限（随模型变化的领域配置，调用方传入）
   * @param docType 文种（前缀 L1）
   * @param department 责任部门（前缀 L1）
   * @param title 文档名（前缀 L1）
   * @return 有序计划；空 markdown 返回空列表
   * @throws IllegalArgumentException 分段/重叠参数非法，或前缀组成缺失
   * @throws BusinessException 向量预算连前缀都装不下（{@link ErrorCode#SEGMENT_TOO_LONG}）时带可读原因与 hint
   */
  public SegmentPlan plan(
      String markdown,
      int segmentLength,
      int overlap,
      int maxChars,
      String docType,
      String department,
      String title) {
    return plan(
        markdown, segmentLength, overlap, maxChars, docType, department, title, ChunkSource.TEXT);
  }

  /**
   * 产出有序分段计划（预览与入库共用）—— 第 9 周起多两件事。
   *
   * <p>★★ <b>先剥噪声再分块</b>（{@code 12 §2.4}）：页眉页脚/页码/目录会在 50 个 chunk 里重复 ⇒ 它们互相"很像" ⇒
   * 召回被稀释。扫描件上更严重（Tesseract 不产出 {@code ocr_header}，页眉只能我们认）。
   * 剥离放在<strong>这里</strong>、而不是解析器里，是为了让**预览与入库同源** —— 否则"预览看到的有页眉、入库的没有"就是下一个静默分叉（{@code 12
   * §3.5}）。
   *
   * <p>★ <b>按来源选规则集</b>（{@code 12 §11.5}）：文本型 {@code L1→L2→L3→L4}； OCR 型 {@code L2→L1'→L4}（跳
   * L3）。判据是文档级的 OCR 事实，不是"环境里开着 OCR"。
   *
   * @param source 分块规则集来源（文本型 / OCR 型）
   * @return 有序计划 + 噪声剥离行数
   */
  public SegmentPlan plan(
      String markdown,
      int segmentLength,
      int overlap,
      int maxChars,
      String docType,
      String department,
      String title,
      ChunkSource source) {
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
    if (maxChars <= 0) {
      throw new IllegalArgumentException("向量上限必须为正数，当前：" + maxChars);
    }
    // 前缀三项构成 L1 文档级标识（12 §4.2）。缺任一项会让前缀变成「null·null·null｜」——
    // 那是往库里写静默数据垃圾，必须在入口拒绝（建档路径本就保证非空）
    if (blank(docType) || blank(department) || blank(title)) {
      throw new IllegalArgumentException("文种/责任部门/文档名都不能为空 —— 它们构成检索前缀（12 §4.2）");
    }
    if (markdown == null || markdown.isBlank()) {
      return new SegmentPlan(List.of(), 0);
    }

    // 预算按最坏前缀（MAX_PREFIX_LENGTH）估，而不是本篇文档的文档级前缀 —— 理由见方法 javadoc
    int target = effectiveTargetLength(segmentLength, maxChars, SearchText.MAX_PREFIX_LENGTH);
    if (target <= 0) {
      throw new BusinessException(
          ErrorCode.SEGMENT_TOO_LONG,
          "向量预算 " + maxChars + " 字不足以容纳前缀（最坏 " + SearchText.MAX_PREFIX_LENGTH + " 字）与任何正文。",
          "请调大向量模型的上限（换长上下文模型），或缩短文档名/部门标签。");
    }

    NoiseStripper.Result stripped = NoiseStripper.strip(markdown);
    if (stripped.markdown().isBlank()) {
      // ★ 全是噪声 ⇒ 不产分段，但**剥离行数照记**：否则"文档有内容却切不出段"看起来像 bug
      return new SegmentPlan(List.of(), stripped.removedLines());
    }

    ChunkSource effective = source == null ? ChunkSource.TEXT : source;
    List<SegmentChunk> chunks = chunker.chunk(stripped.markdown(), target, overlap, effective);
    List<PlannedSegment> plan = new ArrayList<>(chunks.size());
    for (SegmentChunk chunk : chunks) {
      String prefix = SearchText.prefix(docType, department, title, chunk.titlePath());
      // ★ 校验与落库必须是同一个字符串：把 ofSearchText 的产物直接当 searchText 用，
      //   避免「我验的和我要存的是两次独立拼装」这类只在边界上才暴露的漂移（SEG-I1 / SEG-I5）
      String searchText = SegmentContent.ofSearchText(prefix, chunk.content(), maxChars).text();
      plan.add(
          new PlannedSegment(
              chunk.ordinal(),
              chunk.content(),
              searchText,
              SearchText.contentHash(
                  docType, department, title, chunk.titlePath(), chunk.content()),
              chunk.title(),
              chunk.titlePath(),
              chunk.isTable(),
              chunk.isCode(),
              chunk.oversizedRisk()));
    }
    return new SegmentPlan(plan, stripped.removedLines());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
