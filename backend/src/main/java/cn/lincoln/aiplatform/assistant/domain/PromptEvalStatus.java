package cn.lincoln.aiplatform.assistant.domain;

/**
 * 一次评测运行的状态（ROADMAP §11.63.3）。
 *
 * <p>★ 为什么要落库而不是只放在内存里：一次评测 = 2 × 题数 次模型调用（上限 30 题 ⇒ 最多 60 次），
 * 几分钟起步。前端关掉再打开、或者浏览器刷新，都该能看到"上次跑完了没有、跑完了看结果"。 只放内存的话，用户一不小心刷新就白等了。
 */
public enum PromptEvalStatus {
  /** 正在跑。{@code item_done} 跟着涨，前端轮询它画进度。 */
  RUNNING,
  /** 全部跑完，可以评分了。 */
  DONE,
  /** 中途失败（模型不可用、题目被删等）。{@code fail_reason} 里有人说得出的话。 */
  FAILED
}
