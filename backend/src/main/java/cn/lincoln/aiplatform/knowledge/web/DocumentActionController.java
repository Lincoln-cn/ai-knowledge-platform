package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.application.DocumentView;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * <p>⚠️ 本类已覆盖契约里该族的全部端点（{@code embedding} / {@code retry} / {@code reembed} / 改名 / 启停 / 删除）。★
 * 这一族曾经长期是「界面有按钮、后端 404」的重灾区：用户点「重命名」拿到的是一句 「接口不存在」，而这句话看起来像环境问题、不像功能没做。
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

  // ────────────────────────── 改名 / 启停 / 删除（契约 ingestion.ts 的三条） ──────────────────────────

  /**
   * 改名（契约 {@code renameDocument}）。
   *
   * <p>★ 权限点是 {@code knowledge:manage}，与<strong>前端那个按钮的可见条件</strong>一致 （{@code DocumentPanel.vue} 的
   * {@code canManage}）：界面给了入口、后端却 403，是最容易被误读成 「权限配错了」的一类不一致。改名是库管理者的动作，不是上传者的。
   */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<DocumentView> rename(
      @PathVariable UUID id, @RequestBody RenameBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.rename(id, body.name(), UUID.fromString(jwt.getSubject())));
  }

  /** 启用/停用（契约 {@code setDocumentEnabled}）。停用后不参与检索，数据保留。 */
  @PatchMapping("/{id}/enabled")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<DocumentView> setEnabled(
      @PathVariable UUID id, @RequestBody EnabledBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.setEnabled(id, body.enabled(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * ★ 变更文档密级（{@code #247}）。
   *
   * <p>★ 权限点 {code knowledge:manage}：密级同时决定召回前缀与安全过滤，是库管理者的动作。与「上传者」分开， 是因为上传者只会标自己看得见的文档，
   * 而密级变更的影响面是<strong>所有引用过它的会话</strong>。
   *
   * <p>★ 升密时后端会顺手把相关会话的水位抬到新值（同一事务，{@code 13 §2.2}）；降密不动水位。 两条口径见 {@code 13 §2.4}。
   */
  @PatchMapping("/{id}/labels")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<DocumentView> changeLabels(
      @PathVariable UUID id, @RequestBody LabelsBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.changeLabels(id, body.labels(), UUID.fromString(jwt.getSubject())));
  }

  @PatchMapping("/{id}/classification")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<DocumentView> changeClassification(
      @PathVariable UUID id,
      @RequestBody ClassificationBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.changeClassification(
            id, body.classification(), UUID.fromString(jwt.getSubject())));
  }

  /**
   * 删除（契约 {@code deleteDocument}）。
   *
   * <p>★ 文档软删、分段与向量真删（理由见 {@code DocumentService#delete}）：只软删文档会把它的内容 留在检索里 —— 列表看不见、检索答得出来。
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    documentService.delete(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  /**
   * 彻底删除（契约 `purgeDocument`，`#75` 判据 ④）—— 物理删除，**不可恢复**。
   *
   * <p>★ 用 `DELETE /{id}/purge` 而不是给 `DELETE /{id}` 加参数：一个是"从列表里消失"，一个是"这份材料不再存在"， 两种后果不该共用一个 URL
   * —— 共用的时候，少传一个参数就会把第二种当成第一种执行。
   *
   * <p>★ 必须已经软删过（服务层校验）：否则 400「这份文档还在使用中」。
   */
  @DeleteMapping("/{id}/purge")
  @PreAuthorize("hasAuthority('knowledge:manage')")
  public ApiResponse<Void> purge(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    documentService.purge(id, UUID.fromString(jwt.getSubject()));
    return ApiResponse.ok();
  }

  /** 改名请求体（契约 {@code renameDocument} 的 body）。 */
  record RenameBody(String name) {}

  /** 启用位请求体（契约 {@code setDocumentEnabled} 的 body，与知识库那条同形）。 */
  record EnabledBody(boolean enabled) {}

  /**
   * 密级变更请求体（{@code #247}）。
   *
   * <p>★ 用 {@link ClassificationLevel} 而不是 {@code String}：Jackson 会直接把非法取值判成 400， 而一个 {@code
   * String} 字段会让「密级写错」变成服务层的一句业务异常 —— 两者的响应体形状不同，调用方要分两套处理。
   */
  record ClassificationBody(ClassificationLevel classification) {}

  /**
   * 标签请求体。
   *
   * <p>★ 用 {@code List<String>} 而不是 {@code Set<String>}：JSON 数组本身有序，服务端按到达顺序回显更自然；
   * 归一（去空白、丢空串、去重）统一在 {@code Document.normalizeLabels} 里做，端点不做第二套。
   *
   * <p>★ {@code null} 与空数组等价，都表示"不受标签限制"（不是"等所有标签"）。
   */
  record LabelsBody(List<String> labels) {}
}
