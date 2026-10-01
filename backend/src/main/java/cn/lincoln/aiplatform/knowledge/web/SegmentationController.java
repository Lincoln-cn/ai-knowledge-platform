package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.SegmentService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 切分预览端点（契约 {@code frontend/src/api/ingestion.ts} 的 {@code previewSegmentation}）。
 *
 * <p>★ 三条路径（{@code 12 §3.5}）：
 *
 * <ul>
 *   <li>{@code documentId}：读文档的 {@code parsedContent} 调 {@code Segmenter.plan}。 不可见/不存在 ⇒
 *       404（不泄漏存在性，{@code 07 §3.3}）；解析尚未完成 ⇒ 可读 400
 *   <li>{@code segmentationPlan}（用户编辑过的计划）：仍必须跑 SEG-I1 校验 ⇒ 超长 400 + 可读原因
 *   <li>{@code uploadIds}（暂存上传）：明确 400 —— 绝对不「收下但不用」
 * </ul>
 *
 * <p>★ 权限点（{@code 07 §4.1}）：{@code knowledge:view}（读端点）。
 *
 * <p>★ 写端点必须带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest} 构建期检查。
 */
@RestController
@RequestMapping("/api/v1/segmentation")
public class SegmentationController {

  private final SegmentService segmentService;

  public SegmentationController(SegmentService segmentService) {
    this.segmentService = segmentService;
  }

  /**
   * 切分预览（契约 {@code previewSegmentation}）。
   *
   * <p>★ {@code segmentLength > modelLimit} ⇒ 400 + hint（不要静默夹紧）。 返回的 {@code segments[]} 逐项 {@code
   * {ordinal, content, charCount, oversized}}，{@code modelLimit} 与入库用的是同一个来源。
   */
  @PostMapping("/preview")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<SegmentService.PreviewResult> preview(
      @RequestBody PreviewSegmentationBody body) {
    return ApiResponse.ok(segmentService.preview(body.toCommand()));
  }

  /**
   * 预览请求体（契约 {@code PreviewSegmentationBody}）。
   *
   * <p>★ 与契约逐字段对齐：{@code documentId} / {@code segmentLength} / {@code overlap} / {@code strategy} /
   * {@code uploadIds} / {@code segmentationPlan}。
   *
   * @param documentId 已有文档 id；上传前预览时传 null
   * @param segmentLength 分段长度上限
   * @param overlap 重叠长度
   * @param strategy 切分策略（AUTO / CUSTOM）。★ 必须透传给服务层：本版只实现 AUTO，CUSTOM 要 <strong>明确拒绝</strong> —— 静默按
   *     AUTO 处理会让界面那句「按固定长度切分」变成谎话
   * @param uploadIds 暂存上传的文件 id —— <b>后端尚未消费</b>，出现即 400
   * @param segmentationPlan 用户确认过的切分计划（含预览编辑）
   */
  record PreviewSegmentationBody(
      UUID documentId,
      Integer segmentLength,
      Integer overlap,
      String strategy,
      List<String> uploadIds,
      List<Map<String, Object>> segmentationPlan) {

    SegmentService.PreviewCommand toCommand() {
      int segLen = segmentLength == null ? 500 : segmentLength;
      int ov = overlap == null ? 60 : overlap;
      List<SegmentService.PlanItem> plan = null;
      if (segmentationPlan != null && !segmentationPlan.isEmpty()) {
        plan =
            segmentationPlan.stream()
                .map(
                    m ->
                        new SegmentService.PlanItem(
                            m.get("ordinal") instanceof Number n ? n.intValue() : 0,
                            m.get("title") instanceof String t ? t : null,
                            m.get("content") instanceof String c ? c : null))
                .toList();
      }
      return new SegmentService.PreviewCommand(documentId, segLen, ov, strategy, uploadIds, plan);
    }
  }
}
