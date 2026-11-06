package cn.lincoln.aiplatform.platform.compliance;

/**
 * 投诉举报工单状态机（{@code #83} 第十五条）。
 *
 * <h3>★ 状态机（公示的「处理流程」就是它，公示文案与实现必须同源）</h3>
 *
 * <pre>
 *   SUBMITTED（已提交） → PROCESSING（处理中） → RESOLVED（已办结）
 *          └──────────────────→ REJECTED（不予受理）
 * </pre>
 *
 * <p>规则三条：
 *
 * <ol>
 *   <li>流转方向只往前（{@link #canTransitionTo}）；办结与不予受理是终态，不设重开 —— 重开是 feedback
 *       的口径（内容质量反馈可反复处理），投诉举报是**留痕举证**记录， 终态翻案会破坏举证链；要翻案就再提一单（新工单号）。
 *   <li>{@code RESOLVED} / {@code REJECTED} 必须写处理说明（办结结果 / 不予受理理由）， 否则 400 ——
 *       「反馈」要有可举证的内容，不能只有一个状态翻转。
 *   <li>提交之后 {@code subject / content / kind / contact} 不可改（举证完整性）， 能改的只有状态与处理面字段。
 * </ol>
 */
public enum ComplaintStatus {
  /** 已提交：等待受理。反馈时限（{@code due_at}）从提交时刻起算。 */
  SUBMITTED,
  /** 处理中：已受理、指派处理人。 */
  PROCESSING,
  /** 已办结：处理完成，办结结果已反馈（必填处理说明）。 */
  RESOLVED,
  /** 不予受理：不在受理范围（必填不予受理理由）。 */
  REJECTED;

  /**
   * 当前状态能否流转到目标状态（同态更新允许：补写处理说明也算一次 PATCH）。
   *
   * @param target 目标状态
   * @return true = 合法流转（含同态）
   */
  public boolean canTransitionTo(ComplaintStatus target) {
    return switch (this) {
      case SUBMITTED -> target == SUBMITTED || target == PROCESSING || target == REJECTED;
      case PROCESSING -> target == PROCESSING || target == RESOLVED || target == REJECTED;
      case RESOLVED, REJECTED -> target == this;
    };
  }
}
