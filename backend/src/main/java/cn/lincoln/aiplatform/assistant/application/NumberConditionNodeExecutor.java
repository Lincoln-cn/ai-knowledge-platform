package cn.lincoln.aiplatform.assistant.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数值判断节点（{@code NUMBER_CONDITION_NODE}）：按「变量 / 操作符 / 值」结构化比较，<strong>不经过模型</strong>。
 *
 * <h3>与 {@link ConditionNodeExecutor} 的分工</h3>
 *
 * <p>那个读一句自然语言条件、让模型判定走哪条；这个只做数值与字符串的结构化比较。 两者配置形状不同（那个是 {@code branches[].expression}，这个是 {@code
 * branches[].conditions[]}）， 但<strong>写进状态的键是同一个</strong>（{@link
 * LinearWorkflowExecutor#ACTIVE_BRANCH}）， 因为执行器只认「本次走哪条分支」，不关心这条分支是模型选的还是算出来的。
 *
 * <h3>★ 变量寻址的现状（如实记录）</h3>
 *
 * <p>契约里 {@code variable} 是 {@code [节点id, 字段名]} 两级形式，而 {@link FlowState} 今天是 <strong>扁平</strong>的
 * {@code Map<String,String>}，节点输出按<strong>字段名</strong>存（ {@code query} / {@code retrieval_result} /
 * {@code answer} / {@code reply}），没有按节点 id 分区。 所以这里只取 {@code variable[1]}；{@code variable[0]}
 * 暂不参与寻址。
 *
 * <p>★ 这不是"忘了实现"，是加命名空间要先改 {@code FlowState} 的读写契约（每个写方都要多带一个节点 id）， 那是一次跨所有执行器的改动，不塞进这个节点里顺手做。
 */
@Component
public class NumberConditionNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(NumberConditionNodeExecutor.class);

  /** 分支列表在节点配置里的键，与契约 {@code NumberConditionNodeConfig.branches} 同名。 */
  static final String CONFIG_BRANCHES = "branches";

  /** 兜底分支 id 在节点配置里的键。 */
  static final String CONFIG_FALLBACK = "fallbackBranchId";

  @Override
  public String type() {
    return "NUMBER_CONDITION_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    Map<String, Object> config = context.nodeConfig();
    List<Branch> branches = branchesOf(config);
    if (branches.isEmpty()) {
      throw new IllegalStateException("数值判断节点没有任何分支");
    }

    FlowState state = context.state();
    for (Branch branch : branches) {
      if (matches(branch, state)) {
        state.write(LinearWorkflowExecutor.ACTIVE_BRANCH, branch.id(), KeyUpdate.REPLACE);
        log.debug("#workflow 数值判断命中分支 {}（节点 {}）", branch.id(), context.nodeId());
        return;
      }
    }

    String fallback = fallbackOf(config);
    if (fallback != null) {
      state.write(LinearWorkflowExecutor.ACTIVE_BRANCH, fallback, KeyUpdate.REPLACE);
      log.debug("#workflow 数值判断无一命中，走兜底分支 {}", fallback);
      return;
    }
    // ★ 不猜：没有兜底就失败。走错分支会让后续节点对着错误的上下文跑，比直接失败更难查。
    throw new IllegalStateException("数值判断节点没有命中任何分支，且没有配置兜底分支");
  }

  /** 一个分支：组内条件按 {@code logic} 组合（{@code AND} = 全部命中，{@code OR} = 任一命中）。 */
  private boolean matches(Branch branch, FlowState state) {
    if (branch.conditions().isEmpty()) {
      return false;
    }
    boolean or = "OR".equalsIgnoreCase(branch.logic());
    boolean any = false;
    for (Condition c : branch.conditions()) {
      boolean hit = single(c, state);
      if (or && hit) {
        return true;
      }
      if (!or && !hit) {
        return false;
      }
      any = any || hit;
    }
    return or ? any : true;
  }

  /**
   * 单条条件判定。
   *
   * <p>★ 数值比较在<strong>两边都解析得出数字</strong>时才做数值比较，否则退回字符串比较 —— 「三」与「3」这种，把前者当 0 处理会得出完全相反的结论。
   */
  static boolean single(Condition c, FlowState state) {
    String actual =
        c.variable() == null || c.variable().length < 2 ? null : state.read(c.variable()[1]);
    String raw = c.value();

    return switch (c.operator()) {
      case "IS_NULL" -> actual == null || actual.isBlank();
      case "IS_NOT_NULL" -> actual != null && !actual.isBlank();
      case "CONTAIN" -> actual != null && raw != null && actual.contains(raw);
      case "NOT_CONTAIN" -> actual == null || raw == null || !actual.contains(raw);
      case "LEN_EQ" -> lengthOf(actual) == lengthBound(raw);
      case "LEN_GE" -> lengthOf(actual) >= lengthBound(raw);
      case "LEN_GT" -> lengthOf(actual) > lengthBound(raw);
      case "LEN_LE" -> lengthOf(actual) <= lengthBound(raw);
      case "LEN_LT" -> lengthOf(actual) < lengthBound(raw);
      case "EQ" -> compare(actual, raw) == 0;
      case "GE" -> compare(actual, raw) >= 0;
      case "GT" -> compare(actual, raw) > 0;
      case "LE" -> compare(actual, raw) <= 0;
      case "LT" -> compare(actual, raw) < 0;
      default -> throw new IllegalStateException("不认识的操作符： " + c.operator());
    };
  }

  private static int lengthOf(String value) {
    return value == null ? 0 : value.length();
  }

  /**
   * {@code LEN_*} 的右边是**数字本身**，不是一串要被数字符的文本。
   *
   * <p>★ 这条踩过一次：第一版写成 {@code lengthOf(raw)}，于是 {@code LEN_EQ "6"} 拿「"6" 有 1 个字符」 去比 6 个字符的串，永远不成立
   * —— 而且不报错，只是"这条分支永远不走"。 解析不出来时退回 0（等于「长度等于 0」，对空串成立），并由调用方按"没命中"处理。
   */
  private static int lengthBound(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int compare(String actual, String raw) {
    BigDecimal left = numberOrNull(actual);
    BigDecimal right = numberOrNull(raw);
    if (left != null && right != null) {
      return left.compareTo(right);
    }
    return String.valueOf(actual).compareTo(String.valueOf(raw));
  }

  private static BigDecimal numberOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String fallbackOf(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_FALLBACK);
    return raw instanceof String s && !s.isBlank() ? s : null;
  }

  /** 兼容 {@code List<Branch>}（测试直接给的）与 {@code List<Map>}（Jackson 反序列化出来的）。 */
  @SuppressWarnings("unchecked")
  static List<Branch> branchesOf(Map<String, Object> config) {
    if (config == null) {
      return List.of();
    }
    Object raw = config.get(CONFIG_BRANCHES);
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .map(
            item ->
                item instanceof Branch b
                    ? b
                    : new Branch(
                        String.valueOf(((Map<String, Object>) item).get("id")),
                        String.valueOf(((Map<String, Object>) item).getOrDefault("logic", "AND")),
                        conditionsOf((Map<String, Object>) item)))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Condition> conditionsOf(Map<String, Object> branch) {
    Object raw = branch.get("conditions");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .map(
            item -> {
              Map<String, Object> m = (Map<String, Object>) item;
              Object variable = m.get("variable");
              String[] pair = null;
              if (variable instanceof List<?> v && v.size() >= 2) {
                pair = new String[] {String.valueOf(v.get(0)), String.valueOf(v.get(1))};
              }
              return new Condition(
                  pair,
                  String.valueOf(m.getOrDefault("operator", "")),
                  m.get("value") == null ? null : String.valueOf(m.get("value")));
            })
        .toList();
  }

  /** 一个分支：{@code id} 同时是出边的 {@code sourceHandle}。 */
  public record Branch(String id, String logic, List<Condition> conditions) {}

  /** 一条条件：变量 {@code [节点id, 字段名]} × 操作符 × 值。 */
  public record Condition(String[] variable, String operator, String value) {}
}
