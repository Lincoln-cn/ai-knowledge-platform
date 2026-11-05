package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判据 3（{@code #113}）：{@link KeyUpdate} 状态语义 —— 同一键被两个节点写。
 *
 * <p>★ 两条各一条能红的测试：REPLACE 断言"后写覆盖"、APPEND 断言"追加"；两条互为反证（换一种实现就红）。
 */
@DisplayName("判据3 · KeyUpdate 状态语义（同键两节点写）")
class FlowStateTest {

  @Test
  @DisplayName("REPLACE：两个节点写同一键，后写覆盖前写")
  void replaceOverwrites() {
    FlowState state = new FlowState();
    state.write("answer", "第一次", KeyUpdate.REPLACE);
    state.write("answer", "第二次", KeyUpdate.REPLACE);

    assertThat(state.read("answer")).as("REPLACE 语义：后写覆盖 ⇒ 只留最后一个值（若实现成追加这里会红）").isEqualTo("第二次");
  }

  @Test
  @DisplayName("APPEND：两个节点写同一键，后写追加到前写之后")
  void appendConcatenates() {
    FlowState state = new FlowState();
    state.write("answer", "第一次", KeyUpdate.APPEND);
    state.write("answer", "第二次", KeyUpdate.APPEND);

    assertThat(state.read("answer")).as("APPEND 语义：追加 ⇒ 历史都留着（若实现成覆盖这里会红）").isEqualTo("第一次第二次");
  }
}
