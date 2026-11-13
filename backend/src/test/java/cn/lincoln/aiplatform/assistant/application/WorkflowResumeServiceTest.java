package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowPause;
import cn.lincoln.aiplatform.assistant.domain.WorkflowPauseRepository;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 恢复端点的前置条件。
 *
 * <p>★ 这一批测的全是「**还没开始跑**就被挡下」的路径 —— 它们比「跑通了」更重要： 恢复一旦跑错，是把上一次的变量表灌进一次新的执行，而用户看到的只是一次「有点怪的运行」。
 *
 * <p>三条前置条件各自能独立拦住一次恢复，所以每一条都要有一个**只会被它拦住**的用例。
 */
class WorkflowResumeServiceTest {

  private final WorkflowPauseRepository pauses = mock(WorkflowPauseRepository.class);
  private final WorkflowRecordRepository records = mock(WorkflowRecordRepository.class);
  private final WorkflowNodeExecutionRepository nodeRecords =
      mock(WorkflowNodeExecutionRepository.class);
  private final LinearWorkflowExecutor executor = mock(LinearWorkflowExecutor.class);
  private WorkflowResumeService service;

  private final UUID assistantId = UUID.randomUUID();
  private final UUID executionId = UUID.randomUUID();

  private final NodeTypeCatalog catalog =
      new NodeTypeCatalog(tools.jackson.databind.json.JsonMapper.builder().build());

  @BeforeEach
  void setUp() {
    service = new WorkflowResumeService(pauses, records, executor, catalog);
  }

  /** 前置条件一：快照不存在。 */
  @Test
  void missingPauseIsNotFoundAndNeverRunsAnything() {
    when(pauses.find(executionId)).thenReturn(Optional.empty());

    WorkflowResumeService.Result result = service.resume(assistantId, executionId, null, "答复");

    assertThat(result.notFound()).as("快照不存在要让调用方知道「刷新一下」，不是「图有问题」").isTrue();
    assertThat(result.ok()).isFalse();
    verify(executor, never()).resumeAndReturn(any(), any(), anyString(), any(Integer.class), any());
  }

  /** 前置条件二：同一份快照恢复两次。 */
  @Test
  void alreadyResumedPauseIsRejected() {
    WorkflowPause done = pause(Instant.now());
    when(pauses.find(executionId)).thenReturn(Optional.of(done));

    WorkflowResumeService.Result result = service.resume(assistantId, executionId, null, "答复");

    assertThat(result.notFound()).as("已恢复过不是「不存在」").isFalse();
    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("恢复过"));
    verify(executor, never()).resumeAndReturn(any(), any(), anyString(), any(Integer.class), any());
  }

  /** ★ 前置条件三的前半：A 的快照不能被灌进 B。 */
  @Test
  void pauseOfAnotherAssistantIsRejected() {
    when(pauses.find(executionId)).thenReturn(Optional.of(pause(null)));

    WorkflowResumeService.Result result =
        service.resume(UUID.randomUUID(), executionId, emptyGraph(), "答复");

    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("不属于当前应用"));
    verify(executor, never()).resumeAndReturn(any(), any(), anyString(), any(Integer.class), any());
  }

  /** 前置条件三的后半：图被改短了，暂停点已经不存在。 */
  @Test
  void shorterGraphIsRejected() {
    // stepsDone 比新图能提供的位置还大 ⇒ 第 N 步已经不存在。
    when(pauses.find(executionId)).thenReturn(Optional.of(pause(null, 9)));

    WorkflowResumeService.Result result =
        service.resume(assistantId, executionId, emptyGraph(), "答复");

    assertThat(result.ok()).isFalse();
    assertThat(result.errors()).as("空图编译不过，也要给出可读理由（不是静默失败）").isNotEmpty();
    verify(executor, never()).resumeAndReturn(any(), any(), anyString(), any(Integer.class), any());
  }

  /**
   * 成功路径：答复写回 state，且**从中断点的下一步**接着跑 —— 不是从中断点本身重来。
   *
   * <p>★ 这条测试在修 {@code #338} 之前是红的：{@code resume} 把 {@code pause.stepsDone()} 直接当恢复点 传下去，而第 {@code
   * stepsDone} 步正是那个中断点本身 ⇒ 一进恢复就再抛一次 {@code WorkflowInterrupted}， {@code
   * pausedAgain=true}，答复永远走不到下游。原来那四条只测「还没开始跑就被挡下」，没有一条走到这里。
   *
   * <p>★ 用真执行器而不是 mock：这条要钉的正是「服务传给执行器的恢复点对不对」，mock 掉执行器就只剩 断言实现细节，看不出整条链会不会原地打转。
   */
  @Test
  void resumeStartsAfterTheInterruptAndFinishesTheGraph() {
    LinearWorkflowExecutor real =
        new LinearWorkflowExecutor(
            nodeRecords, List.of(new StartNodeExecutor(), new ReplyNodeExecutor()));
    WorkflowResumeService resuming = new WorkflowResumeService(pauses, records, real, catalog);
    when(pauses.find(executionId)).thenReturn(Optional.of(pause(null)));

    WorkflowResumeService.Result result =
        resuming.resume(assistantId, executionId, graphWithQuestion(), "住宿标准是多少");

    assertThat(result.pausedAgain()).as("答复之后应该从中断点的下一步接着跑，而不是原地又停一次（#338 的 off-by-one）").isFalse();
    assertThat(result.ok()).as("这张图跑得完 ⇒ ok").isTrue();
    assertThat(result.finalOutput()).as("REPLY_NODE 写进 state 的 reply 要成为最终输出").isEqualTo("已为你整理如下");

    ArgumentCaptor<WorkflowNodeExecutionRecord> saved =
        ArgumentCaptor.forClass(WorkflowNodeExecutionRecord.class);
    verify(nodeRecords, times(1)).save(saved.capture());
    assertThat(saved.getValue().nodeType())
        .as("只该跑 REPLY 这一步：START 在暂停前已经跑完，QUESTION 由用户这回的答复完成")
        .isEqualTo("REPLY_NODE");
  }

  /** 开始 → 追问 → 回复：暂停点是中间那一步（START 已跑完，所以 stepsDone=1）。 */
  private WorkflowCompiler.Graph graphWithQuestion() {
    return new WorkflowCompiler.Graph(
        List.of(
            new WorkflowCompiler.Node("n-start", "START_NODE"),
            new WorkflowCompiler.Node("n-ask", "QUESTION_NODE"),
            new WorkflowCompiler.Node(
                "n-reply", "REPLY_NODE", Map.of("config", Map.of("content", "已为你整理如下")))),
        List.of(
            new WorkflowCompiler.Edge("n-start", "n-ask", null),
            new WorkflowCompiler.Edge("n-ask", "n-reply", null)));
  }

  private WorkflowPause pause(Instant resumedAt) {
    return pause(resumedAt, 1);
  }

  private WorkflowPause pause(Instant resumedAt, int stepsDone) {
    return new WorkflowPause(
        executionId,
        assistantId,
        "n-ask",
        "QUESTION_NODE",
        Map.of("query", "原来的问题"),
        stepsDone,
        Instant.now().minusSeconds(60),
        resumedAt);
  }

  /** 没有任何节点的合法图：只用来让「图改过」那条判据有机会比大小。 */
  private WorkflowCompiler.Graph emptyGraph() {
    return new WorkflowCompiler.Graph(List.of(), List.of());
  }
}
