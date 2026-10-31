package cn.lincoln.aiplatform.platform.web;

import cn.lincoln.aiplatform.platform.domain.AuditQueryPort;
import cn.lincoln.aiplatform.platform.domain.AuditRecordView;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计记录的只读查询（`#258`）。
 *
 * <p>★ **只有 {@code GET}**。这个控制器里刻意不出现 POST / PUT / DELETE —— `13 §7.2` 第 3 条说审计"只追加、不提供删除接口"，
 * 而"代码里没写 delete"这种约定要被后来者" 顺手补上"太容易了（本仓库已经有过一次这种顺手：见 {@code #40}）。所以在同一个类里连一个可写的映射都没有，
 * 再要加就得先写一个新的映射 —— 那一步会被人看见。
 *
 * <p>★ 一张审计表能被读，才能真正回答等保 2.0 的「可追溯」。`#257` 只做了写，于是"谁改了这份文档的密级" 只能连库手写 SQL —— 那是运维能力，不是产品能力。
 *
 * <p>★ 权限用 {@code system:manage}：审计里有"谁看过什么"的线索，它比业务数据更敏感（`13 §7.2` 第 1 条： 审计日志自身的密级 ≥
 * 被审计数据的密级）。这里没有单独的 {@code audit:view} 权限点 ——
 * 多一个权限点就要多一套"谁能看审计"的矩阵，而目前能看系统的只有一类人；真出现第二类人时再加，那时它会有明确的判据。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

  /** 一页最多 200 条：审计行会越积越多，允许"一次拉完"等于允许一次全表扫描。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final AuditQueryPort auditQuery;

  public AuditLogController(AuditQueryPort auditQuery) {
    this.auditQuery = auditQuery;
  }

  /**
   * 按条件查一页（时间倒序）。
   *
   * @param action 动作编码，如 {@code document.reclassify}
   * @param resourceType 资源类别，如 {@code DOCUMENT}
   * @param resourceId 资源标识
   * @param actorId 操作人
   * @param from 起始时间（含），ISO-8601
   * @param to 结束时间（**不含**）—— 半开区间，见 {@link AuditQueryPort.Query}
   * @param page 从 1 开始
   * @param size 每页条数，上限 {@value #MAX_PAGE_SIZE}
   */
  @GetMapping
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<PageResult<AuditRecordView>> list(
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String resourceId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    int safePage = Math.max(1, page);
    int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    AuditQueryPort.Result result =
        auditQuery.page(
            new AuditQueryPort.Query(
                blankToNull(action),
                normalizeType(resourceType),
                blankToNull(resourceId),
                actorId,
                from,
                to,
                safePage,
                safeSize));

    return ApiResponse.ok(PageResult.of(result.list(), result.total(), safePage, safeSize));
  }

  /**
   * 一个资源的全部记录（"这份文档到底被谁改过"——最常见的问法）。
   *
   * <p>★ 单独一个端点而不是让调用方自己传两个 filter：这个用法太常见，且它的正确形状（按资源 + 时间倒序）不该由调用方每次拼对。
   */
  @GetMapping("/by-resource")
  @PreAuthorize("hasAuthority('system:manage')")
  public ApiResponse<java.util.List<AuditRecordView>> byResource(
      @RequestParam String resourceType, @RequestParam String resourceId) {
    return ApiResponse.ok(auditQuery.byResource(resourceType, resourceId));
  }

  /** 资源类别统一大写：库里存的是 {@code DOCUMENT}，前端传 {@code document} 时不该静默返回 0 条。 */
  private static String normalizeType(String resourceType) {
    String value = blankToNull(resourceType);
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
