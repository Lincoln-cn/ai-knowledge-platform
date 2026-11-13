package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.WorkflowNodeExecutionRepository;
import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * 判据（{@code #335}）：<strong>后端执行器读的键名必须与前端契约同名</strong>。
 *
 * <p>★ 这一组测试为什么必须存在：{@code #335} 的三处断链都不是"某个功能没写"，而是"两边各写了一个名字"—— 前端 {@code graph-format.ts} 发
 * {@code kbIds / branches / defaultAnswer}，后端执行器读 {@code dataset_id_list / branch /
 * default_answer}。这种错误在任何单侧单测里都看不出来：执行器用自己的键名喂自己，永远是绿的； 前端用自己的形状发出去，也是绿的。只有把"前端真实产物"喂给"后端真实执行器"才会红。
 *
 * <p>★ 所以这里的输入一律用<strong>前端 {@code fromLogicFlow} 的真实形状</strong>（含 {@code title} / {@code position}
 * 这些后端不认识的字段），而不是手工拼一个后端喜欢的 {@code Map}。
 */
@DisplayName("判据 · 前端契约形状能直接喂给执行器（键名一处也不许漂）")
class WorkflowContractShapeTest {

  private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  private static final UUID KB_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b9");
  private static final UUID MODEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 只回定值、并记下调用参数的替身端口。 */
  private static final class RecordingPort implements WorkflowSearchPort {
    final List<String> calls = new ArrayList<>();
    List<Snippet> snippets = List.of(new Snippet("差旅费管理办法.pdf", "第三十一条：住宿标准按职级执行。"));

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

      @Override
      public java.util.Iterator<WorkflowSearchPort> iterator() {
        return List.of(port).iterator();
      }
    };
  }

  private static WorkflowNodeContext context(
      String nodeId, String type, String input, Map<String, Object> config, FlowState state) {
    return new WorkflowNodeContext(EXECUTION_ID, nodeId, type, input, state, config);
  }

  // ────────────────────────── 检索节点 ──────────────────────────

  @Test
  @DisplayName("检索节点读契约的 kbIds —— 不是旧画布的 dataset_id_list")
  void searchNodeReadsContractKbIds() {
    RecordingPort port = new RecordingPort();
    FlowState state = new FlowState();

    new SearchDatasetNodeExecutor(provider(port))
        .execute(
            context(
                "search-1",
                "SEARCH_DATASET_NODE",
                "住宿标准是多少？",
                // 前端 SearchDatasetNodeConfig 的原样形状
                Map.of(
                    "kbIds",
                    List.of(KB_ID.toString()),
                    "retrieval",
                    Map.of(
                        "mode", "HYBRID", "threshold", 0.6, "topN", 3, "noRefStrategy", "REFUSE")),
                state));

    assertThat(port.calls).hasSize(1);
    assertThat(port.calls.get(0)).startsWith(KB_ID.toString()).endsWith("|3");
    assertThat(state.read(SearchDatasetNodeExecutor.RESULT_KEY))
        .contains("差旅费管理办法.pdf")
        .contains("第三十一条");
  }

  @Test
  @DisplayName("检索节点仍认旧画布的 dataset_id_list（老草稿不该因改名跑不动）")
  void searchNodeStillAcceptsLegacyKey() {
    RecordingPort port = new RecordingPort();

    new SearchDatasetNodeExecutor(provider(port))
        .execute(
            context(
                "search-1",
                "SEARCH_DATASET_NODE",
                "问题",
                Map.of(SearchDatasetNodeExecutor.LEGACY_KB_IDS, List.of(KB_ID.toString())),
                new FlowState()));

    assertThat(port.calls).hasSize(1);
  }

  // ────────────────────────── 判断节点 ──────────────────────────

  @Test
  @DisplayName("判断节点读契约的 branches —— 不是旧画布的 branch（单数）")
  void conditionNodeReadsContractBranches() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("b2");
    FlowState state = new FlowState();

    new ConditionNodeExecutor(chatPort)
        .execute(
            context(
                "condition-1",
                "CONDITION_NODE",
                "流程怎么走？",
                // 前端 ConditionNodeConfig 的原样形状
                Map.of(
                    "branches",
                    List.of(
                        Map.of("id", "b1", "label", "条件 A", "expression", "问风险或违约"),
                        Map.of("id", "b2", "label", "条件 B", "expression", "问流程或步骤")),
                    "fallbackBranchId",
                    "b-fallback"),
                state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("b2");
  }

  @Test
  @DisplayName("判断节点按 fallbackBranchId 找兜底（契约不用 type=ELSE 表达兜底）")
  void conditionNodeUsesFallbackBranchId() {
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    // 模型回了一个谁都不是的编号 ⇒ 应当落兜底，而不是抛异常
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("我没看懂");
    FlowState state = new FlowState();

    new ConditionNodeExecutor(chatPort)
        .execute(
            context(
                "condition-1",
                "CONDITION_NODE",
                "莫名其妙",
                Map.of(
                    "branches",
                    List.of(
                        Map.of("id", "b1", "label", "条件 A", "expression", "问风险"),
                        Map.of("id", "b2", "label", "条件 B", "expression", "问流程"),
                        Map.of("id", "b-fallback", "label", "兜底", "expression", "以上都不命中")),
                    "fallbackBranchId",
                    "b-fallback"),
                state));

    assertThat(state.read(LinearWorkflowExecutor.ACTIVE_BRANCH)).isEqualTo("b-fallback");
  }

  // ────────────────────────── 追问节点 ──────────────────────────

  @Test
  @DisplayName("追问节点读契约的 defaultAnswer —— 旧名 default_answer 在契约里根本不存在")
  void questionNodeReadsContractDefaultAnswer() {
    FlowState state = new FlowState();

    new QuestionNodeExecutor()
        .execute(
            context(
                "question-1",
                "QUESTION_NODE",
                "  ",
                Map.of("modelId", "", "rewritePrompt", "把问题改写完整", "defaultAnswer", "定时任务默认答复"),
                state));

    assertThat(state.read(QuestionNodeExecutor.QUERY_KEY)).isEqualTo("定时任务默认答复");
  }

  // ────────────────────────── 回复节点 ──────────────────────────

  @Test
  @DisplayName("回复节点没有 replyType 也能跑：契约里它可选，且当前只有一种形态")
  void replyNodeRunsWithoutReplyType() {
    FlowState state = new FlowState();

    // 前端 ReplyNodeConfig 的原样形状（老草稿里可能没有 replyType）
    new ReplyNodeExecutor()
        .execute(context("reply-1", "REPLY_NODE", "", Map.of("content", "结论如下"), state));

    assertThat(state.read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("结论如下");
  }

  @Test
  @DisplayName("回复节点认契约的 replyType")
  void replyNodeAcceptsContractReplyType() {
    FlowState state = new FlowState();

    new ReplyNodeExecutor()
        .execute(
            context(
                "reply-1",
                "REPLY_NODE",
                "",
                Map.of("content", "结论如下", ReplyNodeExecutor.CONFIG_REPLY_TYPE, "content"),
                state));

    assertThat(state.read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("结论如下");
  }

  // ────────────────────────── 端到端 ──────────────────────────

  /**
   * 前端 {@code fromLogicFlow} 的真实产物：带 {@code title / position}，{@code config} 在顶层（没有 {@code
   * properties}），键名全是契约名。
   */
  private static String frontendGraph() {
    return """
    {"nodes":[
      {"id":"start-1","type":"START_NODE","title":"开始","position":{"x":100,"y":200},
       "config":{"prologue":"你好，请描述问题","quickQuestions":[]}},
      {"id":"search-1","type":"SEARCH_DATASET_NODE","title":"知识库检索","position":{"x":320,"y":200},
       "config":{"kbIds":["%s"],
                 "retrieval":{"mode":"HYBRID","threshold":0.6,"topN":5,"noRefStrategy":"REFUSE"}}},
      {"id":"ai-chat-1","type":"AI_CHAT_NODE","title":"AI 对话","position":{"x":540,"y":200},
       "config":{"modelId":"%s","rolePrompt":"你是知识助理","promptIds":[],"kbIds":[],
                 "retrieval":{"mode":"HYBRID","threshold":0.6,"topN":5,"noRefStrategy":"REFUSE"},
                 "thinkingEnabled":false,"multiTurn":false}},
      {"id":"reply-1","type":"REPLY_NODE","title":"指定回复","position":{"x":760,"y":200},
       "config":{"content":"已为你整理如下：","replyType":"content"}}
    ],
    "edges":[
      {"id":"e1","source":"start-1","target":"search-1"},
      {"id":"e2","source":"search-1","target":"ai-chat-1"},
      {"id":"e3","source":"ai-chat-1","target":"reply-1"}
    ]}
    """
        .formatted(KB_ID, MODEL_ID);
  }

  @Test
  @DisplayName("端到端：前端真实形状的图编译后真能跑完（这正是 #335 缺的那条读数）")
  void frontendShapedGraphRunsToTheEnd() {
    RecordingPort port = new RecordingPort();
    ChatPort chatPort = mock(ChatPort.class);
    when(chatPort.pickChatModel()).thenReturn(Optional.of(MODEL_ID));
    when(chatPort.complete(any(), anyString(), any(Duration.class))).thenReturn("住宿标准按职级执行。");

    WorkflowNodeExecutionRepository records = mock(WorkflowNodeExecutionRepository.class);
    List<NodeExecutor> executors =
        List.of(
            new StartNodeExecutor(),
            new SearchDatasetNodeExecutor(provider(port)),
            new AiChatNodeExecutor(chatPort),
            new ReplyNodeExecutor());

    WorkflowCompiler.Graph graph = MAPPER.readValue(frontendGraph(), WorkflowCompiler.Graph.class);
    // ★ 配置断链的回归点：前端形状的 config 必须真的到达执行器
    assertThat(graph.nodes()).allSatisfy(node -> assertThat(node.config()).isNotEmpty());

    WorkflowCompiler.CompileResult compiled =
        new WorkflowCompiler(new NodeTypeCatalog(MAPPER)).compile(graph);
    assertThat(compiled.ok()).as("编译错误：%s", compiled.errors()).isTrue();

    FlowState state =
        new LinearWorkflowExecutor(records, executors)
            .executeAndReturn(EXECUTION_ID, compiled.plan(), "住宿标准是多少？");

    assertThat(port.calls).hasSize(1);
    assertThat(state.read(SearchDatasetNodeExecutor.RESULT_KEY)).contains("第三十一条");
    assertThat(state.read(AiChatNodeExecutor.ANSWER_KEY)).isEqualTo("住宿标准按职级执行。");
    assertThat(state.read(ReplyNodeExecutor.REPLY_KEY)).isEqualTo("已为你整理如下：");
    // 四个节点各一条 SUCCEEDED 记录 —— 「跑完了」与「有记录」是两件事，都要断
    verify(records, times(4)).save(any());
  }
}
