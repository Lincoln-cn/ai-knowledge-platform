package cn.lincoln.aiplatform.assistant.application;

import org.springframework.stereotype.Component;

/**
 * {@code START_NODE} 的执行器（{@code #326} 补的第一个真执行器）。
 *
 * <p>★ 职责只有一件：把本次执行的用户输入写进共享 {@link FlowState} 的 {@value #QUERY_KEY} 键， 让后续节点（检索 / 对话 /
 * 回复）有一个约定的入口可读。
 *
 * <p>★ <strong>为什么零依赖</strong>：{@code assistant} 的 {@code allowedDependencies} 只有 {@code platform /
 * identity / modelcatalog / knowledge}，检索在 {@code retrieval}、对话在 {@code conversation}，
 * 都不在允许面内。所以本执行器只做"搬运"，不碰任何跨模块服务 —— 补 {@code SEARCH_DATASET_NODE} / {@code AI_CHAT_NODE} / {@code
 * REPLY_NODE} 时必须走 {@code platform} 端口（同族先例：{@code #293} 的 {@code HopDeciderPort}）。
 *
 * <p>★ 输入为 {@code null} 或空白时**写空串而不是跳过**：{@code state.read("query")} 返回 {@code null} 与返回 {@code ""}
 * 会让下游写出两种不同的错误信息，而"用户没输入"只有一种情况。
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

  /** 用户提问在 {@link FlowState} 里的键名。下游节点读它，不要再各自定义一份。 */
  public static final String QUERY_KEY = WorkflowNodeKeys.Output.QUERY;

  @Override
  public String type() {
    return "START_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext ctx) {
    String input = ctx.input() == null ? "" : ctx.input();
    ctx.state().write(QUERY_KEY, input, KeyUpdate.REPLACE);
  }
}
