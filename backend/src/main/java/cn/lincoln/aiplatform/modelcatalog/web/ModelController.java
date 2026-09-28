package cn.lincoln.aiplatform.modelcatalog.web;

import cn.lincoln.aiplatform.modelcatalog.application.BaseModelOption;
import cn.lincoln.aiplatform.modelcatalog.application.ModelCapability;
import cn.lincoln.aiplatform.modelcatalog.application.ModelService;
import cn.lincoln.aiplatform.modelcatalog.application.ModelView;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.Supplier;
import cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode;
import cn.lincoln.aiplatform.modelcatalog.domain.ToolsMode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import jakarta.validation.constraints.NotBlank;
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
 * 模型端点（契约 {@code frontend/src/api/models.ts} 的 8 个端点）。
 *
 * <p>★ 全部带 {@code @PreAuthorize("hasAuthority('model:manage')")}，<strong>含 GET</strong> ——
 * 模型资产是平台级配置， 不是普通用户该看到的（与 {@code /users} 的 {@code system:manage} 同一条纪律）。
 *
 * <p>★ 这些写端点<strong>必须</strong>带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest}
 * 会在构建期检查，漏一个就红。
 *
 * <p>★ 视图绝不回传 {@code apiKey} 明文（与 {@code UserView} 不含 {@code passwordHash} 同一条纪律）—— {@link
 * ModelView} 显式列出每个出网字段。
 */
@RestController
@RequestMapping("/api/v1/models")
@PreAuthorize("hasAuthority('model:manage')")
public class ModelController {

  private final ModelService modelService;

  public ModelController(ModelService modelService) {
    this.modelService = modelService;
  }

  // ────────────────────────── 查询 ──────────────────────────

  /**
   * 分页列表（keyword / kind / status 筛选拼进查询，不是查完再筛）。
   *
   * <p>keyword 对 name / baseModel 大小写不敏感模糊匹配；空串等同不筛。
   */
  @GetMapping
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<PageResult<ModelView>> list(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) ModelKind kind,
      @RequestParam(required = false) ModelStatus status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(modelService.list(keyword, kind, status, page, size));
  }

  /**
   * 基础模型目录（画布"新建模型"弹窗的卡片列表）。
   *
   * <p>★ 上游不可达 ⇒ 空列表（不是 500）—— 由 {@link ModelService#baseModels} 保证。
   */
  @GetMapping("/base-models")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<List<BaseModelOption>> baseModels(
      @RequestParam(required = false) String supplier,
      @RequestParam(required = false) String kind) {
    return ApiResponse.ok(modelService.baseModels(supplier, kind));
  }

  // ────────────────────────── 注册 CRUD ──────────────────────────

  @PostMapping
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelView> create(
      @RequestBody UpsertModelBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(modelService.create(body.toCommand(), UUID.fromString(jwt.getSubject())));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelView> update(
      @PathVariable UUID id, @RequestBody UpsertModelBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        modelService.update(id, body.toCommand(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 删除模型。
   *
   * <p>★ 拒绝条件是「被应用/知识库引用」而不是「已加载」（{@code 08 §3.2} 连带修正）—— 否则加载过的模型永远删不掉。 builtin 不可删。删除时清掉启用位。
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<Void> delete(@PathVariable UUID id) {
    modelService.delete(id);
    return ApiResponse.ok();
  }

  // ────────────────────────── load 语义 ──────────────────────────

  /**
   * 触发加载。★ 加载是<strong>异步</strong>的，返回时 status 仍是「加载中」（契约写明）。
   *
   * <p>语义 = 平台启用位 + 上游可用性校验，不操纵上游实例（{@code 09 §3.13.4}）。
   */
  @PostMapping("/{id}/load")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelView> load(@PathVariable UUID id) {
    return ApiResponse.ok(modelService.load(id));
  }

  // ────────────────────────── 能力探测 ──────────────────────────

  /** 按已存模型的 id 探测能力。 */
  @PostMapping("/{id}/probe")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelCapability> probeById(@PathVariable UUID id) {
    return ApiResponse.ok(modelService.probeById(id));
  }

  /**
   * 按基础模型探测能力（新建时 id 还不存在，但能力必须在保存前就填好）。
   *
   * <p>★ 这是从旧实现迁移时发现的缺口 —— 探测必须能在「未保存」状态下发生。
   */
  @PostMapping("/probe")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelCapability> probeBaseModel(@RequestBody ProbeBaseModelBody body) {
    return ApiResponse.ok(
        modelService.probeBaseModel(body.baseModel(), body.supplier(), body.kind()));
  }

  // ────────────────────────── 请求体 ──────────────────────────

  /**
   * 注册 / 更新模型的请求体（契约 {@code UpsertModelBody}）。
   *
   * <p>★ {@code apiKey} 是明文收进来的（契约如此），由 {@code ApiKeyCipher} 加密后落库 —— 明文永远不出网、不入库。
   *
   * @param dimension 向量模型必须显式下发；chat 模型必须为空
   */
  record UpsertModelBody(
      @NotBlank String name,
      ModelKind kind,
      Supplier supplier,
      @NotBlank String baseModel,
      String apiBase,
      String apiKey,
      Integer dimension,
      ThinkingMode thinking,
      ToolsMode tools,
      String remark) {

    ModelService.UpsertCommand toCommand() {
      return new ModelService.UpsertCommand(
          name, kind, supplier, baseModel, apiBase, apiKey, dimension, null, thinking, tools,
          remark);
    }
  }

  /** 探测能力的请求体（契约 {@code ProbeBaseModelBody}）。 */
  record ProbeBaseModelBody(@NotBlank String baseModel, Supplier supplier, ModelKind kind) {}
}
