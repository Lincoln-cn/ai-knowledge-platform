package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM listwise 重排端口：在 cross-encoder（{@link RerankPort}）之后，再让<strong>对话模型</strong>把前几个候选 排一次。
 *
 * <h3>★ 为什么它是独立端口，而不是 {@link RerankPort} 的另一种实现</h3>
 *
 * <p>两者<strong>串联</strong>而不是<strong>二选一</strong>：cross-encoder 负责"从 10 个里挑出最有希望的 5 个"， listwise
 * 负责"在这 5 个里再做一次语义判断"。装成同一个端口的两个实现，就只能二选一， 而这正是本条要避免的（用户 2026-11-08：「既然已经做了，就做成配置」）。
 *
 * <h3>★★ 默认关（用户 2026-11-08 拍板）</h3>
 *
 * <p>20 条试跑：B3 65.00% → <b>70.00%（+5.00 点）</b>，变好 2 / 变差 1 / 不变 17，但 listwise 单条 <b>3.12s</b>（p95
 * 11.69s）⇒ 叠加 cross-encoder 的 8.5s 后单跳 11.6s，<b>破 B4 的 10s</b>。
 *
 * <p>⇒ 它是<strong>增益件</strong>：低配机器上装不下，好机器上应当打开。用户原话：「本机能力不行， 但是部署到更高配置的服务器上，这些延迟应该可以下压」。
 *
 * <h3>契约</h3>
 *
 * <ol>
 *   <li>返回入参 {@code candidateIds} 的一个排列：不丢、不增、不改 id；
 *   <li>只排前 {@code topK} 个，窗口外保持原序；
 *   <li><b>失败一律降级</b>：没有可用对话模型 / 调用超时 / 解析不出来 ⇒ 返回<b>原序</b>，绝不抛异常 （与 {@link RerankPort}
 *       同一条口径：这是增益件，不是命脉件）；
 *   <li>同分保持原序（稳定）。
 * </ol>
 */
public interface ListwiseRerankPort {

  /**
   * 恒返回原序的兜底实现。
   *
   * @return 一个永远返回原序的 {@link ListwiseRerankPort}
   */
  static ListwiseRerankPort noop() {
    return NoopListwiseReranker.INSTANCE;
  }

  /**
   * 用对话模型对前 {@code topK} 个候选做 listwise 排序。
   *
   * @param query 原始问题
   * @param candidateIds 候选分段 id，已按上游分数从高到低排好
   * @param texts segmentId → 正文；可能缺项，缺的按空串处理
   * @param topK 只排前 topK 个
   * @return 排好的完整列表（{@code candidateIds} 的排列）；任何失败返回原序
   */
  List<UUID> rerank(String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK);
}
