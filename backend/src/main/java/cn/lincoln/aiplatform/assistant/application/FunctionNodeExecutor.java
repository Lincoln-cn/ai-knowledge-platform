package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 函数节点：直接写函数名调一次内置函数，把结果写进流程变量。
 *
 * <h3>与 {@link FunctionLibNodeExecutor} 的关系</h3>
 *
 * <p>两者执行完全相同，差别在面板形态与使用场合：
 *
 * <ul>
 *   <li>{@code FUNCTION_LIB_NODE} —— 面板从列表里挑，适合"我知道有个函数叫 trim，但不想背参数顺序"
 *   <li>{@code FUNCTION_NODE} —— 面板直接写名字，适合"这条流程是从别处导进来的，函数名已经写死了"
 * </ul>
 *
 * <p>为什么保留两个类型而不是合并：设计册与画布的节点目录把两者列为不同组件，且用户已有的草稿里 两种都可能存在。合并成一种会让旧图里的另一种类型变成"未知节点"。所以执行逻辑共用
 * （{@link FunctionInvocation}），类型标识保持两个。
 *
 * <p>★ 同样不执行用户写的代码 —— 见 {@link FunctionLibNodeExecutor} 的说明。
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
