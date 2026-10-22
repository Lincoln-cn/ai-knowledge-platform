package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.PromptCatalogService;
import cn.lincoln.aiplatform.assistant.application.PromptCatalogService.PromptConfigView;
import cn.lincoln.aiplatform.assistant.application.PromptCatalogService.PromptTypeView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词类型与模板的管理端点（#196）。
 *
 * <p>路径是顶层 {@code /prompt-types} / {@code /prompt-configs}——与前端契约 {@code
 * frontend/src/api/prompts.ts} 一致；不挂到 {@code /assistants} 下，避免和应用子路径的 UUID 约束互相打架。
 */
@RestController
@RequestMapping("/api/v1")
public class PromptCatalogController {

  private final PromptCatalogService catalog;

  public PromptCatalogController(PromptCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/prompt-types")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PageResult<PromptTypeView>> listTypes(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(catalog.listTypes(page, size));
  }

  @PostMapping("/prompt-types")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<PromptTypeView> createType(@RequestBody UpsertPromptTypeBody body) {
    return ApiResponse.ok(catalog.createType(body.typeName(), body.icon()));
  }

  @PutMapping("/prompt-types/{id}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<PromptTypeView> updateType(
      @PathVariable UUID id, @RequestBody UpsertPromptTypeBody body) {
    return ApiResponse.ok(catalog.updateType(id, body.typeName(), body.icon()));
  }

  @DeleteMapping("/prompt-types/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public void deleteType(@PathVariable UUID id) {
    catalog.deleteType(id);
  }

  @GetMapping("/prompt-configs")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<PageResult<PromptConfigView>> listConfigs(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) UUID typeId,
      @RequestParam(required = false) String keyword) {
    return ApiResponse.ok(catalog.listConfigs(page, size, typeId, keyword));
  }

  @PostMapping("/prompt-configs")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<PromptConfigView> createConfig(@RequestBody UpsertPromptConfigBody body) {
    return ApiResponse.ok(
        catalog.createConfig(
            body.roleName(), body.callWord(), body.typeId(), body.description(), body.icon()));
  }

  @PutMapping("/prompt-configs/{id}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  public ApiResponse<PromptConfigView> updateConfig(
      @PathVariable UUID id, @RequestBody UpsertPromptConfigBody body) {
    return ApiResponse.ok(
        catalog.updateConfig(
            id, body.roleName(), body.callWord(), body.typeId(), body.description(), body.icon()));
  }

  @DeleteMapping("/prompt-configs/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('assistant:edit')")
  public void deleteConfig(@PathVariable UUID id) {
    catalog.deleteConfig(id);
  }

  /** 类型新建/更新请求。 */
  public record UpsertPromptTypeBody(String typeName, String icon) {}

  /** 模板新建/更新请求。 */
  public record UpsertPromptConfigBody(
      String roleName, String callWord, UUID typeId, String description, String icon) {}
}
