package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.AccessCredentialService;
import cn.lincoln.aiplatform.assistant.application.AccessCredentialView;
import cn.lincoln.aiplatform.assistant.application.AssistantBundleView;
import cn.lincoln.aiplatform.assistant.application.AssistantService;
import cn.lincoln.aiplatform.assistant.application.AssistantView;
import cn.lincoln.aiplatform.assistant.application.PublishCheckResult;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用配置端点（契约 {@code frontend/src/api/assistants.ts}）—— 第 15 周 · 0.4.3 + 第 16 周 · 0.4.4。
 *
 * <h3>本批落了哪些（以及其余为什么没落）</h3>
 *
 * <p>第 15 周落下的是"配一个简单应用 ⇒ 发布 ⇒ 导出"这条链：{@code POST /assistants} · {@code GET /assistants/:id} ·
 * {@code PUT /assistants/:id} · {@code GET /assistants/:id/publish-check} · {@code PATCH
 * /assistants/:id/published} · {@code GET /assistants/:id/export}。
 *
 * <p>★ <strong>第 16 周（0.4.4）落下访问凭证 4 个</strong>： {@code GET /assistants/:appId/credentials} ·
 * {@code POST /assistants/:appId/credentials} · {@code PATCH
 * /assistants/:appId/credentials/:id/enabled} · {@code POST
 * /assistants/:appId/credentials/:id/revoke}。 它们把"公开凭证 + 分享冻结"这条链上<strong>管理侧</strong>的那一段补齐
 * （创建时冻结密级、撤销即失效、凭证可启停）；<strong>免登录面本身</strong> （{@code /api/v1/public/**} 的 HTTP 端点）仍是第 17 周 ——
 * 本批只落它的解析形状 （{@code platform.access.PublicTokenResolver}）。
 *
 * <p>★ <strong>收藏</strong>（{@code #195} 的收藏子项）本批已落：{@code POST} / {@code DELETE
 * /assistants/:id/favorite} 两条。在此之前市场页的心形按钮与「只看收藏」是"契约有、页面有、后端 400"。 其余高级端点（标签 · API Key · 共享写 ·
 * 导入）仍在 {@code ContractDriftTest} 的逐条欠账清单里， 每个域各自有排期（共享写是 T302 后半，导入归导入导出那一批）。
 *
 * <h3>★ 权限点（{@code 07 §4.1}）</h3>
 *
 * <ul>
 *   <li>读（{@code GET} / 发布校验 / 导出）⇒ {@code assistant:view}
 *   <li>配置写（{@code POST} / {@code PUT} / <strong>凭证的创建 / 启停 / 撤销</strong>）⇒ {@code assistant:edit}
 *   <li>发布（{@code PATCH .../published}）⇒ {@code assistant:publish}
 * </ul>
 *
 * <p>★ 发布单独一个权限点是刻意的：能改配置不等于能对外发布（{@code 07} 的"数据范围与操作权限正交"同理 —— 配置是内部动作，发布是对外承诺）。
 *
 * <p>★★ <strong>凭证沿用 {@code assistant:edit}</strong>（本轮<strong>不新增权限点</strong>）： 新增一个权限点要动"16
 * 个权限点"的前后端一致性闸门（常量 · 库目录 · 角色 seed · 前端 {@code PERM} · 路由门禁 ·
 * 校验脚本），而"能编辑这个应用"与"能给它发免登录链接"在语义上是同一档操作 （两者都是<strong>对外承诺</strong>那一类里的"配置动作"）。★ 真正的收窄不靠权限点，靠
 * <strong>归属</strong>：只有应用的 owner 能创建/启停/撤销它的凭证（见 {@code AccessCredentialService.requireOwner}）——
 * 一个能编辑别人应用的人不该能以别人的名义发链接。
 *
 * <p>★ {@code ownerId} / {@code visibility} <strong>不在请求体里</strong>：创建时 ownerId = JWT 的 {@code
 * sub}、 visibility = {@code PRIVATE}（{@code 08 §4.4.4} 一律收窄）。请求体里多余的同名字段被忽略。
 *
 * <p>★★ 凭证的 {@code maxClassification} <strong>也不在请求体里</strong>：它 = <strong>创建者当时的
 * clearance</strong>（{@code 13 §5.2} 第 ① 级"创建时冻结"），由服务层从 {@code ScopeFilter.maxClassification}
 * 取出。请求体里塞一个同名字段<strong>不会被读到</strong> —— 否则调用方能把凭证的密级上限抬到超过自己，等于自建一条越权通道。
 */
@RestController
@RequestMapping("/api/v1/assistants")
public class AssistantController {

  private final AssistantService assistants;
  private final AccessCredentialService credentials;

  public AssistantController(AssistantService assistants, AccessCredentialService credentials) {
    this.assistants = assistants;
    this.credentials = credentials;
  }

  /**
   * 列表（契约 {@code listAssistants}）。★ 范围与筛选拼进查询，{@code total} 与页内条数同口径。
   *
   * <p>★ 枚举型参数（{@code type} / {@code origin} / {@code sort}）拼错 ⇒ 可读 400：收了却不筛，
   * 症状是"筛了没效果"，而它看起来像"确实没有符合条件的"。{@code favorite=true} ⇒ 只看我收藏的（收藏状态按 JWT 的 {@code sub}
   * 取，不接受请求体指定"看谁的收藏"）。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PageResult<AssistantView>> list(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Boolean published,
      @RequestParam(required = false) Boolean mine,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) Boolean favorite,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(
        assistants.list(type, published, mine, origin, favorite, tag, keyword, sort, page, size));
  }

  /** 取一个应用（含检索参数与绑定）。 */
  /*
   * ★★ #165：路径变量必须显式约束成 **UUID 形状**，否则未实现的子路径会被它吃掉。
   *
   * 实测（本机真 HTTP，改前）：GET /api/v1/assistants/tags ⇒ **400** ——
   * 因为 {@code tags} 会匹配 {@code /{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}}，再转 UUID 失败 ⇒ MethodArgumentTypeMismatchException。
   * ★ 而 400 的含义是"你的请求有问题"，事实是"这个端点还没实现" ⇒ 排查方向被带偏。
   * ★ 而且其它未实现端点都是 404（/analytics/*、/prompts/*、/feedbacks/*）—— 只有这一条 400，
   *   任何"按状态码分类未实现端点"的扫查都会漏掉它。
   *
   * ★ 用显式的 8-4-4-4-12，**不用 {@code {36}}** —— 后者里的花括号会被 URI 模板当成占位符。
   * ★ 守门人 = AssistantUnknownSubPathTest（去掉这个约束它会红在 expected: 404 but was: 400）。
   */
  @GetMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantView> get(@PathVariable UUID id) {
    return ApiResponse.ok(assistants.get(id));
  }

  /**
   * 新建应用。
   *
   * <p>★ {@code type} 必填且创建后不可改；{@code clientRequestId} 可选 —— 传了就参与幂等（同一调用方重试返回既有应用）。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AssistantView> create(
      @Valid @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.create(body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 改配置（契约里是 {@code Partial<UpsertAssistantBody>} ⇒ <strong>没传的字段保持原值</strong>）。
   *
   * <p>★ 请求体里的 {@code type} <strong>会被忽略</strong>（契约注释：更新时后端应忽略该字段）。 这里不是"读了再丢掉"：
   * 服务层的更新路径压根不读它（{@code AssistantService.update} 用 current.type()）。
   */
  @PutMapping("/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AssistantView> update(
      @PathVariable UUID id,
      @Valid @RequestBody UpsertBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.update(id, body.toDraft(), UUID.fromString(jwt.getSubject())));
  }

  /** 发布前校验（只读）。★ 前端在配置页就调它，把不通过项提前说清（{@code 05 §455}） —— 等点"发布"才报错，是把错误推到配置者最不方便改的时刻。 */
  @GetMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/publish-check")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PublishCheckResult> publishCheck(@PathVariable UUID id) {
    return ApiResponse.ok(assistants.checkBeforePublish(id));
  }

  /** 发布 / 取消发布。★ 校验不过 ⇒ 6001，且应用状态不变。 */
  @PatchMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/published")
  @PreAuthorize("hasAuthority('assistant:publish')")
  public ApiResponse<AssistantView> setPublished(
      @PathVariable UUID id, @RequestBody PublishedBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        assistants.publish(id, body.published(), UUID.fromString(jwt.getSubject())));
  }

  /** 导出 JSON 包（配置 + 依赖清单）。★ {@code includeDocuments} 当前只回显，不影响包内容（见服务层注释）。 */
  @GetMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/export")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantBundleView> export(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeDocuments) {
    return ApiResponse.ok(assistants.export(id, includeDocuments));
  }

  /** 删除（软删）。★ 内置应用不可删（它是平台示范应用）。 */
  @DeleteMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    assistants.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  // ────────────────────────── 收藏（#195 的收藏子项）──────────────────────────

  /**
   * 收藏一个应用（契约 {@code favoriteAssistant}）。
   *
   * <p>★ <strong>幂等</strong>：重复收藏返回成功、{@code favorited} 仍是 {@code true} —— 页面是"乐观更新 +
   * 失败回滚"，一个"已经收藏过"的 400 会把刚点亮的心形回滚掉，看起来像"点了没反应"。
   *
   * <p>★ 只能收藏<strong>自己看得见的</strong>应用：看不见 ⇒ 404（与 {@code get} 同一口径，不泄露 id 是否存在）。
   *
   * <p>★ 权限点用 {@code assistant:view} 而不是 {@code assistant:edit}：收藏是<strong>读者</strong>的动作，
   * 不是配置写。它也不改变任何可见性 —— 收藏只回答"我的市场页该不该点亮心形"。
   */
  @PostMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/favorite")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantView> favorite(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.setFavorite(id, true, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 取消收藏（契约 {@code unfavoriteAssistant}）。
   *
   * <p>★ 同样是幂等的：本来就没收藏也返回成功、{@code favorited} 为 {@code false}。
   */
  @DeleteMapping(
      "/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/favorite")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<AssistantView> unfavorite(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(assistants.setFavorite(id, false, UUID.fromString(jwt.getSubject())));
  }

  // ────────────────────────── 访问凭证（第 16 周 · 0.4.4）──────────────────────────

  /**
   * 列出某个应用的访问凭证（契约 {@code listCredentials}）。
   *
   * <p>★ 含<strong>已撤销</strong>的：契约有 {@code revokedAt}，说明撤销是一条要被人看见的事实
   * （藏起来会让人以为"凭证丢了"，于是重新生成一条——而旧的那条其实还在别人手里，只是失效了）。
   */
  @GetMapping(
      "/{appId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/credentials")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<AccessCredentialView>> listCredentials(
      @PathVariable UUID appId, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(credentials.list(appId, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 新建访问凭证（契约 {@code createCredential}）。
   *
   * <p>★★ 请求体只有 {@code expiresAt}。<strong>{@code maxClassification} 由服务层从请求者的 clearance
   * 取出</strong>（{@code 13 §5.2} 第 ① 级）—— 这里不是"读了再丢掉"： {@link ExpiresBody}
   * 里<strong>压根没有</strong>那个字段，所以调用方无法表达一个更高的密级。
   *
   * <p>★ 撤销是终态 ⇒ 这一条是"重新生成"的唯一入口（界面上按钮叫「重新生成」也是这个原因）。
   */
  @PostMapping(
      "/{appId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/credentials")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AccessCredentialView> createCredential(
      @PathVariable UUID appId,
      @RequestBody(required = false) ExpiresBody body,
      @AuthenticationPrincipal Jwt jwt) {
    Instant expiresAt = body == null ? null : body.expiresAt();
    return ApiResponse.ok(credentials.create(appId, expiresAt, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 启停一条凭证（契约 {@code setCredentialEnabled}）。
   *
   * <p>★ 停用与撤销是两件事：停用可再启用、凭证保留；撤销是终态（再启用会得到 {@code 400} + 可读原因）。 两者混淆的后果是"管理员以为停用等于吊销"——
   * 而那条链接随时可以被他自己再打开。
   */
  @PatchMapping(
      "/{appId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/credentials/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/enabled")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AccessCredentialView> setCredentialEnabled(
      @PathVariable UUID appId,
      @PathVariable UUID id,
      @RequestBody EnabledBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        credentials.setEnabled(appId, id, body.enabled(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 撤销一条凭证（契约 {@code revokeCredential}）—— <strong>终态</strong>。
   *
   * <p>★★ 撤销之后<strong>同一个请求周期内</strong>那条链接即被拒（判据 ①）：服务层会立刻清掉按 token 的 解析缓存。做不到这一点就不该引那个缓存 ——
   * "撤销了还能用"不会有任何报错。
   */
  @PostMapping(
      "/{appId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/credentials/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/revoke")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<AccessCredentialView> revokeCredential(
      @PathVariable UUID appId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(credentials.revoke(appId, id, UUID.fromString(jwt.getSubject())));
  }

  // ────────────────────────── 请求体 ──────────────────────────

  /**
   * 新建 / 更新应用的请求体（契约 {@code UpsertAssistantBody}）。
   *
   * <p>★ 这里用 <strong>Bean Validation</strong> 表达"长度"这类与语义无关的约束（{@code @Size}）， 失败由 {@code
   * GlobalExceptionHandler} 统一转成 {@code {code,message,hint}} 包络（{@code 08 §4.1}）， 不用每个端点各写一遍。
   *
   * <p>⚠️ <strong>{@code name} 上不能加 {@code @NotBlank}</strong>：契约里 {@code PUT} 是 {@code
   * Partial<UpsertAssistantBody>}（只发改动的字段），加"必填"会把合法的部分更新拒成 400。 ⇒ "必填"由服务层按创建/更新分别判（{@code
   * AssistantService.requireName}）， 比在注解里塞校验组清楚得多。
   *
   * <p>⚠️ {@code type} 是字符串而不是枚举：契约里是 {@code 'SIMPLE' | 'WORKFLOW'}，交给 Jackson 直接映射枚举时，
   * 拼错的值会得到一句面向开发者的报错；这里要的是面向用户的"应用类型只支持 SIMPLE / WORKFLOW"。
   */
  public record UpsertBody(
      @Size(max = 30, message = "应用名称不能超过 30 字") String name,
      String icon,
      @Size(max = 200, message = "描述不能超过 200 字") String description,
      String type,
      String modelId,
      @Size(max = 4000, message = "角色设定不能超过 4000 字") String rolePrompt,
      List<String> kbIds,
      List<String> promptIds,
      RetrievalBody retrieval,
      Boolean multiTurn,
      Boolean thinkingEnabled,
      @Size(max = 1000, message = "开场白不能超过 1000 字") String prologue,
      List<String> quickQuestions,
      List<String> tags,
      String clientRequestId) {

    AssistantService.Draft toDraft() {
      return new AssistantService.Draft(
          name,
          icon,
          description,
          parseType(type),
          uuid(modelId, "对话模型"),
          rolePrompt,
          uuids(kbIds, "知识库"),
          uuids(promptIds, "提示词"),
          retrieval == null ? null : retrieval.toDraft(),
          multiTurn,
          thinkingEnabled,
          prologue,
          quickQuestions,
          tags,
          clientRequestId);
    }
  }

  /** 检索参数（契约 {@code RetrievalParams}）。★ 各字段可空 ⇒ 由服务层按"缺省 / 保持原值"处理。 */
  public record RetrievalBody(
      String mode,
      Double threshold,
      Integer topN,
      String noRefStrategy,
      Integer queryCount,
      Boolean decomposeEnabled,
      Integer maxSubQuestions) {

    AssistantService.RetrievalDraft toDraft() {
      AssistantService.RetrievalDraft defaults = AssistantService.RetrievalDraft.defaults();
      return new AssistantService.RetrievalDraft(
          mode == null ? defaults.mode() : mode,
          threshold == null ? defaults.threshold() : threshold,
          topN == null ? defaults.topN() : topN,
          noRefStrategy == null ? defaults.noRefStrategy() : noRefStrategy,
          queryCount == null ? defaults.queryCount() : queryCount,
          decomposeEnabled == null ? defaults.decomposeEnabled() : decomposeEnabled,
          maxSubQuestions == null ? defaults.maxSubQuestions() : maxSubQuestions);
    }
  }

  /** 发布开关（契约 {@code setAssistantPublished}）。 */
  public record PublishedBody(boolean published) {}

  /**
   * 新建凭证的请求体（契约 {@code createCredential} 的 {@code { expiresAt: string | null }}）。
   *
   * <p>★★ <strong>这里没有 {@code maxClassification}</strong>，那是刻意的：冻结值由后端按请求者当时的 clearance 取（{@code 13
   * §5.2}）。字段不存在 ⇒ 调用方<strong>无法表达</strong>"我要一个更高密级的凭证" —— 比"收了再丢掉"强一档。
   *
   * <p>{@code null} 表示长期有效（契约 {@code expiresAt: string | null} 的注释）。 ★ {@code @RequestBody(required
   * = false)} 是必要的：契约里这两个字段都可有可无， 一个 {@code {}} 或空 body 都应当被理解成"长期有效"。
   */
  public record ExpiresBody(Instant expiresAt) {}

  /** 启停开关（契约 {@code setCredentialEnabled}）。 */
  public record EnabledBody(boolean enabled) {}

  private static AssistantType parseType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return AssistantType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "应用类型只支持 SIMPLE / WORKFLOW，收到：" + raw, "创建时请选定其中之一");
    }
  }

  private static UUID uuid(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, field + "的 id 不是合法 UUID，收到：" + raw, "请从列表里重新选择");
    }
  }

  private static List<UUID> uuids(List<String> raw, String field) {
    return raw == null ? null : raw.stream().map(each -> uuid(each, field)).toList();
  }
}
