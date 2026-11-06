package cn.lincoln.aiplatform.platform.compliance.web;

import cn.lincoln.aiplatform.platform.compliance.ComplianceSettings;
import cn.lincoln.aiplatform.platform.compliance.ComplianceSettingsService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合规设置（{@code #83}）：投诉举报的反馈时限，第十七条的算法备案号/安全评估结论登记位。
 *
 * <h3>★ 读写分开（与 {@code DictionaryController} 同一取舍）</h3>
 *
 * <ul>
 *   <li>读（GET）：登录即可。反馈时限要在投诉入口<strong>公示</strong>（第十五条），
 *       「关于」里也要引用备案号（第十七条的"对外材料能引用它"）——卡管理权限就没人看得到。
 *   <li>写（PUT）：{@code platform:compliance:manage}。这是合规登记，改一次影响全平台的公示内容。
 * </ul>
 *
 * <p>★ <strong>不建流程</strong>（处置 = 留缝）：这里只有"登记位"，没有算法备案的申报/变更/ 注销流程 ——
 * 那是面向网信部门的线下流程，平台侧等触发条件（面向境内公众提供服务之前）再做实体。
 */
@RestController
@RequestMapping("/api/v1/compliance/settings")
public class ComplianceSettingsController {

  private final ComplianceSettingsService settings;

  public ComplianceSettingsController(ComplianceSettingsService settings) {
    this.settings = settings;
  }

  /** 读合规设置（登录即可）：投诉反馈时限 + 算法备案号/安全评估结论登记位。 */
  @GetMapping
  public ApiResponse<ComplianceSettings> get() {
    return ApiResponse.ok(settings.current());
  }

  /** 登记/修改合规设置（{@code platform:compliance:manage}）。 */
  @PutMapping
  @PreAuthorize("hasAuthority('platform:compliance:manage')")
  public ApiResponse<ComplianceSettings> update(
      @RequestBody UpdateBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        settings.update(
            body.complaintFeedbackDays(),
            body.algorithmFilingNo(),
            body.securityAssessmentConclusion(),
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 更新请求体（合规设置页一次提交整张表单）。
   *
   * <p>★ {@code algorithmFilingNo} / {@code securityAssessmentConclusion} = 第十七条登记位（留缝）： 可空（空 =
   * 未登记）， <strong>不校验格式</strong> —— 本版无申报流程，编格式规则只会把真号挡在门外。
   */
  public record UpdateBody(
      int complaintFeedbackDays, String algorithmFilingNo, String securityAssessmentConclusion) {}
}
