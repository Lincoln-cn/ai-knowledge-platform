package cn.lincoln.aiplatform.assistant.application;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 人工介入：追问节点。
 *
 * <p>★ 这个执行器<b>通常不会被调用</b> —— {@link WorkflowCompiler#isInterrupt(String)} 把 {@code QUESTION_NODE}
 * 无条件算成中断点，执行器跑到它之前就抛 {@link WorkflowInterrupted} 停住，等用户答复后由 {@code WorkflowResumeService} 把答复写进
 * {@code query} 键、从下一步接着跑。
 *
 * <p>那为什么还要它？两个理由：
 *
 * <ol>
 *   <li>{@link LinearWorkflowExecutor#SUPPORTED_TYPES} 里的每个类型都必须有执行器，那是「发布放行 ⇒ 真能跑」这条 不变式的载体（{@code
 *       AiChatNodeExecutorTest.everySupportedTypeHasAnExecutor} 钉着）。漏一个，就会出现 「点发布通过、点试用炸」。
 *   <li>将来若把「不许自动中断」做成配置（例如定时触发场景下追问节点直接取默认值往下走），这个节点要有个能透传 的实现，而不是到时候再从零写一个。
 * </ol>
 *
 * <p>★ 它<b>不</b>做的事：不调模型（追问的语义是「问用户」，不是「问模型」）、不落库、不改流程形态。
 */
@Component
public class QuestionNodeExecutor implements NodeExecutor {

  /** 追问的答复落在哪个键。与 {@code WorkflowResumeService.answerKey} 必须是同一个名字。 */
  public static final String QUERY_KEY = "query";

  /** 配置里可以带一个默认答复，用于非人工场景（定时触发）直接往下走。 */
  static final String CONFIG_DEFAULT_ANSWER = "default_answer";

  @Override
  public String type() {
    return "QUESTION_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    String answer = context.input();
    if (answer == null || answer.isBlank()) {
      Object configured =
          context.nodeConfig() == null ? null : context.nodeConfig().get(CONFIG_DEFAULT_ANSWER);
      answer = configured == null ? "" : configured.toString();
    }
    // ★ 就算是空串也写进去：下游节点读 query 时，「键不存在」与「键在但为空」的处置不同
    //   （前者会被当成「上一步没跑」，后者是「用户没答」）。
    context.state().write(QUERY_KEY, answer, KeyUpdate.REPLACE);
  }

  /** 供测试与文档引用：这个节点只碰一个键。 */
  static List<String> touchedKeys() {
    return List.of(QUERY_KEY);
  }

  /** 兜底：配置读不通时按空处理，而不是抛错 —— 见 {@link #execute} 的注释。 */
  static Map<String, Object> safeConfig(Map<String, Object> config) {
    return config == null ? Map.of() : config;
  }
}
