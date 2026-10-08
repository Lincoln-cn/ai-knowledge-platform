package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.application.OrgService;
import cn.lincoln.aiplatform.identity.domain.Org;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织机构（{@code 07 §十}）。
 *
 * <h3>★ 读与写的权限分开，理由与字典一样</h3>
 *
 * <p>读：<strong>登录即可</strong>。建档时要选"责任部门"，而那是召回前缀的一部分 —— 卡上管理权限等于让每个上传者在建档页撞 403。写：{@code
 * identity:org:manage}。
 *
 * <h3>★ 为什么没有"改 code"的接口</h3>
 *
 * <p>{@code code} 是共享主体（grant 的 {@code subject_id}）与审计里的标识 —— 改它等于把"共享给谁"悄悄指到别处。要改展示名改 {@code
 * name}（★ 但要注意它会进文档前缀， 改名意味着该部门下的文档需要重新向量化，界面上必须提示）。
 */
@RestController
@RequestMapping("/api/v1/orgs")
public class OrgController {

  private final OrgService orgs;

  public OrgController(OrgService orgs) {
    this.orgs = orgs;
  }

  /** 树（嵌套形态）—— 前端树控件用。 */
  @GetMapping("/tree")
  public ApiResponse<List<OrgNodeView>> tree() {
    return ApiResponse.ok(orgs.tree().stream().map(OrgNodeView::of).toList());
  }

  /** 扁平清单（下拉、共享主体选择器用）。 */
  @GetMapping
  public ApiResponse<List<OrgView>> list() {
    return ApiResponse.ok(orgs.list().stream().map(OrgView::of).toList());
  }

  @PostMapping
  @PreAuthorize("hasAuthority('identity:org:manage')")
  public ApiResponse<OrgView> create(
      @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        OrgView.of(
            orgs.create(
                body.parentId(),
                body.code(),
                body.name(),
                body.sort(),
                UUID.fromString(jwt.getSubject()))));
  }

  /** 改展示名 / 排序 / 启停（Partial）。★ 不含 {@code code}，见类注释。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('identity:org:manage')")
  public ApiResponse<OrgView> update(
      @PathVariable UUID id, @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        OrgView.of(
            orgs.update(
                id, body.name(), body.sort(), body.enabled(), UUID.fromString(jwt.getSubject()))));
  }

  /** 搬到新上级之下；{@code parentId} 为 null = 变成根。 */
  @PostMapping("/{id}/move")
  @PreAuthorize("hasAuthority('identity:org:manage')")
  public ApiResponse<OrgView> move(
      @PathVariable UUID id, @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        OrgView.of(orgs.move(id, body.parentId(), UUID.fromString(jwt.getSubject()))));
  }

  /** 删一个**空**部门（有下级或有用户 ⇒ 400 + 指路到"停用"）。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('identity:org:manage')")
  public ApiResponse<Void> delete(@PathVariable UUID id) {
    orgs.delete(id);
    return ApiResponse.ok(null);
  }

  /** 请求体（新建与修改共用；修改时只传要改的字段 —— 与字典端点同一种 Partial 语义）。 */
  public record UpsertBody(
      UUID parentId, String code, String name, Integer sort, Boolean enabled) {}

  /** 扁平项。 */
  public record OrgView(
      UUID id, UUID parentId, String code, String name, String path, int sort, boolean enabled) {

    static OrgView of(Org org) {
      return new OrgView(
          org.id(), org.parentId(), org.code(), org.name(), org.path(), org.sort(), org.enabled());
    }
  }

  /** 树节点（嵌套）。 */
  public record OrgNodeView(
      UUID id,
      String code,
      String name,
      String path,
      int sort,
      boolean enabled,
      List<OrgNodeView> children) {

    static OrgNodeView of(OrgService.OrgNode node) {
      return new OrgNodeView(
          node.org().id(),
          node.org().code(),
          node.org().name(),
          node.org().path(),
          node.org().sort(),
          node.org().enabled(),
          node.children().stream().map(OrgNodeView::of).toList());
    }
  }
}
