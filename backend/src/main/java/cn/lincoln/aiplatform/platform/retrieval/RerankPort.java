package cn.lincoln.aiplatform.platform.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 重排端口：把一次检索的候选按"与问题的相关性"重新排序（cross-encoder）。
 *
 * <h3>★ 为什么只要前 {@code topK} 个（离线实测，不许照直觉改成全量）</h3>
 *
 * <p>{@code BAAI/bge-reranker-base} 的离线评测（同一份语料、同一批问题）：
 *
 * <ul>
 *   <li><b>只重排前 5 个</b>：B3 引用准确率 <b>57.9% → 68.2%（+10.3 点）</b>，变好 45 条 / 变差 22 条，单条耗时 <b>2.4s（p95
 *       3.1s）</b>；
 *   <li><b>全量重排 20 个</b>：只有 +9.8 点，单条 <b>11.8s</b> —— 花 5 倍时间换更少收益。
 * </ul>
 *
 * <p>⇒ 窗口就是"前 {@code topK} 个"，默认 5。窗口外的候选按原序跟着走： 重排改变的是"谁排最前面"（送进答案的那几条），不是"淘汰谁"。
 *
 * <h3>契约（实现必须满足）</h3>
 *
 * <ol>
 *   <li><b>返回值是入参 {@code candidateIds} 的一个排列</b>：不丢、不增、不改 id；
 *   <li><b>只重排前 {@code topK} 个</b>（窗口 = {@code min(topK, candidateIds.size())}），窗口外保持原序；
 *   <li><b>失败一律降级</b>：模型文件缺失 / 初始化失败 / 推理异常 ⇒ 返回<b>原序</b>，绝不抛异常 —— 检索不因重排坏掉而不可用
 *       （与向量那一路"绝不静默回退"的口径相反：重排是<b>增益件</b>，向量是<b>命脉件</b>）；
 *   <li>同分候选保持原序（稳定排序）。
 * </ol>
 *
 * <h3>装配</h3>
 *
 * <p>消费方（{@code RetrievalService}）只能依赖本接口，不能依赖实现类（{@code #293}：模块边界）。 兜底实现是 {@link
 * NoopRerankerAdapter}（永远返回原序）；测试装配用 {@link #noop()}。
 */
public interface RerankPort {

  /**
   * 恒返回原序的兜底实现（无状态单例）。
   *
   * <p>用途：① 测试里给 {@code RetrievalService} 一个"重排什么都没做"的依赖； ② 实现的降级路径显式委托给它，让"降级 = noop"这句话在代码里看得见
   * （防"配了没用"，{@code #283} 的头号病）。
   *
   * @return 一个永远返回原序的 {@link RerankPort}
   */
  static RerankPort noop() {
    return NoopRerankerAdapter.INSTANCE;
  }

  /**
   * 把候选按相关性重排。
   *
   * @param query 原始问题（用户问的那句，不是改写后的）
   * @param candidateIds 候选分段 id，<b>已按融合分数从高到低排好</b>（RRF 配额合并的输出次序）
   * @param texts segmentId → 正文；<b>可能缺项</b>（并发删除等），缺的按空串处理
   * @param topK 只重排前 topK 个（见类注释的离线读数）
   * @return 重排后的完整列表（{@code candidateIds} 的排列）；任何失败返回原序
   */
  List<UUID> rerank(String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK);
}
