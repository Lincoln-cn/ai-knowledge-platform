package cn.lincoln.aiplatform.platform.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用的跨模块端口（0.8.3 占位 · {@code ROADMAP §11.45.4}）。
 *
 * <p>★ <strong>今天唯一允许的实现是"没有工具"</strong>。立这个接口不是为了让人马上实现它，
 * 而是为了让"工具调用"这件事有一个<strong>可被断言的位置</strong>：
 *
 * <ul>
 *   <li>没有接口时，"今天没有工具调用"是一句<strong>无法证伪</strong>的话 —— 它只是在描述"没人写过这行代码"；
 *   <li>有了接口之后，"没有工具调用"就变成一个<strong>可读的读数</strong>： {@link #toolsFor} 返回空 ⇒ 上游请求体里不会出现 <code>tools
 *       </code> 键。
 * </ul>
 *
 * <p>★ 真正的工具执行（能起沙箱、能杀超时、能记调用审计）排在 0.9.0 的沙箱之后， 见 {@link
 * cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode} 的 javadoc： 能力位（{@code UNSUPPORTED} / {@code
 * SUPPORTED} / {@code PARALLEL}）早就在模型实体上， 但没有代码把它变成请求参数 ——
 * 缺的不是声明，是<strong>谁来给出工具清单</strong>与<strong>谁来执行</strong>。
 *
 * <p>★ 本接口只回答前者。后者（执行）需要 {@code ToolCallExecutor} 与一次真实的多轮往返， 不在 0.8.3 的范围里；把"能声明"与"能执行"分开立，是为了避免出现
 * "模型发出了一个 tool_calls、平台回一句『不支持』"这种<strong>看起来能用、实际不能用</strong>的半成品。
 */
public interface ToolProvider {

  /**
   * 这次对话能提供给模型的工具清单。
   *
   * <p>★ 空表表示<strong>不启用工具调用</strong>，而不是"这个模型没有工具"。两者的区别在请求体上： 空表时上游请求里<strong>连 {@code tools}
   * 键都不出现</strong>（不是空数组）—— 有些上游把"出现 tools 键"当成"客户端支持工具调用"，给个空数组会让它走进另一条分支。
   *
   * @param modelId 这次对话用的模型（同一份工具清单可能按模型能力不同；今天恒为空，参数先留着）
   */
  List<ToolSpec> toolsFor(UUID modelId);

  /**
   * 一个工具的形状：名字、说明、参数 schema。
   *
   * <p>字段与 OpenAI 兼容协议的 {@code tools[].function} 一一对应，但这里<strong>不叫 function</strong> —— 本项目的
   * {@code FUNCTION_NODE} 指的是"平台内置函数"，两者同名会让"函数"这个词在一句话里指两个东西。
   */
  record ToolSpec(String name, String description, Map<String, Object> parameters) {}
}
