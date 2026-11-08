package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 不排的兜底实现：永远返回原序（无状态单例）。
 *
 * <p>两种用途：① listwise 关掉时（默认）让"关 = 什么都不做"在代码里看得见； ② 实现类在失败路径显式委托给它。
 *
 * @see ListwiseRerankPort
 */
final class NoopListwiseReranker implements ListwiseRerankPort {

  static final NoopListwiseReranker INSTANCE = new NoopListwiseReranker();

  private NoopListwiseReranker() {}

  @Override
  public List<UUID> rerank(
      String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
    return List.copyOf(candidateIds);
  }
}
