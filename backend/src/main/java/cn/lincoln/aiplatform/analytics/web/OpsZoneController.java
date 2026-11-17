package cn.lincoln.aiplatform.analytics.web;

import cn.lincoln.aiplatform.analytics.application.ErrorLogService;
import cn.lincoln.aiplatform.analytics.application.RiskService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「审计与风险」专区的两个只读端点（`#354`）：风险提示与错误日志。
 *
 * <p>★ 权限一律 {@code audit:view}，与审计记录、登录日志同一个权限点 —— 这一专区的三张表受众是同一类人
 * （出事时去翻记录的人），多造权限点只会让四份清单多一处要同步的差异。
 *
 * <p>★ 路径前缀用 {@code /ops-zone} 而不是塞进 {@code /analytics}：运营看板回答"业务跑得怎么样"（{@code dashboard:view}），
 * 这里回答"系统有没有毛病"（{@code audit:view}）。两件事的读者与权限都不同，共用前缀会让下一个人以为它们是一回事。
 */
@RestController
@RequestMapping("/api/v1/ops-zone")
public class OpsZoneController {

  private final RiskService risk;
  private final ErrorLogService errorLogs;

  public OpsZoneController(RiskService risk, ErrorLogService errorLogs) {
    this.risk = risk;
    this.errorLogs = errorLogs;
  }

  /** 四项体检读数（磁盘 / 堆 / 慢回答 / 失败回答）。不推送、不落历史。 */
  @GetMapping("/risk")
  @PreAuthorize("hasAuthority('audit:view')")
  public ApiResponse<RiskService.Snapshot> risk() {
    return ApiResponse.ok(risk.snapshot());
  }

  /**
   * 失败记录（回答失败 + 文档解析/向量化失败）。
   *
   * @param days 最近几天（默认 7）
   * @param source `CHAT` / `DOCUMENT`，空 = 全部
   */
  @GetMapping("/error-logs")
  @PreAuthorize("hasAuthority('audit:view')")
  public ApiResponse<ErrorLogService.Page> errorLogs(
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) String source,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.ok(
        errorLogs.page(
            Math.max(1, Math.min(days, 365)),
            source,
            Math.max(1, page),
            Math.max(1, Math.min(size, 200))));
  }
}
