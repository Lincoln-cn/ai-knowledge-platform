package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 判据（{@code #326}）：检索节点真的检索、真的报错，而不是"注册成功但永远空转"。 */
@DisplayName("判据 · 检索节点执行器：有配置能检索、缺配置明确失败、端口未装配明确失败")
class SearchDatasetNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  private static final UUID KB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  /** 只回定值、并记下调用参数的替身端口。 */
  private static final class RecordingPort implements WorkflowSearchPort {
    final List<String> calls = new ArrayList<>();
    List<Snippet> snippets = List.of(new Snippet("制度汇编.pdf", "固定资产归口部门是财务资产部"));

    @Override
    public List<Snippet> search(UUID kbId, String query, int topN) {
      calls.add(kbId + "|" + query + "|" + topN);
      return snippets;
    }
  }

  private static ObjectProvider<WorkflowSearchPort> provider(WorkflowSearchPort port) {
    return new ObjectProvider<>() {
      @Override
      public WorkflowSearchPort getObject(Object... args) {
        return port;
      }

      @Override
      public WorkflowSearchPort getIfAvailable() {
        return port;
      }

      @Override
      public WorkflowSearchPort getIfUnique() {
        return port;
      }

      @Override
      public WorkflowSearchPort getObject() {
        return port;
      }
    };
  }

  private static WorkflowNodeContext context(Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-search", "SEARCH_DATASET_NODE", "固定资产的归口部门", new FlowState(), config);
  }

  @Test
  @DisplayName("绑了库：按 config 里的库与 topN 检索，命中写进 state")
  void searchesWhenKbIsConfigured() {
    RecordingPort port = new RecordingPort();
    new SearchDatasetNodeExecutor(provider(port))
        .execute(
            context(
                Map.of(
                    SearchDatasetNodeExecutor.CONFIG_KB_IDS,
                    List.of(KB_ID.toString()),
                    "retrieval",
                    Map.of("topN", 3))));

    assertThat(port.calls).as("必须真的调一次检索").containsExactly(KB_ID + "|固定资产的归口部门|3");

    FlowState state = new FlowState();
    new SearchDatasetNodeExecutor(provider(port))
        .execute(
            new WorkflowNodeContext(
                EXECUTION_ID,
                "n-search",
                "SEARCH_DATASET_NODE",
                "固定资产的归口部门",
                state,
                Map.of(SearchDatasetNodeExecutor.CONFIG_KB_IDS, List.of(KB_ID.toString()))));
    assertThat(state.read(SearchDatasetNodeExecutor.RESULT_KEY))
        .as("命中的正文必须写进 state，否则下游节点拿不到资料")
        .contains("固定资产归口部门是财务资产部");
  }

  @Test
  @DisplayName("没绑库：抛异常而不是写空串")
  void failsWhenNoKbIsConfigured() {
    SearchDatasetNodeExecutor executor =
        new SearchDatasetNodeExecutor(provider(new RecordingPort()));

    assertThatThrownBy(() -> executor.execute(context(Map.of())))
        .as("没绑库还照常跑，等于让模型对着空资料编答案")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有绑定任何知识库");
  }

  @Test
  @DisplayName("端口没装配：抛异常，不静默返回空")
  void failsWhenPortIsMissing() {
    SearchDatasetNodeExecutor executor = new SearchDatasetNodeExecutor(provider(null));

    assertThatThrownBy(
            () ->
                executor.execute(
                    context(
                        Map.of(
                            SearchDatasetNodeExecutor.CONFIG_KB_IDS, List.of(KB_ID.toString())))))
        .as("「检索没命中」与「检索能力没装配」不能长得一样")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("检索能力还没装配");
  }
}
