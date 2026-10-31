package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowValidationService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 发布前校验（{@code #198} 第三块；契约 {@code validateWorkflow}）。
 *
 * <p>★ 手动从 {@link JsonNode} 里取校验需要的字段（见 {@link WorkflowGraphReader}），而不是把请求体绑成服务里的记录： 图里还带着 {@code
 * position} / {@code config} 等这一层不判的东西，绑成记录就得让它们跟着 fail-fast， 结果"多传一个字段"变成 400 —— 而校验端点最不该因为这件事拒绝。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowValidationController {

  private final WorkflowValidationService validations;

  public WorkflowValidationController(WorkflowValidationService validations) {
    this.validations = validations;
  }

  /** 权限与校验的目的对齐：草稿是用来改的，校验是改完准备发布那一步。 */
  @PostMapping("/validate")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<WorkflowValidationService.Result> validate(
      @PathVariable UUID appId, @RequestBody JsonNode body) {
    return ApiResponse.ok(validations.validate(WorkflowGraphReader.graph(body.path("graph"))));
  }
}
