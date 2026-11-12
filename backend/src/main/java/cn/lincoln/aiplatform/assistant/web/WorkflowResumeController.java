package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowCompiler;
import cn.lincoln.aiplatform.assistant.application.WorkflowResumeService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
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
 * 从暂停点恢复一次执行。
 *
 * <p>★ 权限与「试用」同档（{@code assistant:edit}）：恢复会让流程继续跑模型与检索， 给只读角色开这个口子等于让他能触发模型调用。
 */
@RestController
@RequestMapping("/api/v1/assistants/{assistantId}/workflow")
public class WorkflowResumeController {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final WorkflowResumeService resumes;

  public WorkflowResumeController(WorkflowResumeService resumes) {
    this.resumes = resumes;
  }

  /**
   * 提交一次恢复。
   *
   * <p>★ body 要带 {@code graph}：恢复必须用**当前**的图编译一次计划，再与快照的 {@code stepsDone} 比。
   * 不从库里读草稿是因为「试用」本来就跑的是未保存的当前画布 —— 两边口径要一致， 否则「试用看到的」与「恢复跑起来的」会是两张图。
   */
  @PostMapping("/resume")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<WorkflowResumeService.Result> resume(
      @PathVariable UUID assistantId, @RequestBody JsonNode body) {
    UUID executionId = UUID.fromString(body.path("executionId").asString(""));
    String answer = body.path("answer").asString("");

    // ★ 与调试端点同一条理由：WorkflowGraphReader 只取 id/type/title，而编译器还要 properties.config
    //   （dataset_id_list / modelId 都在里面），所以这里直接反序列化成 WorkflowCompiler.Graph。
    WorkflowCompiler.Graph graph =
        MAPPER.treeToValue(body.path("graph"), WorkflowCompiler.Graph.class);

    return ApiResponse.ok(resumes.resume(assistantId, executionId, graph, answer));
  }
}
