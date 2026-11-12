package cn.lincoln.aiplatform.assistant.application;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基本信息收集节点（{@code BASE_NODE}）：把用户在入口提供的基本信息写进共享状态，供后续节点引用。
 *
 * <h3>★ 这一版做的是什么，没做的是什么</h3>
 *
 * <p>画布上这个节点配的是「要收集哪些字段」（{@code BaseNodeConfig.fields}）。一个完整实现应当在流程跑到这里时
 * <strong>停下来等人填表</strong>，再继续。而执行器今天<strong>没有实现中断</strong> —— {@code
 * ExecutionPlan.interruptNodeIds} 这个字段一直存在，但没人读它。
 *
 * <p>所以这一版只做<b>不改流程形态</b>的那半：把<strong>本次执行已有的输入</strong>写进状态， 并把这次配置的字段清单也记下来（字段名 + 类型），供下游节点的
 * {@code {{变量}}} 引用与 后续的中断实现接管。
 *
 * <p>★ 为什么不"假装中断"（执行到这儿就返回、让人以为流程停了）：那样运行记录里会出现一条 「SUCCEEDED 但什么都没做」的节点，而用户看到的是流程卡住 —— 比明确失败更难查。
 * 也不抛「还没实现」：这一节点在单入口的图里是<b>可用的</b>（把入口输入登记下来）， 直接拒绝会让一张本来就跑得通的三节点图发布不了。
 *
 * <h3>写入的键</h3>
 *
 * <ul>
 *   <li>{@value #FIELDS_KEY}：本次配置的字段名，逗号分隔 —— 下游要引用就必须知道有哪些；
 *   <li>{@value #INPUT_KEY}：用户这次提供的原始输入（{@code context.input()}，可能为空串）。
 * </ul>
 */
@Component
public class BaseNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(BaseNodeExecutor.class);

  /** 配置的字段名（逗号分隔）写进状态的键。 */
  public static final String FIELDS_KEY = "basic_info_fields";

  /** 用户这次提供的基本信息写进状态的键。 */
  public static final String INPUT_KEY = "basic_info_input";

  @Override
  public String type() {
    return "BASE_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    List<String> fields = fieldNames(context.nodeConfig());
    String input = context.input() == null ? "" : context.input();

    context.state().write(FIELDS_KEY, String.join(",", fields), KeyUpdate.REPLACE);
    context.state().write(INPUT_KEY, input, KeyUpdate.REPLACE);

    if (fields.isEmpty()) {
      // ★ 不抛错，但一定留痕：没配字段不等于这个节点没用（它仍然登记了入口输入），
      //   可是多半是配置漏了 —— 静默通过会让"配了字段"与"没配"读数一样。
      log.warn("#workflow 基本信息节点没有配置任何字段（节点 {}）：只登记了入口输入", context.nodeId());
    } else {
      log.debug("#workflow 基本信息节点登记 {} 个字段，输入长度 {}", fields.size(), input.length());
    }
  }

  /** 从 {@code BaseNodeConfig.fields} 里取字段名；兼容 {@code List<Map>}（Jackson）与 {@code List<String>}。 */
  @SuppressWarnings("unchecked")
  static List<String> fieldNames(Map<String, Object> config) {
    if (config == null) {
      return List.of();
    }
    Object raw = config.get("fields");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .map(
            item -> {
              if (item instanceof String s) {
                return s;
              }
              if (item instanceof Map<?, ?> m) {
                Object name = ((Map<String, Object>) m).get("name");
                return name == null ? "" : String.valueOf(name);
              }
              return "";
            })
        .filter(s -> !s.isBlank())
        .toList();
  }
}
