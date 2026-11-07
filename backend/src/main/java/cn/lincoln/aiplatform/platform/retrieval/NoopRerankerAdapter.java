package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 恒返回原序的重排兜底实现。
 *
 * <p>它不是"没接上"的占位，而是降级语义的<strong>正式载体</strong>： {@link RerankPort} 的契约规定"失败一律降级返回原序"，重排实现的降级路径就显式委托给
 * {@link #INSTANCE}， 让"降级 = noop"在代码里看得见（防"配了没用"，{@code #283}）。
 *
 * <p>★ 为什么不用 {@code @ConditionalOnMissingBean} 兜底： 实测它写在组件类上判定时机不对，bean 根本不会注册（见 {@code
 * ChatPortFallbackStreamAdapter} 的类注释）—— 兜底走"普通类 + 单例"，装配走单 bean（{@code OnnxRerankerAdapter}）。
 */
public final class NoopRerankerAdapter implements RerankPort {

  /** 进程内单例：无状态、可安全共享。 */
  public static final NoopRerankerAdapter INSTANCE = new NoopRerankerAdapter();

  private NoopRerankerAdapter() {}

  /**
   * 什么都不做：原样返回入参列表（同一份引用，让调用方"看得见没重排过"）。
   *
   * @param query 忽略
   * @param candidateIds 候选，原样返回
   * @param texts 忽略
   * @param topK 忽略
   * @return 与入参同一份列表
   */
  @Override
  public List<UUID> rerank(
      String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
    return candidateIds;
  }
}
