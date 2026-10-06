package cn.lincoln.aiplatform.retrieval.web;

import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.retrieval.application.HitTestQuery;
import cn.lincoln.aiplatform.retrieval.application.HitTestView;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索端点（契约 {@code frontend/src/api/retrieval.ts}）。
 *
 * <p>★ 本控制器只提供"跑一次检索"这一件事。**参数（mode/threshold/topN/noRefStrategy） 是知识库的一部分**，它们的读写端点在 {@code
 * /knowledge-bases/:kbId/retrieval} —— 挂在知识库那侧（{@code KnowledgeBaseRetrievalController}），因为那四列就住在
 * {@code kb_knowledge_base} 上，可见性判定也复用知识库那一套（{@code 07 §3.3}）。
 *
 * <p>★ 权限点用 {@code knowledge:view}：命中测试是**读**（只是代用户跑一次检索， 不落任何数据），用 {@code knowledge:manage}
 * 会让"只想看看检索效果"的角色被挡在门外。 而它会不会读到不该读的库，由 {@code RetrievalService} 里的可见范围判定管 ——
 * 权限点管"能不能用这个功能"，可见性管"能看见哪些数据"，两件事（{@code 07} 的两个维度）。
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

  private final RetrievalService retrievalService;

  public RetrievalController(RetrievalService retrievalService) {
    this.retrievalService = retrievalService;
  }

  /**
   * 命中测试。一次请求跑一种模式；「对比模式」由前端并发调三次（契约 {@code hitTestCompare}）。
   *
   * <p>★ 不在后端做"一次返回三种模式"：那样会把三种模式的耗时加起来， 而 {@code elapsedMs} 恰恰是要给人看"哪种模式更快"的。
   */
  @PostMapping("/test")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<HitTestView> test(@RequestBody HitTestQuery request) {
    return ApiResponse.ok(retrievalService.hitTest(request));
  }
}
