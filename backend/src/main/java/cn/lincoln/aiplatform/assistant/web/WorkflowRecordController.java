package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowRecordQueryService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编排运行记录的只读端点（{@code #198} 剩余端点之一；契约 {@code listWorkflowRecords}）。
 *
 * <h3>★★ 这条端点在真实系统里今天产生不了一行数据</h3>
 *
 * <p>运行记录由<strong>执行引擎</strong>写入，而它排在 V1.0（{@code 08 §二} / {@code 09 §5.3}，今天 {@code workflow}
 * 模块里只有一个 {@code package-info}）。画布调试 {@code POST …/workflow/debug} 刻意<strong>不</strong>写记录。
 * 所以：端点有了、表有了、读出来永远是空列表 —— 这是如实状态，不是"功能已具备"。
 *
 * <h3>权限与隔离</h3>
 *
 * <p>★ 权限是 <strong>{@code assistant:view}</strong>，不是 {@code assistant:edit}：读运行记录不改变任何状态，
 * 而"这个应用跑过什么、错在哪"是排障的人需要看到的东西（同 {@link WorkflowVersionController} 的口径）。
 *
 * <p>★ 隔离靠应用 id 收窄：{@code {appId}} 不存在 ⇒ 404「应用不存在」，别的应用的记录<strong>不可能</strong>出现在这里 （查询里就带上了
 * {@code assistant_id}，不是查回来再过滤）—— 见 {@code WorkflowRecordQueryService#page}。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow/records")
public class WorkflowRecordController {

  private final WorkflowRecordQueryService queries;

  public WorkflowRecordController(WorkflowRecordQueryService queries) {
    this.queries = queries;
  }

  /**
   * 运行记录列表，按 {@code startedAt} <strong>倒序</strong>（新的在前），分页。
   *
   * <p>★ 从没有过记录 ⇒ <b>200 + 空列表 + total 0</b>，不是 404、也不是 null：「这个应用还没跑过」是正常状态。
   *
   * <p>★ {@code status} 是契约 {@code WorkflowRecordQuery.status} 的可选筛选，取值只能是 {@code SUCCEEDED} /
   * {@code FAILED}；别的值返回 400，而不是静默给一个空列表（空列表会被读成"没有记录"）。
   *
   * <p>★ {@code page} 从 1 开始、{@code size} 上限 100（与全项目列表端点同口径：默认 20、上限 100）。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PageResult<WorkflowRecordQueryService.View>> list(
      @PathVariable UUID appId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(queries.page(appId, status, page, size));
  }
}
