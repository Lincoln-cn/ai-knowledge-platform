package cn.lincoln.aiplatform.assistant.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 两个函数节点共用的执行核心。
 *
 * <h3>为什么单独一个类而不是让一个执行器镜像另一个</h3>
 *
 * <p>{@code FUNCTION_LIB_NODE} 与 {@code FUNCTION_NODE} 的差别只在<strong>面板怎么挑函数</strong> （从库里选 vs
 * 直接写名字），执行这一步完全相同：解析参数 → 调端口 → 写输出。把这段抄两遍， 下一个改「参数为空时给什么」的人只会改到其中一个 —— 而这个项目已经因为"同一件事两处各写一份"
 * 吃过太多次亏（{@code #337} 整条线都在拆这个）。
 *
 * <h3>参数怎么解析</h3>
 *
 * <p>每条参数是 {@code {"from": "literal" | "variable", "value": "…"}}：
 *
 * <ul>
 *   <li>{@code literal} —— 直接把 {@code value} 当字面量用
 *   <li>{@code variable} —— 把 {@code value} 当<strong>流程变量名</strong>，从 {@code FlowState} 里取
 * </ul>
 *
 * <p>★ 不做「在字面量里插 {变量}」那种模板：那会造出第三种写法，而"这段到底取的是变量还是字面量" 恰恰是这个节点最容易配错的地方。两种来源、显式声明，面板上也就两个选项。
 *
 * <p>★ 变量不存在时按<strong>空串</strong>传（不是报错）：流程里"上一步没产出"是常见状态， 让 {@code default(...)}
 * 能兜住它才是函数的意义；真值缺失由用户在函数组合里显式处理。
 */
final class FunctionInvocation {

  static final String CONFIG_FUNCTION_NAME = WorkflowNodeKeys.Config.FUNCTION_NAME;
  static final String CONFIG_ARGS = WorkflowNodeKeys.Config.FUNCTION_ARGS;
  static final String CONFIG_OUTPUT_KEY = WorkflowNodeKeys.Config.OUTPUT_KEY;

  static final String ARG_FROM = "from";
  static final String ARG_VALUE = "value";
  static final String FROM_LITERAL = "literal";
  static final String FROM_VARIABLE = "variable";

  private FunctionInvocation() {}

  /** 函数名；空白时返回 null（由调用方决定文案）。 */
  static String functionName(Map<String, Object> config) {
    return NodeConfigSupport.text(config, CONFIG_FUNCTION_NAME);
  }

  /** 输出变量名，默认 {@code function_result}（两个节点共用，串起来不用改配置）。 */
  static String outputKey(Map<String, Object> config) {
    return NodeConfigSupport.keyOrDefault(
        config, CONFIG_OUTPUT_KEY, WorkflowNodeKeys.Output.FUNCTION_RESULT);
  }

  /**
   * 解析参数列表。
   *
   * @param state 流程状态（{@code variable} 参数从这里取值）
   * @param nodeName 写进报错文案的节点名（"函数库节点" / "函数节点"）
   */
  @SuppressWarnings("unchecked")
  static List<String> resolveArgs(WorkflowNodeContext context, String nodeName) {
    Object raw = context.nodeConfig().get(CONFIG_ARGS);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException(
          nodeName + "的参数配置不是数组 —— 每条参数要写成 {\"from\":\"literal\",\"value\":\"…\"}");
    }
    List<String> resolved = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      Object item = list.get(i);
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException(nodeName + "的第 " + (i + 1) + " 个参数不是对象");
      }
      Map<String, Object> arg = (Map<String, Object>) map;
      String from = NodeConfigSupport.text(arg, ARG_FROM);
      String value = arg.get(ARG_VALUE) == null ? "" : String.valueOf(arg.get(ARG_VALUE));
      if (from == null) {
        // 省略 from 时按字面量解释：这是最常见的一格（写个固定前缀），而把它当变量名
        // 会让「参数名打错」与「变量没产出」变成同一种症状 —— 都得到空串。
        resolved.add(value);
        continue;
      }
      String kind = from.trim().toLowerCase(java.util.Locale.ROOT);
      if (FROM_LITERAL.equals(kind)) {
        resolved.add(value);
      } else if (FROM_VARIABLE.equals(kind)) {
        if (value.isBlank()) {
          throw new IllegalArgumentException(nodeName + "的第 " + (i + 1) + " 个参数标成了变量，但没写变量名");
        }
        String got = context.state().read(value.trim());
        resolved.add(got == null ? "" : got);
      } else {
        throw new IllegalArgumentException(
            nodeName + "的第 " + (i + 1) + " 个参数的 from 只能是 literal 或 variable，实际是 " + from);
      }
    }
    return resolved;
  }

  /** 执行并把结果写进流程变量。 */
  static void run(
      WorkflowNodeContext context,
      String nodeName,
      cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort port) {
    String name = functionName(context.nodeConfig());
    if (name == null) {
      throw new IllegalStateException(nodeName + "没有选函数：在节点配置里给 " + CONFIG_FUNCTION_NAME);
    }
    List<String> args = resolveArgs(context, nodeName);
    String result;
    try {
      result = port.invoke(name, args);
    } catch (IllegalArgumentException e) {
      // ★ 端口抛的是"配置错了"，不是"运行时炸了" —— 文案前缀让节点记录里能直接看出该改哪里
      throw new IllegalStateException(nodeName + "调用「" + name + "」失败：" + e.getMessage(), e);
    }
    if (result == null) {
      throw new IllegalStateException(
          nodeName + "调用的「" + name + "」返回了 null —— 内置函数应当返回字符串（空串也算），null 是端口违约");
    }
    context.state().write(outputKey(context.nodeConfig()), result, KeyUpdate.REPLACE);
  }

  /** 供测试与面板复用的常量（避免手抄键名）。 */
  static UUID executionPlaceholder() {
    return UUID.fromString("00000000-0000-0000-0000-000000000000");
  }
}
