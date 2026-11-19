package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.PromptEvalService;
import cn.lincoln.aiplatform.assistant.domain.EvalVerdict;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词评测的端点（ROADMAP §11.63.3）。
 *
 * <p>★ 路径分两族：评测集与题挂在 `prompt-configs` / `prompt-eval-sets` 下（它们是输入， 跟着提示词走）；运行与评分挂在
 * `prompt-eval-runs` 下（它们是产出，一次运行一条记录）。
 *
 * <p>★ 起运行是 {@code 201} 且**立刻返回 runId** —— 真正跑在后台，前端拿 runId 轮询 {@code GET /prompt-eval-runs/{id}}。
 * 不在这里同步等结果：30 题 = 60 次模型调用，HTTP 早超时了。
 */
@RestController
@RequestMapping("/api/v1")
public class PromptEvalController {

  private final PromptEvalService eval;

  public PromptEvalController(PromptEvalService eval) {
    this.eval = eval;
  }

  // ── 评测集 ────────────────────────────────────────────────────

  @GetMapping("/prompt-configs/{id}/eval-sets")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<PromptEvalService.SetView>> listSets(@PathVariable UUID id) {
    return ApiResponse.ok(eval.listSets(id));
  }

  @PostMapping("/prompt-configs/{id}/eval-sets")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<CreateSetResult> createSet(
      @PathVariable UUID id, @RequestBody NameBody body, @AuthenticationPrincipal Jwt jwt) {
    UUID setId = eval.createSet(id, body.name(), UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok(new CreateSetResult(setId));
  }

  @DeleteMapping("/prompt-eval-sets/{setId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public void deleteSet(@PathVariable UUID setId) {
    eval.deleteSet(setId);
  }

  // ── 题目 ──────────────────────────────────────────────────────

  @GetMapping("/prompt-eval-sets/{setId}/items")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<PromptEvalService.ItemView>> listItems(@PathVariable UUID setId) {
    return ApiResponse.ok(eval.listItems(setId));
  }

  @PostMapping("/prompt-eval-sets/{setId}/items")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<PromptEvalService.ItemView> addItem(
      @PathVariable UUID setId, @RequestBody ItemBody body) {
    return ApiResponse.ok(eval.addItem(setId, body.question()));
  }

  @DeleteMapping("/prompt-eval-items/{itemId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public void deleteItem(@PathVariable UUID itemId) {
    eval.deleteItem(itemId);
  }

  // ── 运行 ──────────────────────────────────────────────────────

  /**
   * 起一次评测。
   *
   * <p>{@code appId} 是**执行环境**（问题要经过真实检索才有意义）。{@code draftText} 是输入框里那版正文， 整段落库 —— 它可能永远不会被保存。
   */
  @PostMapping("/prompt-eval-sets/{setId}/runs")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<CreateRunResult> startRun(
      @PathVariable UUID setId, @RequestBody RunBody body, @AuthenticationPrincipal Jwt jwt) {
    UUID runId =
        eval.startRun(setId, body.appId(), body.draftText(), UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok(new CreateRunResult(runId));
  }

  /** 取一次运行的进度与答案（{@code status = RUNNING} 时前端轮询它）。 */
  @GetMapping("/prompt-eval-runs/{runId}")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PromptEvalService.RunView> getRun(@PathVariable UUID runId) {
    return ApiResponse.ok(eval.getRun(runId));
  }

  /** 人工评一档：评分写在「草稿相对基线」这一侧。 */
  @PutMapping("/prompt-eval-runs/{runId}/items/{itemId}/rating")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Void> rate(
      @PathVariable UUID runId,
      @PathVariable UUID itemId,
      @RequestBody RatingBody body,
      @AuthenticationPrincipal Jwt jwt) {
    eval.rate(runId, itemId, body.verdict(), body.note(), UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok(null);
  }

  /** 清空评价（改主意、或误点）。 */
  @DeleteMapping("/prompt-eval-runs/{runId}/items/{itemId}/rating")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Void> clearRating(@PathVariable UUID runId, @PathVariable UUID itemId) {
    eval.clearRating(runId, itemId);
    return ApiResponse.ok(null);
  }

  // ── 请求体 ────────────────────────────────────────────────────

  public record NameBody(String name) {}

  public record ItemBody(String question) {}

  public record RunBody(UUID appId, String draftText) {}

  public record RatingBody(EvalVerdict verdict, String note) {}

  public record CreateSetResult(UUID id) {}

  public record CreateRunResult(UUID id) {}
}
