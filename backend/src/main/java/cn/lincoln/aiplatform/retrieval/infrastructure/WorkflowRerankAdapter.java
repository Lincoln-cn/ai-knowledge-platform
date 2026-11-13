package cn.lincoln.aiplatform.retrieval.infrastructure;

import cn.lincoln.aiplatform.platform.assistant.WorkflowRerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link WorkflowRerankPort} 的实现 —— 端口在 {@code platform}、实现在提供方模块（{@code 08 §4.4.2}）。
 *
 * <p>★ 只有一行转发，为什么还要这个类：{@code assistant} 模块的 {@code allowedDependencies} 里没有 {@code
 * retrieval}。少这一行，重排节点就得让 {@code assistant} 认识 {@code RerankPort}， 模块边界闸门会红 ——
 * 而那道闸门守的正是"执行器不悄悄绑死某个实现"。
 *
 * <p>★ 为什么把降级策略留给 {@link RerankPort} 而不在这里包一层 try/catch：那个接口的契约已经写明"任何失败返回原序、
 * 绝不抛异常"，<b>重排是增益件、检索是命脉件</b>。在这里再包一层，等于把同一条约定写两遍—— 而两处各写一遍的约定，改一处必漏另一处。
 */
@Component
public class WorkflowRerankAdapter implements WorkflowRerankPort {

  private final RerankPort rerankPort;

  public WorkflowRerankAdapter(RerankPort rerankPort) {
    this.rerankPort = rerankPort;
  }

  @Override
  public List<UUID> rerank(
      String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
    return rerankPort.rerank(query, candidateIds, texts, topK);
  }
}
