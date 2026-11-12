package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 数值判断节点：结构化比较，不经过模型。 */
class NumberConditionNodeExecutorTest {

  private final NumberConditionNodeExecutor executor = new NumberConditionNodeExecutor();

  private static WorkflowNodeContext ctx(Map<String, Object> config, FlowState state) {
    return new WorkflowNodeContext(
        java.util.UUID.randomUUID(), "n-num", "NUMBER_CONDITION_NODE", "", state, config, null);
  }

  private static Map<String, Object> branch(String id, String logic, Map<String, Object>... conds) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("id", id);
    b.put("logic", logic);
    b.put("conditions", List.of(conds));
    return b;
  }

  private static Map<String, Object> cond(String[] variable, String operator, String value) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("variable", variable == null ? null : List.of(variable));
    c.put("operator", operator);
    c.put("value", value);
    return c;
  }

  /**
   * ★ 不 put `null`：{@link WorkflowNodeContext} 的紧凑构造器做 `Map.copyOf`，它**不接受 null 值**。
   * 所以"没有兜底分支"在配置里的表现是<strong>这个键不存在</strong>，而不是键存在、值为 null —— 这也正是 Jackson 反序列化省略字段时的形状。
   */
  private static Map<String, Object> config(List<Map<String, Object>> branches, String fallback) {
    Map<String, Object> cfg = new LinkedHashMap<>();
    cfg.put("branches", branches);
    if (fallback != null) {
      cfg.put("fallbackBranchId", fallback);
    }
    return cfg;
  }

  @Test
  void firstMatchingBranchWinsAndWritesActiveBranch() {
    FlowState state = new FlowState();
    state.write("answer", "120", KeyUpdate.REPLACE);

    executor.execute(
        ctx(
            config(
                List.of(
                    branch("high", "AND", cond(new String[] {"n-ai", "answer"}, "GT", "100")),
                    branch("low", "AND", cond(new String[] {"n-ai", "answer"}, "LE", "100"))),
                "other"),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("high");
  }

  @Test
  void andRequiresEveryCondition() {
    FlowState state = new FlowState();
    state.write("answer", "50", KeyUpdate.REPLACE);

    // 第一条命中（50 > 10）、第二条不命中（50 > 100 为假）⇒ AND 不成立
    // ⇒ 无分支命中且无兜底 ⇒ 抛错。只调一次、只看这一次的结果。
    assertThatThrownBy(
            () ->
                executor.execute(
                    ctx(
                        config(
                            List.of(
                                branch(
                                    "both",
                                    "AND",
                                    cond(new String[] {"n", "answer"}, "GT", "10"),
                                    cond(new String[] {"n", "answer"}, "GT", "100"))),
                            null),
                        state)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有配置兜底分支");
  }

  @Test
  void orNeedsOnlyOneCondition() {
    FlowState state = new FlowState();
    state.write("answer", "50", KeyUpdate.REPLACE);

    executor.execute(
        ctx(
            config(
                List.of(
                    branch(
                        "any",
                        "OR",
                        cond(new String[] {"n", "answer"}, "GT", "100"),
                        cond(new String[] {"n", "answer"}, "GT", "10"))),
                null),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("any");
  }

  @Test
  void noMatchFallsBackToConfiguredBranch() {
    FlowState state = new FlowState();
    state.write("answer", "5", KeyUpdate.REPLACE);

    executor.execute(
        ctx(
            config(
                List.of(branch("high", "AND", cond(new String[] {"n", "answer"}, "GT", "100"))),
                "other"),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("other");
  }

  @Test
  void noMatchWithoutFallbackThrowsInsteadOfGuessing() {
    FlowState state = new FlowState();
    state.write("answer", "5", KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                executor.execute(
                    ctx(
                        config(
                            List.of(
                                branch(
                                    "high",
                                    "AND",
                                    cond(new String[] {"n", "answer"}, "GT", "100"))),
                            null),
                        state)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有配置兜底分支");
  }

  @Test
  void lengthOperatorsCountCharacters() {
    FlowState state = new FlowState();
    state.write("answer", "abcdef", KeyUpdate.REPLACE);

    executor.execute(
        ctx(
            config(
                List.of(branch("six", "AND", cond(new String[] {"n", "answer"}, "LEN_EQ", "6"))),
                null),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("six");
  }

  @Test
  void nullOperatorsDoNotNeedAValue() {
    FlowState state = new FlowState();

    executor.execute(
        ctx(
            config(
                List.of(
                    branch("empty", "AND", cond(new String[] {"n", "missing"}, "IS_NULL", null))),
                null),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("empty");
  }

  @Test
  void nonNumericValuesFallBackToTextComparisonInsteadOfZero() {
    FlowState state = new FlowState();
    state.write("answer", "abc", KeyUpdate.REPLACE);

    // 「abc」与「5」：若把解析失败当 0，`0 > 5` 为假 ⇒ 走兜底 small；
    // 按字符串比较 `"abc" > "5"` 为真 ⇒ 走 big。两种口径结果不同，这条才分得出来。
    executor.execute(
        ctx(
            config(
                List.of(branch("big", "AND", cond(new String[] {"n", "answer"}, "GT", "5"))),
                "small"),
            state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH))
        .as("非数字必须退回字符串比较，不能当成 0")
        .isEqualTo("big");
  }
}
