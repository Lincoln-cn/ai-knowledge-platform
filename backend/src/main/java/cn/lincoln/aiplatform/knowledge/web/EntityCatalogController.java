package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.EntityCatalogService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实体与关系的只读端点（0.9.8 ④⑤⑥，{@code #370}）。
 *
 * <p>★ 一个端点出三样（实体 / 关系 / 图谱），因为它们在界面上是同一个面板的三个视图： 分成三个请求会有三次往返，并且"实体列表"与"关系列表"可能是两个时刻的快照
 * （关系里出现指向不在列表里的实体的边）。见 {@link EntityCatalogService} 的类注释。
 *
 * <p>★ 权限点 {@code knowledge:view}：这一页只是"看清库里抽到了什么"，不改任何数据。 与 {@code EntityMergeController} 同口径 ——
 * 那个的 GET 用的是同一个权限点。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/entity-graph")
public class EntityCatalogController {

  private final EntityCatalogService catalog;

  public EntityCatalogController(EntityCatalogService catalog) {
    this.catalog = catalog;
  }

  /**
   * 一个库的实体与关系快照。
   *
   * @param entityLimit 实体上限（默认 500，最多 2000）
   * @param relationLimit 关系上限（默认 2000）
   */
  @GetMapping
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<EntityCatalogService.GraphView> graph(
      @PathVariable UUID kbId,
      @RequestParam(defaultValue = "500") int entityLimit,
      @RequestParam(defaultValue = "2000") int relationLimit) {
    return ApiResponse.ok(catalog.graph(kbId, entityLimit, relationLimit));
  }
}
