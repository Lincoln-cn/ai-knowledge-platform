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
   * <p>★ <b>由 GET 改成 POST</b>（契约 {@code listBaseModels}）： 请求里要带 {@code apiKey}，
   * 密钥<strong>不能进查询串</strong>（会进日志 / 代理 / 浏览器历史）。 返回形状不变（{@code BaseModelOption[]}）。
   *
   * <p>★ {@code apiBase} / {@code apiKey} 为空时照旧走默认上游（现行为不回归）； 给了就走指定上游 —— 外部 API 的模型目录这才看得见。
   *
   * <p>★ 上游不可达 ⇒ 200 + 空列表 + 包络 {@code hint}（不是 500，也不是干巴巴的空数组）。
   */
  @PostMapping("/base-models")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<List<BaseModelOption>> baseModels(@RequestBody BaseModelsBody body) {
    ModelService.BaseModelCatalog catalog =
        modelService.baseModels(
            body.supplier(),
            body.kind(),
            uuidOrNull(body.providerId(), "服务商"),
            body.apiBase(),
            body.apiKey());
    return catalog.hint() == null
        ? ApiResponse.ok(catalog.options())
        : ApiResponse.okWithHint(catalog.options(), catalog.hint());
  }

  /**
   * 本地 ONNX（进程内向量化）的真实状态。
   *
   * <p>★ `ai_model.status` 是上游加载生命周期，不能冒充它（`#191`）。模型管理页据此显示「就绪 / 尚未预热 / 文件缺失 /
   * 加载失败」，而不是把进程内模型标成「未加载」。
   */
  @GetMapping("/local-onnx")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelService.LocalOnnxStatus> localOnnxStatus() {
    return ApiResponse.ok(modelService.localOnnxStatus());
  }

  /** 预热本地 ONNX（初始化 ONNX session）。★ 对进程内模型来说，这才是「加载」的正确含义。 */
  @PostMapping("/local-onnx/warmup")
  @PreAuthorize("hasAuthority('model:manage')")
  public ApiResponse<ModelService.LocalOnnxStatus> warmUpLocalOnnx() {
    return ApiResponse.ok(modelService.warmUpLocalOnnx());
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
        modelService.probeBaseModel(
            body.baseModel(), body.supplier(), uuidOrNull(body.providerId(), "服务商"), body.kind()));
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
      String providerId,
      String apiBase,
      String apiKey,
      Integer dimension,
      ThinkingMode thinking,
      ToolsMode tools,
      String remark) {

    ModelService.UpsertCommand toCommand() {
      return new ModelService.UpsertCommand(
          name,
          kind,
          supplier,
          baseModel,
          uuidOrNull(providerId, "服务商"),
          apiBase,
          apiKey,
          dimension,
          null,
          thinking,
          tools,
          remark);
    }
  }

  /** 探测能力的请求体（契约 {@code ProbeBaseModelBody}）。 */
  record ProbeBaseModelBody(
      @NotBlank String baseModel, Supplier supplier, String providerId, ModelKind kind) {}

  /**
   * 基础模型目录的请求体（契约 {@code listBaseModels} 入参）。
   *
   * <p>★ {@code apiKey} 只走 body，绝不进 URL —— 密钥进查询串会进日志 / 代理 / 浏览器历史。
   *
   * @param apiBase 指定上游服务地址；为空走默认上游
   * @param apiKey 上游 API key；为空用默认 key
   */
  record BaseModelsBody(
      String supplier, String kind, String providerId, String apiBase, String apiKey) {}

  /** 契约里的 id 是字符串；空串当“没传”，非法值给可读 400（而不是 Jackson 的开发者消息）。 */
  private static UUID uuidOrNull(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new cn.lincoln.aiplatform.platform.domain.BusinessException(
          cn.lincoln.aiplatform.platform.domain.ErrorCode.BAD_REQUEST,
          field + "的 id 不是合法 UUID，收到：" + raw,
          "请从服务商列表里重新选择");
    }
  }
}
