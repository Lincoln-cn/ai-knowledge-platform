package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowVersionQueryService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 已发布版本的只读端点（{@code #198} 第五块）。
 *
 * <p>★ 两个端点的权限都是 <strong>{@code assistant:view}</strong>，不是 {@code
 * assistant:edit}：这一族是<strong>读</strong> —— "线上现在跑的是哪一版"、"某一版长什么样"，是任何能看这个应用的人都该看得到的东西，读它不改变任何状态。
 * 写侧（发布、回滚）才是 {@code assistant:edit}。
 *
 * <p>★ 与草稿端点故意不一致：{@code /workflow/draft} 要 {@code assistant:edit}，因为草稿是"正在画的那张"，
 * 只有能改的人需要看它的中间态；已发布版本是<strong>线上事实</strong>，用更宽的权限不会让人看到不该看的 —— 版本仍按应用隔离，跨应用取用一律 404（见 {@link
 * WorkflowVersionQueryService#require}）。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow/versions")
public class WorkflowVersionController {

  private final WorkflowVersionQueryService queries;

  public WorkflowVersionController(WorkflowVersionQueryService queries) {
    this.queries = queries;
  }

  /**
   * 版本列表，按 {@code version} <strong>降序</strong>（新的在前）。
   *
   * <p>★ 列表刻意不带图：图随历史线性膨胀，而列表要回答的是"有哪几版、谁发的、各几个节点"。 点开某一版看长什么样，是下面那个端点。
   *
   * <p>★ 从没发布过 ⇒ <b>200 + 空数组</b>，不是 404：「这个应用还没发布过」是正常状态。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<WorkflowVersionQueryService.Version>> list(@PathVariable UUID appId) {
    return ApiResponse.ok(queries.list(appId));
  }

  /**
   * 某一版的<strong>图快照</strong>（契约 {@code WorkflowGraph} = {@code {nodes, edges}}）。
   *
   * <p>★ 响应体是<strong>裸图</strong>而不是 {@code {graph: …}}：契约 {@code getWorkflowVersionGraph} 的返回类型就是
   * {@code WorkflowGraph}，前端拿到它直接交给 {@code renderGraphData}；多包一层会让页面读到 {@code undefined.nodes}。
   *
   * <p>★ 路径段接受版本行主键（UUID）与该应用内的版本号（整数）两种写法，理由见 {@link
   * WorkflowVersionQueryService#require}：契约与页面传的是后者，台账命名是前者，只认一种另一种就会静默 404。
   *
   * <p>★ 不存在与不属于这个应用都是 404、同一句文案：区分它们等于告诉越权者"这个 id 真实存在"。
   */
  @GetMapping("/{versionId}")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<JsonNode> graph(@PathVariable UUID appId, @PathVariable String versionId) {
    return ApiResponse.ok(queries.graph(appId, versionId));
  }
}
