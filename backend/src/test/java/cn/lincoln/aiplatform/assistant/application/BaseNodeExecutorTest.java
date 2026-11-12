package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 基本信息收集节点：登记本次输入与配置的字段清单。 */
class BaseNodeExecutorTest {

  private final BaseNodeExecutor executor = new BaseNodeExecutor();

  private static WorkflowNodeContext ctx(
      Map<String, Object> config, String input, FlowState state) {
    return new WorkflowNodeContext(
        java.util.UUID.randomUUID(), "n-base", "BASE_NODE", input, state, config, null);
  }

  private static Map<String, Object> field(String name) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("type", "TEXT");
    return m;
  }

  @Test
  void registersConfiguredFieldsAndTheInput() {
    FlowState state = new FlowState();
    Map<String, Object> cfg = Map.of("fields", List.of(field("姓名"), field("部门")));

    executor.execute(ctx(cfg, "我是财务部的张伟", state));

    assertThat(state.read(BaseNodeExecutor.FIELDS_KEY)).isEqualTo("姓名,部门");
    assertThat(state.read(BaseNodeExecutor.INPUT_KEY)).isEqualTo("我是财务部的张伟");
  }

  @Test
  void nullInputBecomesEmptyStringNotNull() {
    FlowState state = new FlowState();

    executor.execute(ctx(Map.of(), null, state));

    assertThat(state.read(BaseNodeExecutor.INPUT_KEY))
        .as("null 与空串在执行器里必须归一：下游读到的 null 会变成 NPE")
        .isEqualTo("");
  }

  @Test
  void noFieldsStillRegistersTheInput() {
    FlowState state = new FlowState();

    executor.execute(ctx(Map.of(), "随便问点什么", state));

    // 没配字段不等于这个节点没用：它仍然登记了入口输入
    assertThat(state.read(BaseNodeExecutor.INPUT_KEY)).isEqualTo("随便问点什么");
    assertThat(state.read(BaseNodeExecutor.FIELDS_KEY)).isEmpty();
  }

  @Test
  void fieldNamesToleratesPlainStrings() {
    assertThat(BaseNodeExecutor.fieldNames(Map.of("fields", List.of("姓名", "部门"))))
        .containsExactly("姓名", "部门");
  }
}
