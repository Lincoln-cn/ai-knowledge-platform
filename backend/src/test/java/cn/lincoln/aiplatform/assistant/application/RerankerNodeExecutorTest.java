package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.assistant.WorkflowRerankPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 判据（{@code #333} 的 {@code RERANKER_NODE}）：重排节点真的按端口给的顺序重排、真的把问题传下去、 接不上检索节点时明确失败。
 *
 * <p>★ 为什么"端口没丢候选"也要单独一条：{@code RerankPort} 的契约写的是"返回入参的一个排列"， 而实现违约（少给几条）时最难发现 ——
 * 模型照样答，只是那几条资料没进上下文，读数是"跑成功了"。
 */
@DisplayName("判据 · 重排节点执行器：按端口顺序重排、问题传下去、接不上检索就失败")
class RerankerNodeExecutorTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  private static final UUID SEG_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final UUID SEG_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

  /** 记下入参、回放预设顺序的替身端口。 */
  private static final class RecordingPort implements WorkflowRerankPort {
    String query;
    List<UUID> ids = List.of();
    Map<UUID, String> texts = Map.of();
    int topK;

    /** 端口返回什么；null 表示"照入参原序回"。 */
    List<UUID> answer;

    @Override
    public List<UUID> rerank(
        String query, List<UUID> candidateIds, Map<UUID, String> texts, int topK) {
      this.query = query;
      this.ids = candidateIds;
      this.texts = texts;
      this.topK = topK;
      return answer == null ? candidateIds : answer;
    }
  }

  @Test
  @DisplayName("端口给了新顺序：文本与 rank 都按新顺序落进 state")
  void reordersByPortResult() {
    RecordingPort port = new RecordingPort();
    port.answer = List.of(SEG_B, SEG_A);
    FlowState state = stateWithHits();
    state.write(WorkflowNodeKeys.Output.QUERY, "住宿标准是多少", KeyUpdate.REPLACE);

    new RerankerNodeExecutor(providerOf(port)).execute(context(state, Map.of()));

    assertThat(state.read(RerankerNodeExecutor.RESULT_KEY))
        .isEqualTo("[差旅费管理办法.pdf] 第三十一条：住宿标准按职级执行。\n\n[制度汇编.pdf] 固定资产归口部门是财务资产部");
    assertThat(state.read(RerankerNodeExecutor.HITS_KEY))
        .contains("\"rank\":1")
        .contains(SEG_B.toString());
  }

  @Test
  @DisplayName("问题与窗口：默认取上游 query，topK 缺省 5")
  void passesQuestionAndDefaultWindow() {
    RecordingPort port = new RecordingPort();
    FlowState state = stateWithHits();
    state.write(WorkflowNodeKeys.Output.QUERY, "住宿标准是多少", KeyUpdate.REPLACE);

    new RerankerNodeExecutor(providerOf(port)).execute(context(state, Map.of()));

    assertThat(port.query).isEqualTo("住宿标准是多少");
    assertThat(port.topK).isEqualTo(5);
    assertThat(port.texts.get(SEG_A)).isEqualTo("制度汇编.pdf\n固定资产归口部门是财务资产部");
  }

  @Test
  @DisplayName("配置里的 question 与 topK 优先于 state 与默认值")
  void configOverridesQuestionAndWindow() {
    RecordingPort port = new RecordingPort();
    FlowState state = stateWithHits();
    state.write(WorkflowNodeKeys.Output.QUERY, "改写后的问题", KeyUpdate.REPLACE);

    new RerankerNodeExecutor(providerOf(port))
        .execute(context(state, Map.of("question", "原话", "topK", 2)));

    assertThat(port.query).isEqualTo("原话");
    assertThat(port.topK).isEqualTo(2);
  }

  @Test
  @DisplayName("上游没有 retrieval_hits：明确失败，并说清该接在哪个节点后面")
  void rejectsMissingHits() {
    FlowState state = new FlowState();

    assertThatThrownBy(
            () ->
                new RerankerNodeExecutor(providerOf(new RecordingPort()))
                    .execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("retrieval_hits");
    assertThat(state.read(RerankerNodeExecutor.RESULT_KEY)).isNull();
  }

  @Test
  @DisplayName("端口没装配：明确失败，而不是写一份原序让人以为重排生效了")
  void rejectsMissingPort() {
    FlowState state = stateWithHits();

    assertThatThrownBy(
            () -> new RerankerNodeExecutor(providerOf(null)).execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WorkflowRerankPort");
  }

  @Test
  @DisplayName("端口少给了候选：缺的补到末尾，不许静默丢资料")
  void keepsAllCandidatesWhenPortDropsOne() {
    RecordingPort port = new RecordingPort();
    port.answer = List.of(SEG_A, SEG_A);

    FlowState state = stateWithHits();
    new RerankerNodeExecutor(providerOf(port)).execute(context(state, Map.of()));

    assertThat(state.read(RerankerNodeExecutor.HITS_KEY))
        .contains(SEG_A.toString())
        .contains(SEG_B.toString());
  }

  @Test
  @DisplayName("命中里缺 segmentId：明确失败，不许只对一部分候选排序")
  void rejectsHitWithoutSegmentId() {
    FlowState state = new FlowState();
    state.write(
        SearchDatasetNodeExecutor.HITS_KEY,
        "[{\"docName\":\"没有 id 的命中\",\"content\":\"正文\"}]",
        KeyUpdate.REPLACE);

    assertThatThrownBy(
            () ->
                new RerankerNodeExecutor(providerOf(new RecordingPort()))
                    .execute(context(state, Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("segmentId");
  }

  private static FlowState stateWithHits() {
    FlowState state = new FlowState();
    state.write(
        SearchDatasetNodeExecutor.HITS_KEY,
        "[{\"segmentId\":\""
            + SEG_A
            + "\",\"docName\":\"制度汇编.pdf\",\"content\":\"固定资产归口部门是财务资产部\"},"
            + "{\"segmentId\":\""
            + SEG_B
            + "\",\"docName\":\"差旅费管理办法.pdf\",\"content\":\"第三十一条：住宿标准按职级执行。\"}]",
        KeyUpdate.REPLACE);
    return state;
  }

  private static WorkflowNodeContext context(FlowState state, Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-rerank", "RERANKER_NODE", "住宿标准是多少", state, config);
  }

  private static ObjectProvider<WorkflowRerankPort> providerOf(WorkflowRerankPort port) {
    return new ObjectProvider<>() {
      @Override
      public WorkflowRerankPort getObject(Object... args) {
        return port;
      }

      @Override
      public WorkflowRerankPort getIfAvailable() {
        return port;
      }

      @Override
      public WorkflowRerankPort getIfUnique() {
        return port;
      }
    };
  }
}
