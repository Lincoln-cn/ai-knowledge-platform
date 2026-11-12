package cn.lincoln.aiplatform.assistant.application;

import org.springframework.stereotype.Component;

/**
 * 人工介入：表单节点。
 *
 * <p>★ 与 {@link QuestionNodeExecutor} 同族，<b>通常也不会被调用</b> —— {@link
 * WorkflowCompiler#isInterrupt(String)} 把 {@code FORM_NODE} 无条件算成中断点，执行器跑到它之前就抛 {@link
 * WorkflowInterrupted} 停住，等用户填完由 {@code WorkflowResumeService} 把表单内容写进 {@code form_data} 键、从下一步接着跑。
 *
 * <p>存在的理由与追问节点完全一样（见 {@link QuestionNodeExecutor} 的类注释）：{@link
 * LinearWorkflowExecutor#SUPPORTED_TYPES} 里的每个类型都必须有执行器，否则会出现「点发布通过、点试用炸」。
 *
 * <p>★ 它<b>不</b>做的事：不校验表单字段（校验在恢复端点那一侧做，那里才知道用户实际提交了什么）、不落库、不改流程形态。
 */
@Component
public class FormNodeExecutor implements NodeExecutor {

  /** 表单内容落在哪个键。与 {@code WorkflowResumeService.answerKey} 必须是同一个名字。 */
  public static final String FORM_DATA_KEY = "form_data";

  @Override
  public String type() {
    return "FORM_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    String answer = context.input();
    // ★ 与追问节点同样的判断：就算空串也写进去。
    //   「键不存在」会被下游当成「上一步没跑」，「键在但为空」才是「用户没填」。
    context.state().write(FORM_DATA_KEY, answer == null ? "" : answer, KeyUpdate.REPLACE);
  }
}
