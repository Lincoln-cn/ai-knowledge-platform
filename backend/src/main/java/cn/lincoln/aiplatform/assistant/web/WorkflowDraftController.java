package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowDraftService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排画布的草稿读写（{@code #198} 第一块；契约 {@code getWorkflowDraft} / {@code saveWorkflowDraft}）。
 *
 * <p>★ 权限用 {@code assistant:edit} 而不是 {@code assistant:view}：读草稿是为了改它 ——
 * 画布只对能编辑的人有意义；只读的人看编排应该看<strong>已发布版本</strong>，不是别人正在画的草稿。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowDraftController {

  private final WorkflowDraftService drafts;

  public WorkflowDraftController(WorkflowDraftService drafts) {
    this.drafts = drafts;
  }

  /** 读草稿。没保存过时返回空图 + {@code updatedAt: null}（"还没画"是正常状态，不是 404）。 */
  @GetMapping("/draft")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Body> get(@PathVariable UUID appId) {
    WorkflowDraftService.View view = drafts.get(appId);
    return ApiResponse.ok(new Body(view.graph(), view.updatedAt()));
  }

  /** 保存草稿。★ 允许不合规（边画边存）；校验在发布前。 */
  @PutMapping("/draft")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Saved> save(@PathVariable UUID appId, @RequestBody SaveBody body) {
    WorkflowDraftService.Saved saved =
        drafts.save(appId, body == null ? null : body.graph(), Instant.now());
    return ApiResponse.ok(new Saved(saved.savedAt()));
  }

  public record Body(String graph, Instant updatedAt) {}

  public record SaveBody(String graph) {}

  public record Saved(Instant savedAt) {}
}
