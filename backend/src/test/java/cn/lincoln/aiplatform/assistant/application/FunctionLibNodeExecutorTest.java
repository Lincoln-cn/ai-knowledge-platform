package cn.lincoln.aiplatform.assistant.application;

import static cn.lincoln.aiplatform.assistant.application.NodeExecutorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 函数库节点。
 *
 * <p>★ 它<strong>不执行用户写的代码</strong> —— 只调用平台内置函数目录。这一组测的是 「配置怎么变成一次调用」这件事：参数来源解析、输出变量名、以及四种失败各自说什么。
 */
class FunctionLibNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /** 记下每次调用的端口替身。 */
  private static final class RecordingPort implements FunctionInvokePort {
    final List<String> calledFunctions = new ArrayList<>();
    final List<List<String>> calledArgs = new ArrayList<>();
    final Deque<String> answers = new ArrayDeque<>();

    @Override
    public Set<String> functionNames() {
      return Set.of("upper", "trim", "concat");
    }

    @Override
    public String invoke(String functionName, List<String> args) {
      calledFunctions.add(functionName);
      calledArgs.add(List.copyOf(args));
      return answers.isEmpty() ? "结果" : answers.poll();
    }
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-funclib", "FUNCTION_LIB_NODE", "入口输入", state, config);
  }

  private static Map<String, Object> config(Object... pairs) {
    Map<String, Object> map = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return map;
  }

  @Test
  @DisplayName("按配置调一次函数，结果写进 function_result")
  void runsTheConfiguredFunction() {
    RecordingPort port = new RecordingPort();
    port.answers.add("已大写");
    FlowState state = new FlowState();

    new FunctionLibNodeExecutor(providerOf(port))
        .execute(
            context(
                state, config("functionName", "upper", "args", List.of(Map.of("value", "abc")))));

    assertThat(port.calledFunctions).containsExactly("upper");
    assertThat(port.calledArgs.get(0)).containsExactly("abc");
    assertThat(state.read("function_result")).isEqualTo("已大写");
  }

  @Test
  @DisplayName("字面量与流程变量可以混着传，顺序按配置里的顺序")
  void resolvesMixedArgSources() {
    RecordingPort port = new RecordingPort();
    FlowState state = new FlowState();
    state.write("query", "问句", KeyUpdate.REPLACE);

    new FunctionLibNodeExecutor(providerOf(port))
        .execute(
            context(
                state,
                config(
                    "functionName",
                    "concat",
                    "args",
                    List.of(
                        Map.of("from", "literal", "value", "前缀-"),
                        Map.of("from", "variable", "value", "query"),
                        // 省略 from 按字面量解释
                        Map.of("value", "-后缀")))));

    assertThat(port.calledArgs.get(0)).containsExactly("前缀-", "问句", "-后缀");
  }

  @Test
  @DisplayName("变量不存在时按空串传 —— 让 default(...) 兜得住「上一步没产出」")
  void missingVariableBecomesEmptyString() {
    RecordingPort port = new RecordingPort();
    FlowState state = new FlowState();

    new FunctionLibNodeExecutor(providerOf(port))
        .execute(
            context(
                state,
                config(
                    "functionName",
                    "concat",
                    "args",
                    List.of(Map.of("from", "variable", "value", "从来没有过的变量")))));

    assertThat(port.calledArgs.get(0)).containsExactly("");
  }

  @Test
  @DisplayName("outputKey 能把结果写到别的变量上，默认仍是 function_result")
  void outputKeyIsConfigurable() {
    RecordingPort port = new RecordingPort();
    port.answers.add("写到别处");
    FlowState state = new FlowState();

    new FunctionLibNodeExecutor(providerOf(port))
        .execute(context(state, config("functionName", "trim", "outputKey", "my_result")));

    assertThat(state.read("my_result")).isEqualTo("写到别处");
    assertThat(state.read("function_result")).isNull();
  }

  @Test
  @DisplayName("没选函数时说清该填哪个键")
  void rejectsMissingFunctionName() {
    FlowState state = new FlowState();

    assertThatThrownBy(
            () ->
                new FunctionLibNodeExecutor(providerOf(new RecordingPort()))
                    .execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("函数库节点没有选函数")
        .hasMessageContaining("functionName");
    assertThat(state.asMap()).doesNotContainKey("function_result");
  }

  @Test
  @DisplayName("端口抛 IllegalArgumentException 时，文案带上节点名与函数名 —— 节点记录里要能看出该改哪里")
  void wrapsPortFailure() {
    FunctionInvokePort port =
        new FunctionInvokePort() {
          @Override
          public Set<String> functionNames() {
            return Set.of("upper");
          }

          @Override
          public String invoke(String functionName, List<String> args) {
            throw new IllegalArgumentException("upper 要 1 个参数，实际给了 2 个");
          }
        };

    assertThatThrownBy(
            () ->
                new FunctionLibNodeExecutor(providerOf(port))
                    .execute(context(new FlowState(), config("functionName", "upper"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("函数库节点调用「upper」失败")
        .hasMessageContaining("实际给了 2 个");
  }

  @Test
  @DisplayName("端口返回 null 是违约 —— 内置函数应当返回字符串（空串也算）")
  void rejectsNullResult() {
    FunctionInvokePort port =
        new FunctionInvokePort() {
          @Override
          public Set<String> functionNames() {
            return Set.of("upper");
          }

          @Override
          public String invoke(String functionName, List<String> args) {
            return null;
          }
        };

    assertThatThrownBy(
            () ->
                new FunctionLibNodeExecutor(providerOf(port))
                    .execute(context(new FlowState(), config("functionName", "upper"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("返回了 null")
        .hasMessageContaining("端口违约");
  }

  @Test
  @DisplayName("没有端口实现时说清缺的是什么能力")
  void rejectsMissingPort() {
    assertThatThrownBy(
            () ->
                new FunctionLibNodeExecutor(NodeExecutorTestSupport.absent())
                    .execute(context(new FlowState(), config("functionName", "upper"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("函数调用能力还没装配")
        .hasMessageContaining("FunctionInvokePort");
  }

  @Test
  @DisplayName("标成变量却没写变量名时要报错 —— 否则会静默传一个空串，看起来像「那个变量是空的」")
  void rejectsVariableWithoutName() {
    RecordingPort port = new RecordingPort();

    assertThatThrownBy(
            () ->
                new FunctionLibNodeExecutor(providerOf(port))
                    .execute(
                        context(
                            new FlowState(),
                            config(
                                "functionName",
                                "concat",
                                "args",
                                List.of(Map.of("from", "variable", "value", "  "))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("标成了变量，但没写变量名");
  }
}
