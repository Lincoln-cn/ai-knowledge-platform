package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.assistant.application.AssistantView;
import cn.lincoln.aiplatform.assistant.application.TemplateService;
import cn.lincoln.aiplatform.assistant.domain.Template;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板中心（{@code #295}）。
 *
 * <p>五个动作：列表 / 详情 / 从应用存成模板 / 从模板建应用 / 删模板。
 *
 * <p>★ 没有 {@code PUT}：模板是不可变快照，改模板 = 存一个新模板（见 {@link TemplateService} 的类注释）。
 * 一个"能改的模板"会让已经克隆出去的应用不知道自己当初是从哪个版本来的。
 */
@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

  private final TemplateService templates;

  public TemplateController(TemplateService templates) {
    this.templates = templates;
  }

  /** 当前操作人（与 {@code AssistantController} 一致：JWT 的 subject 就是用户 id） */
  private static UUID actorOf(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  /** 列表（所有登录用户可看：模板本来就是给人挑的） */
  @GetMapping
  @PreAuthorize("hasAuthority('assistant:view')")
  public List<TemplateView> list() {
    return templates.list().stream().map(TemplateView::of).toList();
  }

  /** 详情 */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('assistant:view')")
  public TemplateView get(@PathVariable UUID id) {
    return TemplateView.of(templates.get(id));
  }

  /**
   * 把一个应用存成模板。
   *
   * <p>★ 借 {@code assistant:edit} 而不是另开一个 {@code template:edit}：这一版模板的唯一来源是
   * 「从某个应用抽出来」，能抽出模板的人必然是能改那个应用的人。单独开一个权限点会立刻出现 「有 template:edit 却看不到任何应用」的空角色，那是权限模型的噪音，不是粒度。
   */
  @PostMapping
  @PreAuthorize("hasAuthority('assistant:edit')")
  @ResponseStatus(HttpStatus.CREATED)
  public TemplateView create(@RequestBody CreateBody body, @AuthenticationPrincipal Jwt jwt) {
    return TemplateView.of(
        templates.createFromApp(
            body.sourceAppId(), body.name(), body.description(), body.category(), actorOf(jwt)));
  }

  /** 从模板建一个应用（建应用本身要 {@code assistant:edit}） */
  @PostMapping("/{id}/instantiate")
  @PreAuthorize("hasAuthority('assistant:edit')")
  @ResponseStatus(HttpStatus.CREATED)
  public AssistantView instantiate(
      @PathVariable UUID id,
      @RequestBody(required = false) InstantiateBody body,
      @AuthenticationPrincipal Jwt jwt) {
    boolean skip = body != null && Boolean.TRUE.equals(body.skipMissingReferences());
    return templates.instantiate(id, skip, actorOf(jwt));
  }

  /** 删模板（内置模板会被拒） */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('assistant:edit')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    templates.delete(id, actorOf(jwt));
  }

  /**
   * 模板视图。
   *
   * <p>★ 不带 {@code payload} 的全文：列表页用不到那棵树，而模板的 payload 就是一份完整导出包， 全量返回会让列表接口的体积与模板数成正比。
   */
  public record TemplateView(
      UUID id,
      String name,
      String description,
      String category,
      UUID sourceAppId,
      boolean builtin,
      String createdAt) {

    static TemplateView of(Template t) {
      return new TemplateView(
          t.id(),
          t.name(),
          t.description(),
          t.category(),
          t.sourceAppId(),
          t.builtin(),
          t.createdAt() == null ? null : t.createdAt().toString());
    }
  }

  /** 建模板的请求体 */
  public record CreateBody(UUID sourceAppId, String name, String description, String category) {}

  /** 从模板建应用的请求体（可省）。{@code skipMissingReferences} 缺省按 {@code false} 处理 */
  public record InstantiateBody(Boolean skipMissingReferences, Map<String, Object> extra) {}
}
