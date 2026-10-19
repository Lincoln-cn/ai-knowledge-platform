package cn.lincoln.aiplatform.modelcatalog.web;

import cn.lincoln.aiplatform.modelcatalog.application.ModelProviderService;
import cn.lincoln.aiplatform.modelcatalog.application.ModelProviderView;
import cn.lincoln.aiplatform.modelcatalog.application.ProviderTestResult;
import cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderEgress;
import cn.lincoln.aiplatform.modelcatalog.domain.ProviderKind;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务商配置端点（契约 {@code frontend/src/api/providers.ts}）。
 *
 * <h3>★ 权限</h3>
 *
 * <p>复用 {@code model:manage}，不新增权限点：能改模型地址/密钥的人，本来就能换这套凭据；把它切成两个权限点只会 让“管模型的人不能修 401”成为日常故障。
 *
 * <h3>★★ 密钥永不出网</h3>
 *
 * <p>{@link ModelProviderView} 只有 {@code hasApiKey}；设置/清除密钥是单独端点。请求体里的 key 只用于加密落库 或一次探测，不进日志、不进响应。
 */
@RestController
@RequestMapping("/api/v1/model-providers")
@PreAuthorize("hasAuthority('model:manage')")
public class ModelProviderController {

  private final ModelProviderService providers;

  public ModelProviderController(ModelProviderService providers) {
    this.providers = providers;
  }

  @GetMapping
  public ApiResponse<List<ModelProviderView>> list() {
    return ApiResponse.ok(providers.list());
  }

  @GetMapping("/{id}")
  public ApiResponse<ModelProviderView> get(@PathVariable UUID id) {
    return ApiResponse.ok(providers.get(id));
  }

  @PostMapping
  public ApiResponse<ModelProviderView> create(
      @RequestBody ProviderBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(providers.create(body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  @PutMapping("/{id}")
  public ApiResponse<ModelProviderView> update(
      @PathVariable UUID id, @RequestBody ProviderBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(providers.update(id, body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  @PatchMapping("/{id}/enabled")
  public ApiResponse<ModelProviderView> setEnabled(
      @PathVariable UUID id, @RequestBody EnabledBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        providers.setEnabled(id, body.enabled(), UUID.fromString(jwt.getSubject())));
  }

  @PostMapping("/{id}/key")
  public ApiResponse<ModelProviderView> setKey(
      @PathVariable UUID id, @RequestBody KeyBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(providers.setKey(id, body.apiKey(), UUID.fromString(jwt.getSubject())));
  }

  @DeleteMapping("/{id}/key")
  public ApiResponse<ModelProviderView> clearKey(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(providers.clearKey(id, UUID.fromString(jwt.getSubject())));
  }

  /** 测试已保存的服务商（只读探测，不写库）。 */
  @PostMapping("/{id}/test")
  public ApiResponse<ProviderTestResult> test(@PathVariable UUID id) {
    return ApiResponse.ok(providers.test(id));
  }

  /** 一键绑定 JAiRouter：先探再写；探不通不落库。 */
  @PostMapping("/quick-bind")
  public ApiResponse<ModelProviderService.QuickBindResult> quickBind(
      @RequestBody(required = false) QuickBindBody body, @AuthenticationPrincipal Jwt jwt) {
    String apiBase = body == null ? null : body.apiBase();
    String apiKey = body == null ? null : body.apiKey();
    return ApiResponse.ok(providers.quickBind(apiBase, apiKey, UUID.fromString(jwt.getSubject())));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable UUID id) {
    providers.delete(id);
    return ApiResponse.ok();
  }

  // ────────────────────────── 请求体 ──────────────────────────

  /** 创建 / 更新服务商。★ 更新时 {@code apiKey} 不在这里处理：密钥走 {@code /key} 端点，避免“留空 = 保留还是清空” 这种两义。 */
  public record ProviderBody(
      @NotBlank String name,
      ProviderKind kind,
      String apiBase,
      AuthStyle authStyle,
      ProviderEgress egress,
      String apiKey,
      String remark) {

    ModelProviderService.ProviderDraft toDraft() {
      return new ModelProviderService.ProviderDraft(
          name, kind, apiBase, authStyle, egress, apiKey, remark);
    }
  }

  public record KeyBody(@NotBlank String apiKey) {}

  public record EnabledBody(boolean enabled) {}

  /** 快速绑定请求：两个字段都可空（地址用默认，key 由本地网关是否校验决定）。 */
  public record QuickBindBody(String apiBase, String apiKey) {}
}
