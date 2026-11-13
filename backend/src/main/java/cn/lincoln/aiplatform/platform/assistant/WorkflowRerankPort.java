package cn.lincoln.aiplatform.platform.assistant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流节点要对候选做重排时的端口（{@code #333} 的 {@code RERANKER_NODE}）。
 *
 * <h3>★ 为什么不直接复用 {@code RerankPort}</h3>
 *
 * <p>{@code RerankPort} 住在 {@code platform.retrieval}，而 {@code assistant} 模块的
 * {@code @ApplicationModule(allowedDependencies = {platform, identity, modelcatalog, knowledge})}
 * 里没有 {@code retrieval} —— 与 {@link WorkflowSearchPort} 同一条理由：执行器不该认识检索侧的类型， 否则"换成另一个重排实现"要从节点代码改起。
 *
 * <h3>★ 与检索链路内部那次重排是什么关系（不是重复）</h3>
 *
 * <p>{@code RetrievalService.hitTest} 里已经有一次 cross-encoder 重排，由 {@code
 * app.retrieval.rerank.enabled} 统一开关。本端口服务的是**另一件事**：让某张图**显式**声明"我要在这里重排一次、窗口是几条"。
 *
 * <ul>
 *   <li>全局开、图上没有重排节点：行为与今天完全一样（检索侧排好就送生成）；
 *   <li>全局关、图上有重排节点：只有那张图会重排 —— 这正是"关掉全局省 2.4s/条，但关键流程仍要准"的场景；
 *   <li>两个都开：节点拿到的候选已经排过一次，再排一次多数情况下顺序不变 —— **浪费但不是错**， 所以读数里要能看出"重排前后顺序是否变化"，否则用户分不清"重排生效"与"重排白跑"。
 * </ul>
 *
 * <h3>★ 为什么契约里带 {@code texts}</h3>
 *
 * <p>重排模型要读正文（文档名 + 正文拼在一起，见 {@code RetrievalService} 的同一个做法）。候选 id 与正文分开传， 是因为正文来自上游节点的输出，端口无权去库里取
 * —— 端口只做"排序"，取数是调用方的事。
 */
public interface WorkflowRerankPort {

  /**
   * 把候选按与问题的相关性重排。
   *
   * @param query 问题原文
   * @param candidateIds 候选分段 id，已按融合分数从高到低排好
   * @param texts segmentId → 正文；**可能缺项**（并发删除等），缺的按空串处理
   * @param topK 只重排前 topK 个；窗口外保持原序
   * @return 重排后的完整列表（{@code candidateIds} 的一个排列）；任何失败返回原序，绝不抛异常
   */
  List<UUID> rerank(String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK);
}
