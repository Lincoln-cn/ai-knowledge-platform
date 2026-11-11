package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowCompiler;
import cn.lincoln.aiplatform.assistant.application.WorkflowDebugService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 编排应用的「试用」（{@code #326}；契约 {@code debugWorkflow}）。
 *
 * <p>★ 这个端点在 {@code ContractDriftTest} 的"未实现清单"里待了很久，也是画布「试用」按钮<strong>为什么看起来能跑</strong>的原因：
 * 前端一直没有调它，而是本地按线性顺序跑（{@code api/workflow.ts:63}）。从这一版起后端真的接了。
 *
 * <p>★ 仍然手动从 {@link JsonNode} 取字段（同 {@link WorkflowValidationController}）：图里带着 {@code position} /
 * {@code config} 等这一层不判的东西，绑成记录就得让它们跟着 fail-fast。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowDebugController {

  /** 读图用；与项目其它地方同一套 Jackson（{@code JacksonUnificationTest} 只放行 {@code tools.jackson}）。 */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final WorkflowDebugService debugs;

  public WorkflowDebugController(WorkflowDebugService debugs) {
    this.debugs = debugs;
  }

  /**
   * 跑一次试用，并落一行运行记录（{@code conversationId} 为空 —— 试用不属于任何会话）。
   *
   * <p>★ 权限用 {@code assistant:edit} 而不是 {@code assistant:view}：试用的目的是"发布之前先看看"，是编辑动作的一部分；
   * 给只读角色开这个口子等于让他能触发模型调用。
   */
  @PostMapping("/debug")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<WorkflowDebugService.Result> debug(
      @PathVariable UUID appId, @RequestBody JsonNode body) {
    return ApiResponse.ok(
        debugs.debug(appId, compilerGraph(body.path("graph")), entryInput(body.path("inputs"))));
  }

  /**
   * 把请求里的图读成编译器要的形状。
   *
   * <p>★ 这里<strong>不能</strong>用 {@link WorkflowGraphReader#graph}：那个只取校验要的 {@code id / type /
   * title}， 而编译器还要 {@code properties.config}（检索节点的 {@code dataset_id_list}、AI 节点的 {@code modelId}
   * 都在里面）。 用错一个的后果是"编译能过、执行时每个节点都缺配置"。
   *
   * <p>★ 直接反序列化而不是逐个字段手工取：{@code properties} 是原样保留的画布配置（Jackson 直映射这一层），手工取会把 nested 的 {@code
   * config} 拍平；用 {@code JsonMapper} 则与前端契约 {@code WorkflowGraph} 保持同一形状。
   */
  private static WorkflowCompiler.Graph compilerGraph(JsonNode graph) {
    if (graph == null || graph.isNull() || !graph.isObject()) {
      return new WorkflowCompiler.Graph(List.of(), List.of());
    }
    try {
      return MAPPER.treeToValue(graph, WorkflowCompiler.Graph.class);
    } catch (RuntimeException ex) {
      // 读不出来当空图处理：编译器会给出"缺少开始节点"，比一个 400 的通用文案更容易定位。
      return new WorkflowCompiler.Graph(List.of(), List.of());
    }
  }

  /**
   * 从 {@code inputs}（{@code {nodeId: text}}）里取这一次的用户输入。
   *
   * <p>★ 取<strong>第一个非空值</strong>而不是把所有值拼起来：线性图只有一个入口，拼起来会让第二个节点的输入变成"追加在用户问题后面 的文本"，而那正是执行器 {@code
   * input} 参数不该承担的东西。多值拼接要等条件分支能并行时才需要（{@code §11.27.1} 0.7.2）。
   */
  private static String entryInput(JsonNode inputs) {
    if (inputs == null || !inputs.isObject()) {
      return "";
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = inputs.properties().iterator();
        it.hasNext(); ) {
      JsonNode value = it.next().getValue();
      if (value != null && !value.isNull()) {
        String text = value.asString("");
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return "";
  }
}
