package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowRollbackService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回滚到某一历史版本（契约 {@code rollbackWorkflow}）。
 *
 * <p>★★ 这个端点的语义要读准确：<strong>把历史版本的图写回草稿</strong>。它<strong>不发布、不切换线上</strong> ——
 * 回滚之后线上跑的仍然是原来那一版，直到用户自己点「发布」。所以它的权限与草稿保存同级（{@code assistant:edit}），
 * 而不是与"发布"同级；响应里的东西也只是<strong>草稿</strong>。这条写进类注释的理由很直接：光看路由 {@code
 * …/versions/{key}/rollback}，"回滚"两个字极易被读成"线上回到那一版"。
 *
 * <p>★ 权限与 {@link WorkflowDraftController}、{@link WorkflowPublishController} 同一个形状（都是 {@code
 * assistant:edit}）：回滚改的是草稿，属于"能改这个应用的人"的动作；只读的人看版本图走 {@code GET …/versions/{key}}（那边是 {@code
 * assistant:view}）。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowRollbackController {

  private final WorkflowRollbackService rollbacks;

  public WorkflowRollbackController(WorkflowRollbackService rollbacks) {
    this.rollbacks = rollbacks;
  }

  /**
   * 把版本 {@code versionId} 的图写回草稿，返回写回后的草稿（含 {@code graph} / {@code updatedAt} / {@code version}）。
   *
   * <p>★ <strong>没有请求体</strong>：版本号在路径里，回滚没有别的参数（不是"再发一次发布"，图不从客户端来 —— 客户端给图 就成了"另一次保存草稿"，那件事已经有
   * {@code PUT /workflow/draft}）。
   *
   * <p>★ 用 {@code POST} 而不是 {@code PUT}：契约冻结的就是 POST；从语义上讲它是一次<strong>动作</strong>（把旧图抄回草稿，
   * 且每次执行都会推进草稿的 {@code updatedAt}），不是"把这个资源设成那个值"。
   *
   * <p>★ 404 而不是 403（版本属于别的应用时也一样 404）：<strong>存在性不该被泄露</strong> —— 403 等于回答"这一版确实存在， 只是不归你"，而
   * {@code 853a11d} 已经在读取侧确立了同一判据（用例 {@code anotherAppsVersionIsNotReadable}）。 写侧若换成
   * 403，就是同一个问题在两处有两个答案。
   */
  @PostMapping("/versions/{versionId}/rollback")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<WorkflowRollbackService.RolledBack> rollback(
      @PathVariable UUID appId, @PathVariable String versionId) {
    return ApiResponse.ok(rollbacks.rollback(appId, versionId));
  }
}
