package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.SegmentService;
import cn.lincoln.aiplatform.knowledge.application.SegmentView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
   * 删除分段（契约 {@code deleteSegment}）。
   *
   * <p>★ 分段是子实体、没有软删列 ⇒ 真删；删完回填文档进度列并把状态置回「待向量化」（向量旧了）。
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<Void> delete(@PathVariable UUID id) {
    segmentService.delete(id);
    return ApiResponse.ok();
  }

  /** 编辑请求体（契约 {@code updateSegment} 的 body）。 */
  record UpdateSegmentBody(String title, String content) {}
}
