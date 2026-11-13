package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 函数库节点：从平台内置函数目录里挑一个函数，按配置的参数跑一次，把结果写进流程变量。
 *
 * <h3>它不执行用户写的代码</h3>
 *
 * <p>"函数库"这个名字在设计册里指的是<strong>执行用户上传脚本的沙箱</strong>，排在 {@code ROADMAP §11.27.3} 的
 * 0.9.0，判据是「沙箱能起、超时能杀」。那个东西需要独立的安全边界 （进程隔离、资源上限、依赖镜像），不在本版本。
 *
 * <p>所以本节点调用的是 {@link FunctionInvokePort} 背后的<strong>内置函数目录</strong> （{@code upper} / {@code trim}
 * / {@code substring} / {@code jsonpath} 这类纯函数）。 这样做而不是"看起来能跑代码、实际跑不了"，是因为后者会把 {@code
 * silent-failure} 装进产品里： 用户填了一段脚本、节点显示成功、结果却什么都没做。
 *
 * <p>与 {@link FunctionNodeExecutor} 的差别只在面板怎么挑函数（这里从列表选，那里直接写名字）， 执行这一步完全共用 {@link
 * FunctionInvocation}。
 */
@Component
public class FunctionLibNodeExecutor implements NodeExecutor {

  private static final String NODE_NAME = "函数库节点";

  private final ObjectProvider<FunctionInvokePort> invoker;

  public FunctionLibNodeExecutor(ObjectProvider<FunctionInvokePort> invoker) {
    this.invoker = invoker;
  }

  @Override
  public String type() {
    return "FUNCTION_LIB_NODE";
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
