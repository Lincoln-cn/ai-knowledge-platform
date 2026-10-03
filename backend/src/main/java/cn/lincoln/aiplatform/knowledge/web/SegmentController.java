package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.BatchDeleteSegmentsView;
import cn.lincoln.aiplatform.knowledge.application.BatchMoveView;
import cn.lincoln.aiplatform.knowledge.application.SegmentService;
import cn.lincoln.aiplatform.knowledge.application.SegmentView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
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
 * 分段端点（契约 {@code frontend/src/api/ingestion.ts} 的 {@code listSegments} / {@code updateSegment}）。
 *
 * <p>★ 权限点（{@code 07 §4.1}）：
 *
 * <ul>
 *   <li>{@code GET} 列表 ⇒ {@code knowledge:view}
 *   <li>{@code PUT} 编辑 ⇒ {@code knowledge:segment:edit}
 * </ul>
 *
 * <p>★ 写端点<strong>必须</strong>带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest}
 * 构建期检查，漏一个就红。
 *
 * <p>★ 可见性继承库：库不可见 ⇒ 其分段读/写都 <strong>404</strong>（不是 403）—— 与批 1 同一判定。
 */
@RestController
@RequestMapping("/api/v1/segments")
public class SegmentController {

  private final SegmentService segmentService;

  public SegmentController(SegmentService segmentService) {
    this.segmentService = segmentService;
  }

  /**
   * 分页列分段（契约 {@code listSegments}）。
   *
   * <p>★ 数据范围拼进查询（{@code 08 §4.4.3} 纪律 1）：不可见库的分段不得返回。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<PageResult<SegmentView>> list(
      @RequestParam(required = false) UUID documentId,
      @RequestParam(required = false) Boolean onlyOversized,
      @RequestParam(required = false) UUID kbId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(segmentService.list(documentId, onlyOversized, kbId, page, size));
  }

  /**
   * 按 id 取一条分段（契约 {@code getSegment}，{@code conversations.ts} 的引用回溯用）。
   *
   * <p>★ 不可见/不存在 ⇒ 404（不泄漏存在性）。
   */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<SegmentView> get(@PathVariable UUID id) {
    return ApiResponse.ok(segmentService.get(id));
  }

  /**
   * 编辑分段（契约 {@code updateSegment}）。
   *
   * <p>★ 改 {@code content} ⇒ 重跑 SEG-I1（超长 400）、重算 {@code searchText} 与 {@code contentHash}
   * （前缀仍用该文档的文种/部门/名），并把 {@code embeddingStatus} 置回 {@code PENDING_EMBEDDING}。
   *
   * <p>★ 越权/不可见 ⇒ 404（不泄漏存在性）。
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<SegmentView> update(
      @PathVariable UUID id,
      @RequestBody UpdateSegmentBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        segmentService.update(id, body.title(), body.content(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 启用/停用分段（契约 {@code setSegmentEnabled}）。
   *
   * <p>★ 权限点 {@code knowledge:segment:edit} —— 与前端那个开关的可见条件一致（{@code SegmentEditDialog}）。
   */
  @PatchMapping("/{id}/enabled")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<SegmentView> setEnabled(
      @PathVariable UUID id, @RequestBody EnabledBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        segmentService.setEnabled(id, body.enabled(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 单段重新向量化（契约 {@code reembedSegment}）。
   *
   * <p>★ 它复用文档级执行器（只重算未完成的分段），理由见 {@code SegmentService#reembed}。
   */
  @PostMapping("/{id}/embedding")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<SegmentView> reembed(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(segmentService.reembed(id, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 删除分段（契约 {@code deleteSegment}）。
   *
   * <p>★ 分段是子实体、没有软删列 ⇒ 真删；删完回填文档进度列并重编剩余序号、状态置回「待向量化」（向量旧了）。
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    segmentService.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  // ────────────────────────── 批量动作（契约的两条 P1） ──────────────────────────

  /**
   * 批量删除分段（契约 {@code batchDeleteSegments}）—— <strong>尽力而为</strong>，回执区分 {@code deleted} 与 {@code
   * failed}。
   *
   * <p>★ 路径用字面量 {@code /batch} 而不是让 {@code /{id}} 去接：Spring 的路径匹配**字面量优先于占位符**， 所以 {@code DELETE
   * /segments/batch} 会进到这里 —— 而在落这条之前，它被 {@code DELETE /segments/{id}}
   * 接走了，返回的是「参数类型不正确：id」（一个把"功能没做"说成"你传错了"的报错）。
   */
  @DeleteMapping("/batch")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<BatchDeleteSegmentsView> batchDelete(
      @RequestBody BatchIdsBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        segmentService.batchDelete(body.ids(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 批量迁移分段（契约 {@code batchMoveSegments}）—— 搬移语义：源删除 + 目标写入。
   *
   * <p>★ 整批全成或全不成（目标是同一篇文档、目标不可见、任一段在目标前缀下超长 ⇒ 整批拒绝并说清原因）： 半途而废的搬移会把一篇文档的分段**劈到两个地方**，而两边都显示得挺正常。
   */
  @PostMapping("/batch-move")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<BatchMoveView> batchMove(
      @RequestBody BatchMoveBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        segmentService.batchMove(
            body.segmentIds(), body.targetDocumentId(), UUID.fromString(jwt.getSubject())));
  }

  /** 编辑请求体（契约 {@code updateSegment} 的 body）。 */
  record UpdateSegmentBody(String title, String content) {}

  /** 启用位请求体（契约 {@code setSegmentEnabled} 的 body）。 */
  record EnabledBody(boolean enabled) {}

  /** 批量删除请求体（契约 {@code batchDeleteSegments} 的 body）。 */
  record BatchIdsBody(List<UUID> ids) {}

  /** 批量迁移请求体（契约 {@code batchMoveSegments} 的 body）。 */
  record BatchMoveBody(List<UUID> segmentIds, UUID targetDocumentId) {}
}
