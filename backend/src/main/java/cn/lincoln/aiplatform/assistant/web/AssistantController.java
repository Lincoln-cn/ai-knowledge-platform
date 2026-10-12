package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.AssistantBundleView;
import cn.lincoln.aiplatform.assistant.application.AssistantService;
import cn.lincoln.aiplatform.assistant.application.AssistantView;
import cn.lincoln.aiplatform.assistant.application.PublishCheckResult;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用配置端点（契约 {@code frontend/src/api/assistants.ts}）—— 第 15 周 · 0.4.3。
 *
 * <h3>本批落了哪 6 个（以及其余为什么没落）</h3>
 *
 * <p>落下的是"配一个简单应用 ⇒ 发布 ⇒ 导出"这条链：{@code POST /assistants} · {@code GET /assistants/:id} · {@code PUT
 * /assistants/:id} · {@code GET /assistants/:id/publish-check} · {@code PATCH
 * /assistants/:id/published} · {@code GET /assistants/:id/export}。
 *
 * <p>其余（列表与市场筛选 · 收藏 · 标签 · 凭证 · API Key · 共享写 · 导入）仍在 {@code
 * ContractDriftTest.UNIMPLEMENTED_PREFIXES} 的 {@code /assistants} 名下 —— 那是"前端已跑在后端前面"的既有状态，
 * 每个域各自有排期（凭证/分享是第 16 周与 T302 后半，导入归导入导出那一批）。
 *
 * <h3>★ 权限点（{@code 07 §4.1}）</h3>
 *
 * <ul>
 *   <li>读（{@code GET} / 发布校验 / 导出）⇒ {@code assistant:view}
 *   <li>配置写（{@code POST} / {@code PUT}）⇒ {@code assistant:edit}
 *   <li>发布（{@code PATCH .../published}）⇒ {@code assistant:publish}
 * </ul>
 *
 * <p>★ 发布单独一个权限点是刻意的：能改配置不等于能对外发布（{@code 07} 的"数据范围与操作权限正交"同理 —— 配置是内部动作，发布是对外承诺）。
 *
 * <p>★ {@code ownerId} / {@code visibility} <strong>不在请求体里</strong>：创建时 ownerId = JWT 的 {@code
 * sub}、 visibility = {@code PRIVATE}（{@code 08 §4.4.4} 一律收窄）。请求体里多余的同名字段被忽略。
 */
@RestController
@RequestMapping("/api/v1/assistants")
public class AssistantController {

  private final AssistantService assistants;

  public AssistantController(AssistantService assistants) {
    this.assistants = assistants;
  }

  /**
   * 列表（契约 {@code listAssistants}）。★ 范围与筛选拼进查询，{@code total} 与页内条数同口径。
   *
   * <p>★ 枚举型参数（{@code type} / {@code origin} / {@code sort}）拼错 ⇒ 可读 400：收了却不筛，
   * 症状是"筛了没效果"，而它看起来像"确实没有符合条件的"。{@code favorite=true} 同理显式拒绝（收藏还没落）。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PageResult<AssistantView>> list(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Boolean published,
      @RequestParam(required = false) Boolean mine,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) Boolean favorite,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(
        assistants.list(type, published, mine, origin, favorite, tag, keyword, sort, page, size));
  }

  /** 取一个应用（含检索参数与绑定）。 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantView> get(@PathVariable UUID id) {
    return ApiResponse.ok(assistants.get(id));
  }

  /**
   * 新建应用。
   *
   * <p>★ {@code type} 必填且创建后不可改；{@code clientRequestId} 可选 —— 传了就参与幂等（同一调用方重试返回既有应用）。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AssistantView> create(
      @Valid @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.create(body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 改配置（契约里是 {@code Partial<UpsertAssistantBody>} ⇒ <strong>没传的字段保持原值</strong>）。
   *
   * <p>★ 请求体里的 {@code type} <strong>会被忽略</strong>（契约注释：更新时后端应忽略该字段）。 这里不是"读了再丢掉"：
   * 服务层的更新路径压根不读它（{@code AssistantService.update} 用 current.type()）。
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AssistantView> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpsertBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.update(id, body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  /** 发布前校验（只读）。★ 前端在配置页就调它，把不通过项提前说清（{@code 05 §455}） —— 等点"发布"才报错，是把错误推到配置者最不方便改的时刻。 */
  @GetMapping("/{id}/publish-check")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PublishCheckResult> publishCheck(@PathVariable UUID id) {
    return ApiResponse.ok(assistants.checkBeforePublish(id));
  }

  /** 发布 / 取消发布。★ 校验不过 ⇒ 6001，且应用状态不变。 */
  @PatchMapping("/{id}/published")
  @PreAuthorize("hasAuthority('assistant:publish')")
  public ApiResponse<AssistantView> setPublished(
      @PathVariable UUID id, @RequestBody PublishedBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        assistants.publish(id, body.published(), UUID.fromString(jwt.getSubject())));
  }

  /** 导出 JSON 包（配置 + 依赖清单）。★ {@code includeDocuments} 当前只回显，不影响包内容（见服务层注释）。 */
  @GetMapping("/{id}/export")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantBundleView> export(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeDocuments) {
    return ApiResponse.ok(assistants.export(id, includeDocuments));
  }

  /** 删除（软删）。★ 内置应用不可删（它是平台示范应用）。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    assistants.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  // ────────────────────────── 请求体 ──────────────────────────

  /**
   * 新建 / 更新应用的请求体（契约 {@code UpsertAssistantBody}）。
   *
   * <p>★ 这里用 <strong>Bean Validation</strong> 表达"长度"这类与语义无关的约束（{@code @Size}）， 失败由 {@code
   * GlobalExceptionHandler} 统一转成 {@code {code,message,hint}} 包络（{@code 08 §4.1}）， 不用每个端点各写一遍。
   *
   * <p>⚠️ <strong>{@code name} 上不能加 {@code @NotBlank}</strong>：契约里 {@code PUT} 是 {@code
   * Partial<UpsertAssistantBody>}（只发改动的字段），加"必填"会把合法的部分更新拒成 400。 ⇒ "必填"由服务层按创建/更新分别判（{@code
   * AssistantService.requireName}）， 比在注解里塞校验组清楚得多。
   *
   * <p>⚠️ {@code type} 是字符串而不是枚举：契约里是 {@code 'SIMPLE' | 'WORKFLOW'}，交给 Jackson 直接映射枚举时，
   * 拼错的值会得到一句面向开发者的报错；这里要的是面向用户的"应用类型只支持 SIMPLE / WORKFLOW"。
   */
  public record UpsertBody(
      @Size(max = 30, message = "应用名称不能超过 30 字") String name,
      String icon,
      @Size(max = 200, message = "描述不能超过 200 字") String description,
      String type,
      String modelId,
      @Size(max = 4000, message = "角色设定不能超过 4000 字") String rolePrompt,
      List<String> kbIds,
      List<String> promptIds,
      RetrievalBody retrieval,
      Boolean multiTurn,
      Boolean thinkingEnabled,
      @Size(max = 1000, message = "开场白不能超过 1000 字") String prologue,
      List<String> quickQuestions,
      List<String> tags,
      String clientRequestId) {

    AssistantService.Draft toDraft() {
      return new AssistantService.Draft(
          name,
          icon,
          description,
          parseType(type),
          uuid(modelId, "对话模型"),
          rolePrompt,
          uuids(kbIds, "知识库"),
          uuids(promptIds, "提示词"),
          retrieval == null ? null : retrieval.toDraft(),
          multiTurn,
          thinkingEnabled,
          prologue,
          quickQuestions,
          tags,
          clientRequestId);
    }
  }

  /** 检索参数（契约 {@code RetrievalParams}）。★ 各字段可空 ⇒ 由服务层按"缺省 / 保持原值"处理。 */
  public record RetrievalBody(
      String mode,
      Double threshold,
      Integer topN,
      String noRefStrategy,
      Integer queryCount,
      Boolean decomposeEnabled,
      Integer maxSubQuestions) {

    AssistantService.RetrievalDraft toDraft() {
      AssistantService.RetrievalDraft defaults = AssistantService.RetrievalDraft.defaults();
      return new AssistantService.RetrievalDraft(
          mode == null ? defaults.mode() : mode,
          threshold == null ? defaults.threshold() : threshold,
          topN == null ? defaults.topN() : topN,
          noRefStrategy == null ? defaults.noRefStrategy() : noRefStrategy,
          queryCount == null ? defaults.queryCount() : queryCount,
          decomposeEnabled == null ? defaults.decomposeEnabled() : decomposeEnabled,
          maxSubQuestions == null ? defaults.maxSubQuestions() : maxSubQuestions);
    }
  }

  /** 发布开关（契约 {@code setAssistantPublished}）。 */
  public record PublishedBody(boolean published) {}

  private static AssistantType parseType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return AssistantType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "应用类型只支持 SIMPLE / WORKFLOW，收到：" + raw, "创建时请选定其中之一");
    }
  }

  private static UUID uuid(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, field + "的 id 不是合法 UUID，收到：" + raw, "请从列表里重新选择");
    }
  }

  private static List<UUID> uuids(List<String> raw, String field) {
    return raw == null ? null : raw.stream().map(each -> uuid(each, field)).toList();
  }
}
