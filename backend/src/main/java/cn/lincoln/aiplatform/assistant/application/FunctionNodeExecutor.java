package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 函数节点：直接写函数名调一次内置函数，把结果写进流程变量。
 *
 * <h3>为什么只有一个函数节点</h3>
 *
 * <p>此前还有过一个 {@code FUNCTION_LIB_NODE}（「函数库」），配置三格、输出变量、执行核心与这里完全相同 ——
 * 连面板都是同一个组件。早期设计里两者的差别是「从列表里挑」与「直接写名字」，而面板上的下拉本来就带 {@code allow-create}，一个控件两种用法都支持。于是那个类型在 0.9.2
 * 被合并掉（见 {@code ROADMAP §11.44.7}）。
 *
 * <p>★ 「函数库」这个名字留给 {@code 0.9.0} 的脚本沙箱 —— 那个能力执行<strong>用户上传的代码</strong>，与这里调 内置纯函数是两件事（见 {@link
 * FunctionInvocation}）。
 */
@Component
public class FunctionNodeExecutor implements NodeExecutor {

  private static final String NODE_NAME = "函数节点";

  private final ObjectProvider<FunctionInvokePort> invoker;

  public FunctionNodeExecutor(ObjectProvider<FunctionInvokePort> invoker) {
    this.invoker = invoker;
  }

  @Override
  public String type() {
    return "FUNCTION_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    FunctionInvokePort port = invoker.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("函数调用能力还没装配：没有 FunctionInvokePort 的实现");
    }
    FunctionInvocation.run(context, NODE_NAME, port);
  }
}
