package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.application.DocumentView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档的<strong>按 id 动作</strong>端点（契约 {@code frontend/src/api/ingestion.ts} 的 {@code startEmbedding}
 * 等）。
 *
 * <p>★ 为什么与 {@code DocumentController} 分开：那个是<strong>知识库下</strong>的文档资源 （{@code
 * /knowledge-bases/:kbId/documents}），这里是<strong>文档自身</strong>的动作 （{@code
 * /documents/:id/...}）。两条路径的权限与可见性判定起点不同，混在一个类里会看不清。
 *
 * <p>★ 权限点与<strong>前端按钮的可见条件一致</strong>（{@code knowledge:document:upload}）： 界面给了入口、后端却
 * 403，是最容易被当成"权限配错了"的那类不一致。
 *
 * <p>★ 写端点必须带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest} 构建期检查。
 *
 * <p>⚠️ 本批只落 {@code POST /{id}/embedding}。契约里同一族还有 {@code retry} / {@code reembed} / 改名 / 启停 / 删除
 * —— 它们仍欠着（见 {@code ContractDriftTest} 的未实现清单与 CHANGELOG）。
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentActionController {

  private final DocumentService documentService;

  public DocumentActionController(DocumentService documentService) {
    this.documentService = documentService;
  }

  /**
   * 开始向量化（契约 {@code startEmbedding}：用户显式动作，后端不自动推进）。
   *
   * <p>★ 立刻返回「向量化中」的文档视图：真正的工作在后台分批跑，进度由每个批次回写。 前端据此轮询 —— 进度来自库里的数字，所以刷新页面不会重置。
   */
  @PostMapping("/{id}/embedding")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<DocumentView> startEmbedding(@PathVariable UUID id) {
    return ApiResponse.ok(documentService.startEmbedding(id));
  }

  /**
   * 重试（契约 {@code retryDocument}）—— 从失败阶段继续（{@code 03 §3.5}）。
   *
   * <p>★ 分段失败的重试如实拒绝（需要原始文件，本版不保留）—— 假装重试一次会让用户反复点、 状态一直不变，然后去翻日志。
   */
  @PostMapping("/{id}/retry")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<DocumentView> retry(@PathVariable UUID id) {
    return ApiResponse.ok(documentService.retry(id));
  }

  /**
   * 重新向量化（契约 {@code reembedDocument}）—— 例如换了向量模型之后。
   *
   * <p>★ 它会把该文档的全部分段退回「待向量化」—— 只改文档状态不改分段的话，向量化器会因为 「已完成的分段跳过」而一个都不做，界面却显示在跑。
   */
  @PostMapping("/{id}/reembed")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<DocumentView> reembed(@PathVariable UUID id) {
    return ApiResponse.ok(documentService.reembed(id));
  }
}
