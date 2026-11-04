package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.QueryUnderstandingPort;
import cn.lincoln.aiplatform.retrieval.domain.QueryUnderstanding;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 查询理解端口的薄壳适配（`#297`）。
 *
 * <p>★★ <strong>它只做类型搬运，零逻辑</strong> —— 之所以能是零逻辑，是因为端口的 {@link QueryUnderstandingPort.SubQuestion}
 * 与 {@link QueryUnderstanding.SubQuestion} 字段逐字对应（`question` / `queries`）。任何"顺手在这里做点加工"的改动都会让
 * 两个模块的行为分叉，而分叉之后 {@code #297} 的判据（改写日志里必须同时出现原句与 子问题 × 查询）会变成"在测适配层"而不是"在测理解层"。
 *
 * <p>★ 与 {@link cn.lincoln.aiplatform.retrieval.application.HopDeciderAdapter} 同一形状， 理由见 {@link
 * QueryUnderstandingPort} 的类注释。
 */
@Component
public class QueryUnderstandingAdapter implements QueryUnderstandingPort {

  private final QueryUnderstandingService delegate;

  public QueryUnderstandingAdapter(QueryUnderstandingService delegate) {
    this.delegate = delegate;
  }

  @Override
  public Result understand(
      String question,
      int queryCount,
      boolean decomposeEnabled,
      int maxSubQuestions,
      long budgetMs,
      String recentContext) {
    QueryUnderstandingService.Outcome outcome =
        delegate.understand(
            question, queryCount, decomposeEnabled, maxSubQuestions, budgetMs, recentContext);
    return new Result(
        map(outcome.understanding()),
        outcome.degradeLevel(),
        outcome.degradeReason(),
        outcome.elapsedMs());
  }

  private static List<SubQuestion> map(QueryUnderstanding understanding) {
    if (understanding == null) {
      return List.of();
    }
    return understanding.subQuestions().stream()
        .map(it -> new SubQuestion(it.question(), it.queries()))
        .toList();
  }
}
