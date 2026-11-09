package cn.lincoln.aiplatform.platform.assistant;

import java.util.List;
import java.util.UUID;

/**
 * 工作流节点要检索知识库时的端口（{@code #326}）。
 *
 * <p>★ 为什么要有这个端口，而不是让 {@code SearchDatasetNodeExecutor} 直接调检索模块： {@code assistant} 模块的
 * {@code @ApplicationModule(allowedDependencies = {platform, identity, modelcatalog, knowledge})}
 * 里**没有 retrieval**。节点执行器要检索，只能经过 {@code platform} 这一层 —— 否则模块边界闸门会红，而那道闸门守的正是"执行器不悄悄绑死某个实现"。
 *
 * <p>★ 为什么方法只收**一个** {@code kbId}：一个检索节点可以配多个知识库，但"多个库怎么合并成一份排名"是检索侧的算法 （{@code RrfQuotaJoiner}
 * 那一层），不该让每个节点各写一份。节点负责"调几次、按什么顺序拼"，端口负责"一个库怎么查"。
 *
 * <p>★ 为什么**不给** {@code noop()} 静态兜底、也不给方法 default 实现：检索是执行器唯一有意义的输出。一个永远返回空列表的
 * 兜底会让"检索没命中"和"检索能力根本没装配"读数完全一样 —— 那正是本项目头号病（静默失败）的形状。 装配缺失时应当**明确失败**，见 {@code
 * SearchDatasetNodeExecutor} 的 {@code ObjectProvider} 用法。
 */
public interface WorkflowSearchPort {

  /**
   * 一段命中的正文。
   *
   * @param docName 文档名（给引用显示用；检索侧应保证不为 {@code null}，缺失时给空串）
   * @param content 正文
   */
  record Snippet(String docName, String content) {}

  /**
   * 在一个知识库里检索。
   *
   * @param kbId 知识库 id（单个）
   * @param query 检索词
   * @param topN 取前几条
   * @return 命中的正文；**没有命中返回空列表，不返回 {@code null}**
   * @throws RuntimeException 检索本身失败（网络、库不存在等）—— 让调用方按节点失败处理，不要吞成空列表
   */
  List<Snippet> search(UUID kbId, String query, int topN);
}
