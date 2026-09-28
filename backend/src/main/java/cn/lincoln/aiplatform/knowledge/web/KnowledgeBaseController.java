package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseService;
import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseView;
import cn.lincoln.aiplatform.knowledge.application.KnowledgeCategoryView;
import cn.lincoln.aiplatform.knowledge.domain.ImportType;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.validation.constraints.NotBlank;
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
 * 知识库端点（契约 {@code frontend/src/api/knowledge.ts} 的 7 个端点）。
 *
 * <p>★ 权限点（{@code 07 §4.1}）：
 *
 * <ul>
 *   <li>{@code GET} ⇒ {@code knowledge:view}
 *   <li>{@code POST} ⇒ {@code knowledge:create}
 *   <li>{@code PUT} / {@code DELETE} / {@code PATCH} ⇒ {@code knowledge:manage}
 * </ul>
 *
 * <p>★ 这些写端点<strong>必须</strong>带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest}
 * 会在构建期检查，漏一个就红。
 *
 * <p>★ {@code ownerId} / {@code visibility} <strong>不在请求体里</strong>： 创建时 ownerId = JWT 的 {@code
 * sub}、visibility = {@code PRIVATE}（{@code 08 §4.4.4} 一律收窄）。 请求体里多余的同名字段被忽略（契约里本就没有这些请求字段）。
 *
 * <p>★ 路径上 {@code /categories} 必须声明在 {@code /{id}} 之前可读性更好； Spring MVC
 * 的路径匹配会优先选字面量更具体的模式，所以顺序不影响正确性， 但写在一起是为了让"这两条会抢路径"这件事在代码里可见。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

  private final KnowledgeBaseService knowledgeBaseService;

  public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
    this.knowledgeBaseService = knowledgeBaseService;
  }

  // ────────────────────────── 查询 ──────────────────────────

  /**
   * 分页列表。★ 范围（{@code dataScope}）与筛选<strong>拼进查询</strong>，不是查完再筛 —— {@code total} 与页内条数同口径。
   *
   * @param mine 只看自己名下的（契约 {@code KnowledgeBaseQuery.mine}）；不传 = 按数据范围给全部可见的
   */
  @GetMapping
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<PageResult<KnowledgeBaseView>> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean mine,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(knowledgeBaseService.list(category, enabled, mine, keyword, page, size));
  }

  /** 分类清单（含每个分类下的可见库数）。 */
  @GetMapping("/categories")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<List<KnowledgeCategoryView>> categories() {
    return ApiResponse.ok(knowledgeBaseService.categories());
  }

  /** 单条详情。★ 看不见的库返 <strong>404</strong> 而不是 403。 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<KnowledgeBaseView> get(@PathVariable UUID id) {
    return ApiResponse.ok(knowledgeBaseService.get(id));
  }

  // ────────────────────────── 写 ──────────────────────────

  /**
   * 建库。★ 幂等：同一 {@code clientRequestId} 重复创建返回既有库（见 {@code KnowledgeBaseService} 类注释）。
   *
   * <p>★ {@code maxClassification}（ceiling）<strong>必填</strong>： 缺失 ⇒ 400「库的密级上限必填」（{@code 13 §6}）。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('knowledge:create')")
  public ApiResponse<KnowledgeBaseView> create(
      @RequestBody CreateKnowledgeBaseBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        knowledgeBaseService.create(
            body.name(),
            body.category(),
            body.description(),
            body.icon(),
            body.importType(),
            parseUuid(body.embeddingModelId(), "向量模型"),
            body.maxClassification(),
            body.clientRequestId(),
            UUID.fromString(jwt.getSubject())));
  }

  /** 改库。★ ownerId 不可改 —— 转让是显式动作，不是改个字段。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<KnowledgeBaseView> update(
      @PathVariable UUID id,
      @RequestBody UpdateKnowledgeBaseBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        knowledgeBaseService.update(
            id,
            body.name(),
            body.category(),
            body.description(),
            body.icon(),
            body.maxClassification(),
            UUID.fromString(jwt.getSubject())));
  }

  /** 启用/停用。停用后不参与检索，数据保留。 */
  @PatchMapping("/{id}/enabled")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<KnowledgeBaseView> setEnabled(
      @PathVariable UUID id, @RequestBody EnabledBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        knowledgeBaseService.setEnabled(id, body.enabled(), UUID.fromString(jwt.getSubject())));
  }

  /** 删除。★ 连同文档与分段一并移除是后续段的职责；本段先落聚合根软删。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    knowledgeBaseService.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  // ────────────────────────── 请求体 ──────────────────────────

  /**
   * 建库请求体（契约 {@code CreateKnowledgeBaseBody}）。
   *
   * <p>★ <b>没有 ownerId / visibility</b>：它们由后端写入（{@code 08 §4.4.4}）。 请求体里多余的同名 JSON 字段被 Jackson 忽略
   * —— 关键是服务端写入的值不被覆盖。
   *
   * @param maxClassification ceiling（{@code 13 §6}）。★ <b>必填</b> —— 服务层再校验一次，错误文案说清「库的密级上限必填」
   * @param clientRequestId 幂等键；同一键重复创建返回既有库
   */
  record CreateKnowledgeBaseBody(
      @NotBlank String name,
      String category,
      String description,
      String icon,
      ImportType importType,
      String embeddingModelId,
      ClassificationLevel maxClassification,
      String clientRequestId) {}

  /**
   * 改库请求体（契约 {@code Partial<CreateKnowledgeBaseBody>}）—— 全字段可选，只改传了的那些。
   *
   * <p>★ 不含 {@code importType}（BR-03-01：创建时选定后不可更改）与 {@code clientRequestId}（幂等键属于创建）。
   */
  record UpdateKnowledgeBaseBody(
      String name,
      String category,
      String description,
      String icon,
      ClassificationLevel maxClassification) {}

  /** 启用位请求体（契约 {@code setKnowledgeBaseEnabled} 的 body）。 */
  record EnabledBody(boolean enabled) {}

  /**
   * 把契约里的字符串 id 转成 UUID。
   *
   * <p>★ 契约把 id 声明为 {@code string}，而"不是合法 UUID"的输入必须变成 <strong>400</strong> 而不是 500 ——
   * 前者是"你传错了"（调用方自己能修），后者是"我崩了"（得来找我）。
   */
  private static UUID parseUuid(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不能为空");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 不是合法的 id：" + raw);
    }
  }
}
