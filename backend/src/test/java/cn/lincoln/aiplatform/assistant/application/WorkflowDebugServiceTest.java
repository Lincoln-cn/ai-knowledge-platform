package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/**
 * 试用链路（{@code #326} 第 1–3 层）的判据。
 *
 * <p>★ 四条各钉一件事，其中「失败也留一行」是这一版最重要的：执行器在节点抛异常时整批回滚， 如果记录也放进同一个事务，<strong>失败的试用就不会留下任何痕迹</strong> ——
 * 而失败恰恰是最需要回查的那一次。
 */
class WorkflowDebugServiceTest {

  private static final UUID APP = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

  private NodeTypeCatalog catalog;
  private LinearWorkflowExecutor executor;
  private WorkflowRecordRepository records;
  private cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository pauses;
  private WorkflowDebugService service;

  @BeforeEach
  void setUp() {
    catalog = new NodeTypeCatalog(JsonMapper.builder().build());
    executor = Mockito.mock(LinearWorkflowExecutor.class);
    records = Mockito.mock(WorkflowRecordRepository.class);
    pauses = Mockito.mock(cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository.class);
    when(records.save(any(WorkflowRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new WorkflowDebugService(catalog, executor, records, pauses);
  }

  /** 编译不过 ⇒ 不产生运行记录（"图还不成立"不是"一次失败的运行"）。 */
  @Test
  void compileFailureWritesNoRecord() {
    WorkflowCompiler.Graph graph = new WorkflowCompiler.Graph(List.of(), List.of());

    WorkflowDebugService.Result result = service.debug(APP, graph, "问题");

    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).isNotEmpty();
    verify(records, never()).save(any(WorkflowRecord.class));
    verify(executor, never()).executeAndReturn(any(), any(), anyString());
  }

  /** 跑通 ⇒ 记录是 SUCCEEDED，且 finalOutput 取自状态的 reply 键。 */
  @Test
  void successfulRunWritesSucceededRecord() {
    FlowState state = new FlowState();
    state.write("reply", "这是最终回答", KeyUpdate.REPLACE);
    when(executor.executeAndReturn(any(), any(), eq("问题"))).thenReturn(state);

    WorkflowDebugService.Result result = service.debug(APP, fourNodeGraph(), "问题");

    assertThat(result.ok()).isTrue();
    assertThat(result.finalOutput()).isEqualTo("这是最终回答");
    assertThat(result.executionId()).isNotNull();

    ArgumentCaptor<WorkflowRecord> saved = ArgumentCaptor.forClass(WorkflowRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo("SUCCEEDED");
    assertThat(saved.getValue().failReason()).isNull();
    assertThat(saved.getValue().conversationId()).as("试用不属于任何会话 —— 统计口径靠这一格把试用与真问答分开").isNull();
    assertThat(saved.getValue().finalOutputPreview()).isEqualTo("这是最终回答");
  }

  /**
   * 执行抛异常 ⇒ <strong>仍然写一行，且是 FAILED</strong>。
   *
   * <p>★ 反证目标：把 {@code records.save} 挪进 try 里（与执行同一事务）⇒ 这条会红。
   */
  @Test
  void failedRunStillWritesFailedRecord() {
    when(executor.executeAndReturn(any(), any(), any()))
        .thenThrow(new IllegalStateException("没有类型 AI_CHAT_NODE 的节点执行器"));

    WorkflowDebugService.Result result = service.debug(APP, fourNodeGraph(), "问题");

    // ★ 这里原来是 isTrue()，而且它“通过”了 —— 因为实现里写死了 true。
    //   一条照着实现抄的期望值只能证明“实现没变”，证明不了“实现对不对”。
    //   真栈验证时它表现为：试用对话框显示“成功”，记录表里是 FAILED。
    assertThat(result.ok()).as("执行失败时 ok 必须是 false").isFalse();
    assertThat(result.errors()).as("失败原因要带回给调用方，不能只写进记录表").hasSize(1);
    assertThat(result.errors().getFirst()).contains("没有类型 AI_CHAT_NODE 的节点执行器");
    assertThat(result.finalOutput()).isNull();

    ArgumentCaptor<WorkflowRecord> saved = ArgumentCaptor.forClass(WorkflowRecord.class);
    verify(records).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo("FAILED");
    assertThat(saved.getValue().failReason()).contains("没有类型 AI_CHAT_NODE 的节点执行器");
  }

  /** 轨迹来自编译后的计划（节点顺序与类型），不需要再读一遍节点执行记录表。 */
  @Test
  void trailComesFromCompiledPlan() {
    FlowState state = new FlowState();
    state.write("reply", "答", KeyUpdate.REPLACE);
    when(executor.executeAndReturn(any(), any(), any())).thenReturn(state);

    WorkflowDebugService.Result result = service.debug(APP, fourNodeGraph(), "问题");

    assertThat(result.nodeTrail())
        .extracting(WorkflowDebugService.TrailStep::type)
        .containsExactly("START_NODE", "REPLY_NODE");
    assertThat(result.nodeTrail())
        .extracting(WorkflowDebugService.TrailStep::order)
        .as("order 从 0 开始（编译器 PlanStep 的口径），不是从 1")
        .containsExactly(0, 1);
  }

  /**
   * 一个最小的可编译图：START → REPLY。
   *
   * <p>★ 不用四节点：{@code SEARCH_DATASET_NODE} 与 {@code AI_CHAT_NODE} 的配置在这一层不参与编译，加进来只会让夹具变长、
   * 而这里要验的是"记录写得对"。
   */
  private static WorkflowCompiler.Graph fourNodeGraph() {
    WorkflowCompiler.Node start =
        new WorkflowCompiler.Node("n-start", "START_NODE", Map.of("config", Map.of()));
    WorkflowCompiler.Node reply =
        new WorkflowCompiler.Node(
            "n-reply",
            "REPLY_NODE",
            Map.of("config", Map.of("reply_type", "content", "content", "答")));
    return new WorkflowCompiler.Graph(
        List.of(start, reply), List.of(new WorkflowCompiler.Edge("n-start", "n-reply", null)));
  }

  /**
   * 中断是第三档状态（PAUSED），不是失败。
   *
   * <p>★ 这条测试盯的是「有人把 paused 忘了」—— 忘掉之后中断会被写成 FAILED， 而 FAILED 的约束要求 fail_reason 非空，于是记录写不进去、整次试用变成
   * 500。
   */
  @Test
  void interruptedRunIsPausedNotFailed() {
    assertThat(WorkflowDebugService.statusOf(null, true)).isEqualTo("PAUSED");
    assertThat(WorkflowDebugService.statusOf("模型超时", true))
        .as("有失败原因时，中断仍然优先 —— 它表示流程没走完，而不是走完了出错")
        .isEqualTo("PAUSED");
    assertThat(WorkflowDebugService.statusOf(null, false)).isEqualTo("SUCCEEDED");
    assertThat(WorkflowDebugService.statusOf("模型超时", false)).isEqualTo("FAILED");
  }
}
