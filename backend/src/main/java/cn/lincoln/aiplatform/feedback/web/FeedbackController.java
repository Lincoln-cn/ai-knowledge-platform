package cn.lincoln.aiplatform.feedback.web;

import cn.lincoln.aiplatform.feedback.application.FeedbackService;
import cn.lincoln.aiplatform.feedback.application.FeedbackService.BatchResult;
import cn.lincoln.aiplatform.feedback.application.FeedbackService.FeedbackPage;
import cn.lincoln.aiplatform.feedback.application.FeedbackService.FeedbackView;
import cn.lincoln.aiplatform.feedback.application.FeedbackService.Rejected;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈管理 6 个端点（Issue #194，契约 {@code frontend/src/api/feedback.ts}）。
 *
 * <p>★ 全部要求 {@code feedback:handle}：这是运营动作，不是“登录就能看”。页面上的批量/处理/重开按钮也用同一个点。
 *
 * <p>★ 批量办理在控制器里逐条调 {@link FeedbackService#handle}，每条走自己的事务；失败项只回该 id 与原因， 已办成的照常提交。禁止把“整批一个事务 +
 * 全部成功回执”写回来 —— 那正是 #194 判据要挡的假绿。
 */
@RestController
@RequestMapping("/api/v1/feedbacks")
@PreAuthorize("hasAuthority('feedback:handle')")
public class FeedbackController {

  private final FeedbackService feedback;

  public FeedbackController(FeedbackService feedback) {
    this.feedback = feedback;
  }

  @GetMapping
  public ApiResponse<FeedbackPage> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID appId,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = jwt == null ? null : UUID.fromString(jwt.getSubject());
    return ApiResponse.ok(feedback.list(status, appId, mine, userId, keyword, page, size));
  }

  @GetMapping("/stats")
  public ApiResponse<Map<String, Long>> stats() {
    return ApiResponse.ok(feedback.stats());
  }

  @GetMapping("/{id}")
  public ApiResponse<FeedbackView> get(@PathVariable UUID id) {
    return ApiResponse.ok(feedback.get(id));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('feedback:handle')")
  public ApiResponse<FeedbackView> handle(
      @PathVariable UUID id, @RequestBody HandleFeedbackBody body) {
    return ApiResponse.ok(
        feedback.handle(
            id, body.status(), body.assignee(), body.resolution(), body.promoteToKbId()));
  }

  @PatchMapping("/batch")
  @PreAuthorize("hasAuthority('feedback:handle')")
  public ApiResponse<BatchResult> batch(@RequestBody BatchHandleBody body) {
    List<FeedbackView> accepted = new ArrayList<>();
    List<Rejected> rejected = new ArrayList<>();
    String assignee = body.assigneeId() == null ? null : feedback.assigneeName(body.assigneeId());
    for (UUID id : body.ids() == null ? List.<UUID>of() : body.ids()) {
      try {
        accepted.add(feedback.handle(id, body.status(), assignee, body.resolution(), null));
      } catch (BusinessException e) {
        rejected.add(new Rejected(id, e.getMessage()));
      }
    }
    return ApiResponse.ok(new BatchResult(accepted, rejected));
  }

  @PostMapping("/{id}/reopen")
  @PreAuthorize("hasAuthority('feedback:handle')")
  public ApiResponse<FeedbackView> reopen(@PathVariable UUID id) {
    return ApiResponse.ok(feedback.reopen(id));
  }

  public record HandleFeedbackBody(
      String status, String assignee, String resolution, UUID promoteToKbId) {}

  public record BatchHandleBody(
      List<UUID> ids, String status, UUID assigneeId, String resolution) {}
}
