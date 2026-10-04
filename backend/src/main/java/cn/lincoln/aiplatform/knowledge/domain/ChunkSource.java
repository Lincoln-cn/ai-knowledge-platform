package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 分块规则集的来源口径（第 9 周 · 0.3.1，{@code 12 §11.5}）。
 *
 * <h3>★ 为什么 OCR 文本要另走一套规则</h3>
 *
 * <p>OCR 出来的文本会<strong>击穿四层规则的一半</strong>（{@code 12 §11.5} 的实测判断）：
 *
 * <ul>
 *   <li><b>L1 标题</b>：中等失效 —— OCR 断行会把一个标题**劈成多行**
 *   <li><b>L2 语义单元「第X条」</b>：★ 相对最稳（中文法规编号是高识别率模式）⇒ <b>保持，并作为主锚点</b>
 *   <li><b>L3 句边界</b>：★ 大面积失效 —— 行尾无标点、行尾断词 ⇒ 改为按行拼接 + 尾标点推断，<b>不可靠时直接跳 L4</b>
 *   <li><b>L4 硬切</b>：<b>成为主力</b>（在 OCR 文档上它不再是兜底）
 * </ul>
 *
 * <p>⇒ 文本型走 {@code L1 → L2 → L3 → L4}；<b>OCR 型走 {@code L2 → L1'（正则 + 相邻行合并）→ L4}</b>。
 *
 * <p>★ 判定依据是**文档级的 OCR 事实**（{@code Document#ocrDerived()} = 「真的走了 OCR 的页数 &gt; 0」）， 不是"环境里开着
 * OCR"：混合型 PDF 可能一页都没走 OCR，那时它就该按文本型规则切。
 */
public enum ChunkSource {
  /** 文本型（DOCX / TXT / 有文本层的 PDF）—— 四层规则齐全。 */
  TEXT,
  /** OCR 型（扫描件 / 图像型 PDF）—— L2 主锚点 + 跳 L3。 */
  OCR
}
