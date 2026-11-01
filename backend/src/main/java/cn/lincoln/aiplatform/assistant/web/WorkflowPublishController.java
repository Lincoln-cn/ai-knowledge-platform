package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowPublishService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 发布编排版本（{@code #198} 第四块；契约 {@code publishWorkflow}）。
 *
 * <p>★ 权限与草稿一致（{@code assistant:edit}）：发布是"把画布推上线"，属于能改这个应用的人。 没有单独的发布权限点不是漏项 ——
 * 现阶段"能改草稿"就必然"能发布"，多一个权限点只会多一处要维护且没人会单独授予的授权。
 *
 * <p>★ 请求体手动解析（见 {@link WorkflowGraphReader}）：图里那些这一层不判的字段不该让请求变成 400。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowPublishController {

  private final WorkflowPublishService publishes;

  public WorkflowPublishController(WorkflowPublishService publishes) {
    this.publishes = publishes;
  }

  @PostMapping("/publish")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<WorkflowPublishService.Published> publish(
      @PathVariable UUID appId, @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
    JsonNode graph = body.path("graph");
    return ApiResponse.ok(
        publishes.publish(
            appId,
            WorkflowGraphReader.json(graph),
            WorkflowGraphReader.graph(graph),
            WorkflowGraphReader.note(body),
            UUID.fromString(jwt.getSubject())));
  }
}
