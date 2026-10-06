package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import java.util.List;
import java.util.UUID;

/**
 * 命中测试的结果（契约 {@code HitTestResult}）。
 *
 * @param mode 本次用的模式（前端并发跑三种模式做对比，靠它区分回包）
 * @param elapsedMs 本次检索耗时 —— 界面用它展示"调参前后快慢"
 */
public record HitTestView(RetrievalMode mode, List<Item> items, long elapsedMs) {

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
