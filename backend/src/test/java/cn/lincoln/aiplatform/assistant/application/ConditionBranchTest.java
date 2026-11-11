package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 0.7.2：条件分支在运行时真的分叉。
 *
 * <p>★ 这一组断的不是「编译器能不能编出计划」，而是「**同一张图按不同输入走不同分支**」—— 前者 0.7.2 之前就有（`WorkflowCompilerTest`
 * 六条），但执行器那时会拒绝任何带 `branchHandle` 的计划， 等于条件节点在运行时根本不存在。
 */
class ConditionBranchTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final UUID EXECUTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID MODEL_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  /** 一张「开始 → 条件 →（A 分支：AI 对话）/（B 分支：回复）」的图。 */
  private static WorkflowCompiler.Graph twoBranchGraph() {
    List<WorkflowCompiler.Node> nodes =
        List.of(
            new WorkflowCompiler.Node("n-start", "START_NODE", Map.of()),
            new WorkflowCompiler.Node(
                "n-cond",
                "CONDITION_NODE",
                Map.of(
                    "config",
                    Map.of(
                        "branch",
                        List.of(
                            Map.of("id", "A", "type", "if", "expression", "问题在问住宿标准"),
                            Map.of("id", "B", "type", "if", "expression", "问题在写诗、闲聊"))))),
            new WorkflowCompiler.Node(
                "n-ai",
                "AI_CHAT_NODE",
                Map.of("config", Map.of(AiChatNodeExecutor.CONFIG_MODEL_ID, MODEL_ID.toString()))),
            new WorkflowCompiler.Node(
                "n-reply",
                "REPLY_NODE",
                Map.of("config", Map.of("reply_type", "content", "content", "兜底回复"))));
    List<WorkflowCompiler.Edge> edges =
        List.of(
            new WorkflowCompiler.Edge("n-start", "n-cond", null),
            new WorkflowCompiler.Edge("n-cond", "n-ai", "A"),
            new WorkflowCompiler.Edge("n-cond", "n-reply", "B"));
    return new WorkflowCompiler.Graph(nodes, edges);
  }

  private static LinearWorkflowExecutor executor(
      ChatPort chatPort, List<WorkflowNodeExecutionRecord> saved) {
    WorkflowNodeExecutionRepository records = mock(WorkflowNodeExecutionRepository.class);
    when(records.save(any()))
        .thenAnswer(
            invocation -> {
              saved.add(invocation.getArgument(0));
              return invocation.getArgument(0);
            });
    return new LinearWorkflowExecutor(
        records,
        List.of(
            new StartNodeExecutor(),
            new ConditionNodeExecutor(chatPort),
            new AiChatNodeExecutor(chatPort),
            new ReplyNodeExecutor()));
  }

  private static ChatPort chatPortReturning(String branchAnswer, String aiAnswer) {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    when(chatPort.complete(any(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              String prompt = invocation.getArgument(1);
              // 条件节点的提示词里有「请判断下面的问题符合哪一条条件」；AI 节点的没有。
              return prompt.contains("请判断下面的问题符合哪一条条件") ? branchAnswer : aiAnswer;
            });
    return chatPort;
  }

  @Test
  @DisplayName("编译器把分支 id 传播到整条链，汇合点回到 null")
  void compilerPropagatesBranchAndResetsAtJoin() {
    WorkflowCompiler.CompileResult result =
        new WorkflowCompiler(new NodeTypeCatalog(MAPPER)).compile(twoBranchGraph());

    assertThat(result.ok()).isTrue();
    var byId = new LinkedHashMap<String, String>();
    for (WorkflowCompiler.PlanStep step : result.plan().steps()) {
      byId.put(step.nodeId(), step.branchHandle());
    }
    assertThat(byId)
        .containsEntry("n-start", null) // 开始节点不在任何分支上
        .containsEntry("n-cond", null) // 条件节点本身要先跑，才能决定走哪
        .containsEntry("n-ai", "A")
        .containsEntry("n-reply", "B");
  }

  @Test
  @DisplayName("走 A 分支时不执行 B 分支的节点（同一张图，两个输入两种走法）")
  void runsOnlyTheChosenBranch() {
    List<WorkflowNodeExecutionRecord> saved = new ArrayList<>();
    ChatPort chatPort = chatPortReturning("A", "A 分支的回答");

    executor(chatPort, saved)
        .executeAndReturn(EXECUTION_ID, compilePlan(twoBranchGraph()), "住宿标准是多少？");

    List<String> ranNodes = saved.stream().map(WorkflowNodeExecutionRecord::nodeId).toList();
    assertThat(ranNodes).contains("n-start", "n-cond", "n-ai").doesNotContain("n-reply");
  }

  @Test
  @DisplayName("走 B 分支时不执行 A 分支的节点")
  void runsOnlyTheOtherBranch() {
    List<WorkflowNodeExecutionRecord> saved = new ArrayList<>();
    ChatPort chatPort = chatPortReturning("B", "不该被用到");

    executor(chatPort, saved)
        .executeAndReturn(EXECUTION_ID, compilePlan(twoBranchGraph()), "帮我写首诗");

    List<String> ranNodes = saved.stream().map(WorkflowNodeExecutionRecord::nodeId).toList();
    assertThat(ranNodes).contains("n-start", "n-cond", "n-reply").doesNotContain("n-ai");
  }

  private static WorkflowCompiler.ExecutionPlan compilePlan(WorkflowCompiler.Graph graph) {
    return new WorkflowCompiler(new NodeTypeCatalog(MAPPER)).compile(graph).plan();
  }
}
