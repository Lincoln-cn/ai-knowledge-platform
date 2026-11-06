package cn.lincoln.aiplatform.platform.compliance.web;

import cn.lincoln.aiplatform.platform.compliance.AgreementConsent;
import cn.lincoln.aiplatform.platform.compliance.AgreementService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务协议同意留痕（{@code #83} 第九条 / 第十条）。
 *
 * <p>协议正文是前端常量（{@code src/constants/agreement.ts}，含适用人群/场合/用途与防未成年人 过度依赖的说明）；后端只留「谁、何时、对哪一版」。★
 * 协议更新后版本号要 bump ⇒ 未对新版本 同意过的用户会被前端重新弹窗。
 */
@RestController
@RequestMapping("/api/v1/compliance/agreement")
public class AgreementController {

  private final AgreementService agreements;

  public AgreementController(AgreementService agreements) {
    this.agreements = agreements;
  }

  /** 当前用户最近一次协议同意留痕；从未同意过 ⇒ {@code data} 为 null。 */
  @GetMapping("/consent")
  public ApiResponse<AgreementConsent> consentOf(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(agreements.latestOf(UUID.fromString(jwt.getSubject())).orElse(null));
  }

  /**
   * 记录同意（登录即可；同版本重复提交幂等）。★ isAuthenticated() 是显式声明 —— 写端点不许裸奔（AuthorizationDeclarationTest 盯这条）。
   */
  @PostMapping("/consent")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<AgreementConsent> consent(
      @RequestBody ConsentBody body, @AuthenticationPrincipal Jwt jwt) {
    String version = body.version() == null ? "" : body.version().trim();
    if (version.isEmpty() || version.length() > 64) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "协议版本号不能为空且不超过 64 个字符");
    }
    return ApiResponse.ok(agreements.consent(UUID.fromString(jwt.getSubject()), version));
  }

  /** 同意请求体：协议版本号（前端 {@code AGREEMENT_VERSION}）。 */
  public record ConsentBody(String version) {}
}
