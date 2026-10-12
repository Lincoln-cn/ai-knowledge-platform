package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import java.util.List;
import java.util.UUID;

/**
 * 命中测试的结果（契约 {@code HitTestResult}）。
 *
 * @param mode 本次用的模式（前端并发跑三种模式做对比，靠它区分回包）
 * @param elapsedMs 本次检索耗时 —— 界面用它展示"调参前后快慢"
 * @param trace ★ 策略层的可观测特征（第 12 周 · 0.4.0）—— <strong>后端不做埋点服务，就把它放进回包里</strong>：
 *     它是"这一层到底做了什么"的唯一凭据，界面上能看到、日志里也记一份。可选字段，老前端不读也照常工作
 */
public record HitTestView(RetrievalMode mode, List<Item> items, long elapsedMs, Trace trace) {

  /**
   * 一次检索的策略轨迹（{@code 10 §7.1} 的埋点口径落在这一处）。
   *
   * <p>★ <strong>为什么必须有它</strong>：多层策略最典型的失败不是"报错"，而是<em>"这周的答案莫名变差了"</em> ——
   * 没有任何地方记录当时降级了、拆分成了几个子问题、发出去几条查询。把这三个数字放进回包， 演示与调参才有依据（{@code 10 §6.3}："每一步降级都必须可观测"）。
   *
   * @param subQuestionCount M：模型拆出的子问题数（未拆分时是 1）
   * @param queryCount N：**实际发出去**的查询条数（不是配置值 —— 截断之后才是事实）
   * @param pathCount 总检索路数（受 {@code 10 §6.2} 第 4 项的上限 6 截断后的真实路数）
   * @param degradeLevel {@code 10 §6.3} 的档位（0 正常 / 3 只做成改写 / 4 退回原始问题单路）
   * @param degradeReason 为什么降级；正常路径下它装的是"解析器改动了什么"（截断/去重/兜底）， ★
   *     两者共用字段是刻意的：它们都是"这一层到底做了什么"的说明，而分开两个字段会让界面只显示一半
   * @param truncated 子问题数或查询条数被截断过
   * @param elapsedMs 查询理解这一层花掉的时间（它直接从首字延迟里扣）
   */
  public record Trace(
      int subQuestionCount,
      int queryCount,
      int pathCount,
      int degradeLevel,
      String degradeReason,
      boolean truncated,
      long elapsedMs,
      boolean hopTriggered,
      int hopRounds,
      String hopStopReason,
      int hopNewSegments) {}

  /**
   * 一条命中。
   *
   * @param rank 从 1 起连续（{@code 03 §2.3}：引用编号连续，便于模型引用与用户点击）
   * @param score ★ 该模式的 0–1 分数：<strong>向量路是余弦相似度、全文路是"查询词命中覆盖率"</strong>。 混合模式给的是"两路里最好的那个分"而不是 RRF
   *     分 —— RRF 分（≈0.016）对人没有意义， 而融合的次序已经由 RRF 决定了。三种模式都用 0–1 是为了界面上那个阈值滑块说得通
   * @param content ★ 是 {@code content} 而不是 {@code search_text}：引用展示要干净原文 （{@code 12 §4.2} 纪律 1）
   */
  public record Item(
      int rank,
      UUID segmentId,
      String title,
      String docName,
      String kbName,
      double score,
      String content) {}
}
