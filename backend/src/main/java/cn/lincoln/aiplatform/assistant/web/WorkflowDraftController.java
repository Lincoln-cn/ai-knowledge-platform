package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.WorkflowDraftService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 编排画布的草稿读写（{@code #198} 第一块；契约 {@code getWorkflowDraft} / {@code saveWorkflowDraft}）。
 *
 * <p>★ 权限用 {@code assistant:edit} 而不是 {@code assistant:view}：读草稿是为了改它 ——
 * 画布只对能编辑的人有意义；只读的人看编排应该看<strong>已发布版本</strong>，不是别人正在画的草稿。
 *
 * <p>★ 库里存的是 {@code jsonb}，但在<strong>线上</strong>它是<strong>对象</strong>（{@code {graph: {nodes,
 * edges}}}）： 契约 {@code getWorkflowDraft} / {@code saveWorkflowDraft} 声明的就是对象。曾经这里两处都是 {@code
 * String}， 于是画布对着真后端保存会 400、读回来拿到的是一个字符串 —— 而 mock 一直按对象回，两边读数一样（{@code #262}）。 ⇒ 存储形状（jsonb
 * 文本）与传输形状（JSON 对象）在这里分开，转换只发生在本类。
 */
@RestController
@RequestMapping("/api/v1/assistants/{appId}/workflow")
public class WorkflowDraftController {

  private final WorkflowDraftService drafts;
  private final ObjectMapper mapper;

  public WorkflowDraftController(WorkflowDraftService drafts, ObjectMapper mapper) {
    this.drafts = drafts;
    this.mapper = mapper;
  }

  /** 读草稿。没保存过时返回空图 + {@code updatedAt: null}（"还没画"是正常状态，不是 404）。 */
  @GetMapping("/draft")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Body> get(@PathVariable UUID appId) {
    WorkflowDraftService.View view = drafts.get(appId);
    return ApiResponse.ok(new Body(readGraph(view.graph()), view.updatedAt()));
  }

  /** 保存草稿。★ 允许不合规（边画边存）；校验在发布前。 */
  @PutMapping("/draft")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Saved> save(@PathVariable UUID appId, @RequestBody SaveBody body) {
    WorkflowDraftService.Saved saved =
        drafts.save(appId, body == null ? null : writeGraph(body.graph()), Instant.now());
    return ApiResponse.ok(new Saved(saved.savedAt()));
  }

  public record Body(JsonNode graph, Instant updatedAt) {}

  public record SaveBody(JsonNode graph) {}

  /**
   * 库里的 jsonb 文本 ⇒ 线上对象。
   *
   * <p>★ 解析不出来<strong>抛</strong>而不是回空图：空图会让「草稿被人写坏了」看起来像「还没画」， 而后者是正常状态 —— 两者读数一样，排障时无从下手。
   */
  private JsonNode readGraph(String stored) {
    try {
      return mapper.readTree(stored);
    } catch (JacksonException e) {
      throw new IllegalStateException("草稿内容不是合法 JSON，读不回来 —— 库里那一行被人写坏了", e);
    }
  }

  /**
   * 线上对象 ⇒ 库里的 jsonb 文本。
   *
   * <p>★ 只收<strong>对象</strong>：{@code {"graph":"{...}"}}（字符串形状）若被放行会存成一个被引号包住的字符串， 读回来就是一层转义 ——
   * 前端拿到的仍不是图，症状与没改之前一模一样。
   */
  private String writeGraph(JsonNode graph) {
    if (graph == null) {
      return null;
    }
    if (!graph.isObject()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "草稿必须是图对象（{nodes, edges}）", "不要把它序列化成字符串再传 —— 这里要的是 JSON 对象");
    }
    return mapper.writeValueAsString(graph);
  }

  public record Saved(Instant savedAt) {}
}
