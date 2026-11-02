package cn.lincoln.aiplatform.retrieval.application;

import cn.lincoln.aiplatform.platform.retrieval.HopDeciderPort;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把 {@link MultiHopService} 接到 {@link HopDeciderPort} 上的薄壳。
 *
 * <p>★ 为什么要有这一层（`#293`）：{@code conversation} 不能直接依赖 {@code retrieval.application} 里的实现类型（模块边界只认
 * {@code platform} 里的端口）。而 {@code MultiHopService} 本身又被 {@code RetrievalService} 用着，形状不宜为这一个消费者改动
 * —— 于是"适配"放在检索侧， 端口放在 {@code platform}。
 *
 * <p>★ 它只做类型搬运，**不加任何逻辑**：判定规则、失败处置、"不可用就用已有结果继续"全在 {@link MultiHopService} 里。★
 * 如果哪天有人想在这里加东西，先问"这是检索的决定还是问答的决定" —— 答案是后者的话，它不该出现在这里。
 */
@Component
public class HopDeciderAdapter implements HopDeciderPort {

  private final MultiHopService multiHop;

  public HopDeciderAdapter(MultiHopService multiHop) {
    this.multiHop = multiHop;
  }

  @Override
  public Result decide(String question, List<SegmentSearchPort.Card> hits, long timeoutMs) {
    MultiHopService.Outcome outcome = multiHop.decide(question, hits, timeoutMs);
    if (outcome == null || outcome.decision() == null) {
      return new Result(false, null, "多跳判定没有返回结果", outcome == null ? 0L : outcome.elapsedMs());
    }
    return new Result(
        outcome.decision().sufficient(),
        outcome.decision().nextQuery(),
        outcome.decision().failure(),
        outcome.elapsedMs());
  }
}
