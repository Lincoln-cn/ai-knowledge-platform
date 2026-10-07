package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.KnowledgeBaseService;
import cn.lincoln.aiplatform.knowledge.application.RetrievalParamsView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库的检索参数（契约 {@code frontend/src/api/retrieval.ts} 的后两个端点）。
 *
 * <h3>为什么这两个端点住在 {@code knowledge} 而不是 {@code retrieval}</h3>
 *
 * <p>因为<strong>参数是知识库的一部分</strong>：四列就住在 {@code kb_knowledge_base} 上 （{@code
 * V20}），可见性与写权限也复用知识库那一套判定（{@code 07 §3.3} 硬规矩 1： 读与写都先问同一个判定）。检索模块消费它们，但不拥有它们 —— 这与 "命中测试在 {@code
 * retrieval}"并不矛盾：{@code POST /retrieval/test} 是**跑一次检索**， 而这里是**配置**。
 *
 * <p>★ 权限点：读 {@code knowledge:view} / 写 {@code knowledge:manage} —— 与 mock （{@code gateKb(...,
 * PERM.knowledgeView / knowledgeManage)}）一致。 写端点必须带 {@code @PreAuthorize}：{@code
 * AuthorizationDeclarationTest} 构建期检查。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/retrieval")
public class KnowledgeBaseRetrievalController {

  private final KnowledgeBaseService knowledgeBaseService;

  public KnowledgeBaseRetrievalController(KnowledgeBaseService knowledgeBaseService) {
    this.knowledgeBaseService = knowledgeBaseService;
  }

  /** 读当前参数。★ 新库回默认值（HYBRID / 0.6 / 5 / REFUSE），不是 404。 */
  @GetMapping
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<RetrievalParamsView> get(@PathVariable UUID kbId) {
    return ApiResponse.ok(knowledgeBaseService.retrievalParams(kbId));
  }

  /**
   * 改参数。★ 契约是 Partial（只传改动的字段），未传的保持原值。
   *
   * <p>看不见的库返 <strong>404</strong>（不是 403）—— 与知识库其它端点同口径。
   */
  @PutMapping
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<RetrievalParamsView> update(
      @PathVariable UUID kbId,
      @RequestBody UpdateRetrievalParamsBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        knowledgeBaseService.updateRetrievalParams(
            kbId,
            body.mode(),
            body.threshold(),
            body.topN(),
            body.noRefStrategy(),
            body.queryCount(),
            body.decomposeEnabled(),
            body.maxSubQuestions(),
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 请求体（Partial：字段都可空）。
   *
   * <p>★ 查询理解那三个（第 12 周 · 0.4.0）也是可空的同一种语义：<strong>null = 这次不改这个字段</strong>。 这里最容易写错的写法是"用 {@code
   * record} 的默认值兜底" —— 那会让"只改 TopN"顺手把查询理解也重置， 而用户看不出发生了什么（同 {@code 08 §4.6} 对 Partial 的注释）。
   */
  public record UpdateRetrievalParamsBody(
      String mode,
      Double threshold,
      Integer topN,
      String noRefStrategy,
      Integer queryCount,
      Boolean decomposeEnabled,
      Integer maxSubQuestions) {}
}
