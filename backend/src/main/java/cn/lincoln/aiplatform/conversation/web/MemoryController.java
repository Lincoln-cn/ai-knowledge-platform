package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.MemoryService;
import cn.lincoln.aiplatform.conversation.application.MemorySettingsView;
import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.time.Instant;
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
 * 长期记忆的接口（{@code 0.9.0 · #296}）。
 *
 * <h3>★ 读与写的权限不同</h3>
 *
 * <p>读：<strong>登录即可</strong>，但只能看自己的（{@code owner_id} 从 JWT 取，不接受任何请求参数覆盖）。 写/改/删：同样是自己的，不需要额外权限点
 * —— 记忆是<strong>个人数据</strong>，不是系统配置。 唯一的例外是设定（总开关、召回条数等）影响全机行为 ⇒ 要 {@code conversation:settings}。
 *
 * <p>★ 为什么不接受 {@code userId} 参数：一旦有这个参数，"越权读别人的记忆"就只是改一个参数的事。 这个接口里根本不存在"按别人查"的能力，比事后校验更安全。
 *
 * <p>★★ 写端点仍然显式声明 {@code conversation:view}，而不是留空：本仓有一条闸门 （{@code
 * AuthorizationDeclarationTest.每个写端点都必须声明权限}）要求<strong>每个</strong> POST/PUT/DELETE 都写
 * {@code @PreAuthorize} —— 它的理由不是"这些端点危险"，而是 "没写注解的端点不会报错、只会静默对所有登录用户开放"。这里选最宽的那个权限点是刻意的： 能力边界由
 * {@code owner_id 从 JWT 取} 回答，权限点只声明"你需要能用对话"。
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

  private final MemoryService memoryService;

  public MemoryController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /**
   * 列出我的记忆。
   *
   * @param jwt 当前用户
   * @param level 只看某一层（可选）
   * @param limit 条数上限
   * @return 记忆列表
   */
  @GetMapping
  public ApiResponse<List<MemoryView>> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String level,
      @RequestParam(defaultValue = "100") int limit) {
    UUID owner = UUID.fromString(jwt.getSubject());
    MemoryLevel parsed = level == null || level.isBlank() ? null : MemoryLevel.of(level);
    List<MemoryView> rows =
        memoryService.list(owner, parsed, limit).stream().map(MemoryView::of).toList();
    return ApiResponse.ok(rows);
  }

  /**
   * 各层条数。
   *
   * @param jwt 当前用户
   * @return 四层条数
   */
  @GetMapping("/counts")
  public ApiResponse<List<MemoryService.LevelCount>> counts(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(memoryService.counts(UUID.fromString(jwt.getSubject())));
  }

  /**
   * 我的画像。
   *
   * @param jwt 当前用户
   * @return 画像
   */
  @GetMapping("/profile")
  public ApiResponse<ProfileView> profile(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        ProfileView.of(memoryService.profileOf(UUID.fromString(jwt.getSubject()))));
  }

  /**
   * 手动新增一条记忆。
   *
   * @param jwt 当前用户
   * @param body 内容
   * @return 新记忆
   */
  @PostMapping
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<MemoryView> create(
      @AuthenticationPrincipal Jwt jwt, @RequestBody CreateBody body) {
    UUID owner = UUID.fromString(jwt.getSubject());
    Memory memory =
        memoryService.create(
            owner,
            MemoryLevel.of(body.level()),
            body.scopeId(),
            MemoryKind.of(body.kind()),
            body.content(),
            body.importanceOrDefault());
    return ApiResponse.ok(MemoryView.of(memory));
  }

  /**
   * 改一条记忆。
   *
   * @param jwt 当前用户
   * @param id 记忆 id
   * @param body 新内容
   * @return 改后的记忆
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<MemoryView> update(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody UpdateBody body) {
    Memory memory =
        memoryService.update(
            UUID.fromString(id),
            UUID.fromString(jwt.getSubject()),
            body.content(),
            body.importanceOrDefault());
    return ApiResponse.ok(MemoryView.of(memory));
  }

  /**
   * 删一条记忆（软删）。
   *
   * @param jwt 当前用户
   * @param id 记忆 id
   * @return 空
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    memoryService.delete(UUID.fromString(id), UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok(null);
  }

  /**
   * 读记忆设定。
   *
   * @return 设定
   */
  @GetMapping("/settings")
  public ApiResponse<MemorySettingsView> settings() {
    return ApiResponse.ok(memoryService.settingsView());
  }

  /**
   * 改记忆设定。
   *
   * @param jwt 当前用户
   * @param body 新设定
   * @return 落库后的设定
   */
  @PutMapping("/settings")
  @PreAuthorize("hasAuthority('conversation:settings')")
  public ApiResponse<MemorySettingsView> updateSettings(
      @AuthenticationPrincipal Jwt jwt, @RequestBody MemorySettingsView body) {
    return ApiResponse.ok(memoryService.updateSettings(body, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 一条记忆的对外形状。
   *
   * @param id id
   * @param level 层级
   * @param scopeId 会话 id
   * @param kind 种类
   * @param content 内容
   * @param importance 重要度
   * @param confidence 置信度
   * @param hitCount 命中次数
   * @param lastHitAt 最后命中
   * @param sourceMessageIds 来源消息
   * @param createdAt 创建时间
   * @param updatedAt 更新时间
   */
  public record MemoryView(
      UUID id,
      String level,
      UUID scopeId,
      String kind,
      String content,
      int importance,
      float confidence,
      int hitCount,
      Instant lastHitAt,
      List<UUID> sourceMessageIds,
      Instant createdAt,
      Instant updatedAt) {

    /**
     * @param memory 实体
     * @return 视图
     */
    public static MemoryView of(Memory memory) {
      return new MemoryView(
          memory.id(),
          memory.level().code(),
          memory.scopeId(),
          memory.kind().code(),
          memory.content(),
          memory.importance(),
          memory.confidence(),
          memory.hitCount(),
          memory.lastHitAt(),
          memory.sourceMessageIds() == null ? List.of() : List.of(memory.sourceMessageIds()),
          memory.createdAt(),
          memory.updatedAt());
    }
  }

  /**
   * 画像的对外形状。
   *
   * @param tone 语气偏好
   * @param domains 关注领域
   * @param taboos 忌讳
   * @param summary 摘要
   * @param updatedAt 更新时间
   */
  public record ProfileView(
      String tone, List<String> domains, List<String> taboos, String summary, Instant updatedAt) {

    /**
     * @param profile 画像实体
     * @return 视图
     */
    public static ProfileView of(cn.lincoln.aiplatform.conversation.domain.UserProfile profile) {
      return new ProfileView(
          profile.tone(),
          profile.domainList(),
          profile.taboosList(),
          profile.summary(),
          profile.updatedAt());
    }
  }

  /**
   * 新增请求体。
   *
   * @param level 层级
   * @param scopeId 会话 id
   * @param kind 种类
   * @param content 内容
   * @param importance 重要度（缺省 3）
   */
  public record CreateBody(
      String level, UUID scopeId, String kind, String content, Integer importance) {

    /**
     * @return 重要度，缺省给 3
     */
    public int importanceOrDefault() {
      return importance == null ? 3 : importance;
    }
  }

  /**
   * 修改请求体。
   *
   * @param content 新内容
   * @param importance 新重要度（缺省不动？—— 这里要求必须给，避免"顺手把重要度改回 3"）
   */
  public record UpdateBody(String content, Integer importance) {

    /**
     * @return 重要度，缺省给 3
     */
    public int importanceOrDefault() {
      return importance == null ? 3 : importance;
    }
  }
}
