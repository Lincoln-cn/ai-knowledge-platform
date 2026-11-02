package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;

/**
 * 多跳的"要不要再检一轮"判定，跨模块的唯一入口。
 *
 * <p>★ 为什么需要这个端口（`#293`）：{@code conversation} 原先直接 {@code import} {@code
 * retrieval.application.MultiHopService} —— 那会让问答模块依赖检索模块的**实现类型**， 而 {@code ModuleBoundaryTest}
 * 的规矩是「跨模块只认 {@code platform} 里的端口」。 当时那条红是这么报的：
 *
 * <pre>
 * Module 'conversation' depends on non-exposed type
 *   cn.lincoln.aiplatform.retrieval.application.MultiHopService within module 'retrieval'!
 * </pre>
 *
 * <p>★ 处置**不是**把 {@code MultiHopService} 开成 {@code @NamedInterface} —— 那是"改闸门去迁就实现" （同族先例见 {@code
 * platform.assistant.AppBindingPort} 的类注释）。正确做法是把**形状**放进 {@code platform}，让实现留在 {@code retrieval}
 * 里。
 *
 * <p>★ 这个端口的形状刻意**不引用** {@code retrieval.domain.HopDecision}：那个类型带 {@code Payload}
 * 解析细节，属于检索侧的内部表达。这里只要三项事实 —— 够不够、下一问是什么、为什么没用上。
 */
public interface HopDeciderPort {

  /**
   * 一次判定的结果。
   *
   * @param sufficient 已有命中是否已经够回答这个问题
   * @param nextQuery 若不够，下一轮该用什么查询（可能为空）
   * @param failure 判定为什么不可用（{@code null} = 可用）
   * @param elapsedMs 这次判定花了多少毫秒（只用于日志与预算，不参与判据）
   */
  record Result(boolean sufficient, String nextQuery, String failure, long elapsedMs) {

    /** 这次判定有没有得出结论。★ 不可用时调用方必须"用已有结果继续"，不能抛。 */
    public boolean usable() {
      return failure == null;
    }

    /** 有没有下一轮要检的查询。 */
    public boolean hasNextQuery() {
      return nextQuery != null && !nextQuery.isBlank();
    }
  }

  /**
   * 判一次。
   *
   * @param question 用户原始提问
   * @param hits 当前已召回的分段（供判定参考）
   * @param timeoutMs 这次判定的预算；{@code <= 0} 表示用实现自己的默认值
   */
  Result decide(String question, List<SegmentSearchPort.Card> hits, long timeoutMs);
}
