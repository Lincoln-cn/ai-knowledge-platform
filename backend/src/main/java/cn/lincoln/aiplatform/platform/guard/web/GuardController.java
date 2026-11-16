package cn.lincoln.aiplatform.platform.guard.web;

import cn.lincoln.aiplatform.platform.guard.GuardCategory;
import cn.lincoln.aiplatform.platform.guard.GuardHitCount;
import cn.lincoln.aiplatform.platform.guard.GuardRule;
import cn.lincoln.aiplatform.platform.guard.GuardRuleKind;
import cn.lincoln.aiplatform.platform.guard.GuardService;
import cn.lincoln.aiplatform.platform.guard.GuardSettings;
import cn.lincoln.aiplatform.platform.guard.GuardStore;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全围栏的配置接口（{@code ROADMAP §11.53.6}，页面 {@code /settings/guard}）。
 *
 * <h3>★ 读也守 {@code platform:guard:manage}，这与字典相反</h3>
 *
 * <p>基础数据字典的读是"登录即可"（建档的下拉要用它）。围栏的读**不能这样**：读到的是一份 "哪些话会被拦"的可绕过清单，能读它就等于能绕开它。所以十一个端点全部守同一个权限点。
 *
 * <h3>★ 分类没有真删除，规则有</h3>
 *
 * <p>分类只停用（{@link #disableCategory}）：历史审计里那个 {@code code} 还要有显示名可对照。
 * 规则允许真删：一条错加的词即使停用，"这条词存在过"也会变成永久记录，而它本身是敏感内容。
 */
@RestController
@RequestMapping("/api/v1/guard")
public class GuardController {

  private final GuardService guard;

  private final GuardStore store;

  public GuardController(GuardService guard, GuardStore store) {
    this.guard = guard;
    this.store = store;
  }

  /** 当前运行开关。 */
  @GetMapping("/settings")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardSettings> settings() {
    return ApiResponse.ok(guard.settings());
  }

  /**
   * 整行覆盖写运行开关。
   *
   * <p>★ 契约与页面都是"整行提交"（同检索参数）：只传一部分会把没传的字段写成默认值， 表现为"改了一处、另一处悄悄回到初始值"。
   */
  @PutMapping("/settings")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardSettings> updateSettings(
      @RequestBody SettingsBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        guard.updateSettings(
            new GuardSettings(
                body.inputEnabled(),
                body.defaultRefusalText(),
                body.advisorsEnabled(),
                body.categoryRefusalEnabled(),
                null),
            operator(jwt)));
  }

  /** 全部分类（含停用的）。 */
  @GetMapping("/categories")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<List<GuardCategory>> categories() {
    return ApiResponse.ok(guard.categories());
  }

  /** 新增一个分类。 */
  @PostMapping("/categories")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardCategory> createCategory(
      @RequestBody CategoryBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        guard.saveCategory(
            null,
            body.code(),
            body.name(),
            body.sortOrder(),
            body.enabled(),
            body.refusalText(),
            operator(jwt)));
  }

  /** 改一个分类（{@code code} 不可改）。 */
  @PutMapping("/categories/{id}")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardCategory> updateCategory(
      @PathVariable("id") UUID id,
      @RequestBody CategoryBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        guard.saveCategory(
            id,
            body.code(),
            body.name(),
            body.sortOrder(),
            body.enabled(),
            body.refusalText(),
            operator(jwt)));
  }

  /**
   * 停用一个分类。
   *
   * <p>★ 用 {@code DELETE} 动词但语义是"停用"：与字典同一口径（{@code DictionaryController} 连 DELETE 都不提供）。这里保留
   * DELETE 是因为界面上的按钮是"停用"，而 REST 里没有比它更贴的动词； 响应体明确回 {@code enabled=false} 的整条记录，读接口的人不会误以为它没了。
   */
  @DeleteMapping("/categories/{id}")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardCategory> disableCategory(
      @PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(guard.disableCategory(id, operator(jwt)));
  }

  /**
   * 规则列表。
   *
   * @param categoryId 只看某个分类；不传 = 全部
   */
  @GetMapping("/rules")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<List<GuardRule>> rules(
      @RequestParam(value = "categoryId", required = false) UUID categoryId) {
    return ApiResponse.ok(guard.rules(categoryId));
  }

  /** 新增一条规则。 */
  @PostMapping("/rules")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardRule> createRule(
      @RequestBody RuleBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        guard.saveRule(
            null,
            body.categoryId(),
            body.kind(),
            body.content(),
            body.enabled(),
            body.note(),
            operator(jwt)));
  }

  /** 改一条规则。 */
  @PutMapping("/rules/{id}")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<GuardRule> updateRule(
      @PathVariable("id") UUID id, @RequestBody RuleBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        guard.saveRule(
            id,
            body.categoryId(),
            body.kind(),
            body.content(),
            body.enabled(),
            body.note(),
            operator(jwt)));
  }

  /** 删一条规则。 */
  @DeleteMapping("/rules/{id}")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<Boolean> deleteRule(
      @PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(guard.deleteRule(id, operator(jwt)));
  }

  /**
   * 最近若干天各分类被拦的次数（★ 只有计数，没有原文也没有词面）。
   *
   * @param days 窗口，1–90，默认 7
   */
  @GetMapping("/hits")
  @PreAuthorize("hasAuthority('platform:guard:manage')")
  public ApiResponse<List<GuardHitCount>> hits(
      @RequestParam(value = "days", required = false, defaultValue = "7") int days) {
    return ApiResponse.ok(store.hitCounts(days));
  }

  private static UUID operator(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (RuntimeException notUuid) {
      // 服务账号等非 UUID 主体：审计里记不到人，但不该因此拒绝一次合法的配置变更
      return null;
    }
  }

  /** 运行开关的请求体（整行提交）。 */
  public record SettingsBody(
      boolean inputEnabled,
      String defaultRefusalText,
      boolean advisorsEnabled,
      boolean categoryRefusalEnabled) {}

  /**
   * 分类的请求体。
   *
   * @param code 机器码：新增必填；修改时传原值（服务端以库里那份为准）
   * @param refusalText 分类专属话术；空 = 用统一话术（同时它也是"差异化话术"开关的原料）
   * @param enabled 空 = 保持原值（新增时 = true）
   */
  public record CategoryBody(
      String code, String name, Integer sortOrder, Boolean enabled, String refusalText) {}

  /**
   * 规则的请求体。
   *
   * @param kind {@code KEYWORD} / {@code QUESTION}；修改时空 = 保持原值
   * @param enabled 空 = 保持原值（新增时 = true）
   */
  public record RuleBody(
      UUID categoryId, GuardRuleKind kind, String content, Boolean enabled, String note) {}
}
