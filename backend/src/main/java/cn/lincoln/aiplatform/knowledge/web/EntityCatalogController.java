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
 * 实体与关系的只读端点（0.9.8 ④⑤⑥，{@code #370}；0.9.10 拆成三个）。
 *
 * <p>★ 2026-11-29 拆开：以前一个端点出三样（实体 / 关系 / 图谱），理由是"三个视图共用一份快照，
 * 分成三个请求会有三次往返，并且关系里可能出现指向不在列表里的实体的边"。那个理由在 <b>1400 实体</b>的真实库里不再成立 ——
 * 一次取齐意味着要么截断（用户看到"图里没有不代表库里没有" 这种只能靠文案补的缺口）、要么把几 MB 塞给浏览器。
 *
 * <p>现在的分工：{@code /entity-graph} 只给图谱（节点 = 出现在关系里的实体，天然有界且每条边都在图上）， {@code /entities} 与 {@code
 * /relations} 给表格（库里分页）。三个请求换来的是"每一屏都在它自己的尺度上 取数"，而不是给所有人看同一份被截断的快照。
 *
 * <p>★ 权限点 {@code knowledge:view}：这一页只是"看清库里抽到了什么"，不改任何数据。 与 {@code EntityMergeController} 同口径 ——
 * 那个的 GET 用的是同一个权限点。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}")
public class EntityCatalogController {

  private final EntityCatalogService catalog;

  public EntityCatalogController(EntityCatalogService catalog) {
    this.catalog = catalog;
  }

  /**
   * 图谱：节点只含出现在关系里的实体，边取全量（有上限）。
   *
   * @param relationLimit 关系上限（默认 2000）
   */
  @GetMapping("/entity-graph")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<EntityCatalogService.GraphView> graph(
      @PathVariable UUID kbId, @RequestParam(defaultValue = "2000") int relationLimit) {
    return ApiResponse.ok(catalog.graph(kbId, relationLimit));
  }

  /**
   * 实体表格的一页。
   *
   * @param type 只看某一类（组织 / 人名 / 职责 / 文书 / 术语）
   * @param keyword 名字包含
   * @param page 页码，从 1 开始
   * @param size 每页条数（默认 50，最多 500）
   */
  @GetMapping("/entities")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<EntityCatalogService.PageView<EntityCatalogService.NodeView>> entities(
      @PathVariable UUID kbId,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.ok(catalog.entities(kbId, type, keyword, page, size));
  }

  /**
   * 关系表格的一页。
   *
   * @param page 页码，从 1 开始
   * @param size 每页条数（默认 50，最多 500）
   */
  @GetMapping("/relations")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<EntityCatalogService.PageView<EntityCatalogService.RelationRowView>> relations(
      @PathVariable UUID kbId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ApiResponse.ok(catalog.relations(kbId, page, size));
  }
}
