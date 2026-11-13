package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 函数节点。
 *
 * <p>它和 {@link FunctionLibNodeExecutor} 执行同一段核心（{@link FunctionInvocation}）， 所以这里不重抄参数解析的每一条 ——
 * 那会成为"同一件事两处各写一份"。 这里只钉三件<strong>这两者确实不同</strong>的事：类型标识、报错文案里的节点名、以及"两个类型都在"。
 */
class FunctionNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final class FixedPort implements FunctionInvokePort {
    @Override
    public Set<String> functionNames() {
      return Set.of("upper");
    }

    @Override
    public String invoke(String functionName, List<String> args) {
      return "大写:" + args.get(0);
    }
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(EXECUTION_ID, "n-func", "FUNCTION_NODE", "入口输入", state, config);
  }

  @Test
  @DisplayName("类型标识是 FUNCTION_NODE —— 与函数库节点是两个类型，旧图里两种都可能存在")
  void hasItsOwnType() {
    assertThat(new FunctionNodeExecutor(providerOf(new FixedPort())).type())
        .isEqualTo("FUNCTION_NODE");
    assertThat(new FunctionLibNodeExecutor(providerOf(new FixedPort())).type())
        .isEqualTo("FUNCTION_LIB_NODE");
  }

  @Test
  @DisplayName("直接写函数名就能跑，结果写进 function_result")
  void runsTheNamedFunction() {
    FlowState state = new FlowState();

    new FunctionNodeExecutor(providerOf(new FixedPort()))
        .execute(
            context(
                state, Map.of("functionName", "upper", "args", List.of(Map.of("value", "abc")))));

    assertThat(state.read("function_result")).isEqualTo("大写:abc");
  }

  @Test
  @DisplayName("报错文案里写「函数节点」而不是「函数库节点」—— 节点记录要能指回用户配置的那一格")
  void failureMentionsItsOwnName() {
    assertThatThrownBy(
            () ->
                new FunctionNodeExecutor(providerOf(new FixedPort()))
                    .execute(context(new FlowState(), Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("函数节点没有选函数")
        .hasMessageNotContaining("函数库节点");
  }

  @Test
  @DisplayName("没有端口实现时同样说清缺的是什么能力")
  void rejectsMissingPort() {
    assertThatThrownBy(
            () ->
                new FunctionNodeExecutor(NodeExecutorTestSupport.absent())
                    .execute(context(new FlowState(), Map.of("functionName", "upper"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("函数调用能力还没装配");
  }
}
