package cn.lincoln.aiplatform.analytics.web;

import cn.lincoln.aiplatform.analytics.application.AnalyticsService;
import cn.lincoln.aiplatform.analytics.application.AnalyticsService.AssistantRankItem;
import cn.lincoln.aiplatform.analytics.application.AnalyticsService.MeSummary;
import cn.lincoln.aiplatform.analytics.application.AnalyticsService.Overview;
import cn.lincoln.aiplatform.analytics.application.AnalyticsService.Quality;
import cn.lincoln.aiplatform.analytics.application.AnalyticsService.TrendPoint;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营分析 5 个只读端点（Issue #193）。
 *
 * <p>四个全平台口径要 {@code dashboard:view}；{@code /me/summary} 只要求登录 —— 它是消费者看自己的读数，
 * 不该被运营权限挡住（契约注释逐字写着“我的口径与 overview 是两套口径”）。
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

  private final AnalyticsService analytics;

  public AnalyticsController(AnalyticsService analytics) {
    this.analytics = analytics;
  }

  @GetMapping("/overview")
  @PreAuthorize("hasAuthority('dashboard:view')")
  public ApiResponse<Overview> overview(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(analytics.overview(from, to));
  }

  @GetMapping("/trend")
  @PreAuthorize("hasAuthority('dashboard:view')")
  public ApiResponse<List<TrendPoint>> trend(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(analytics.trend(from, to));
  }

  @GetMapping("/assistants")
  @PreAuthorize("hasAuthority('dashboard:view')")
  public ApiResponse<List<AssistantRankItem>> assistants(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "10") int limit) {
    return ApiResponse.ok(analytics.topAssistants(from, to, limit));
  }

  @GetMapping("/quality")
  @PreAuthorize("hasAuthority('dashboard:view')")
  public ApiResponse<Quality> quality(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(analytics.quality(from, to));
  }

  @GetMapping("/me/summary")
  public ApiResponse<MeSummary> me(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(analytics.me(subjectOf(jwt), from, to));
  }

  private static UUID subjectOf(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new IllegalArgumentException("当前登录信息缺少用户标识");
    }
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("当前登录信息的用户标识不是合法 UUID");
    }
  }
}
