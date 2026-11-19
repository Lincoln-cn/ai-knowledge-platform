package cn.lincoln.aiplatform.assistant.domain;

/**
 * 人工盲评的三档（ROADMAP §11.63.2 第 2 层）。
 *
 * <p>★ 为什么必须有 {@link #SAME}：只给"更好 / 更差"会逼人瞎选，数据反而更脏。 三档里"差不多"是最常用的那个 —— 大多数改法本来就不改变答案。
 *
 * <p>★ 胜率口径：{@code (BETTER × 1 + SAME × 0.5) / 已评题数}。未评的题不参与计算， 但界面上必须单独显示"还有几题没评" —— 20 题里评了 3
 * 题得出的 100% 不能当结论看。
 */
public enum EvalVerdict {
  BETTER,
  SAME,
  WORSE
}
