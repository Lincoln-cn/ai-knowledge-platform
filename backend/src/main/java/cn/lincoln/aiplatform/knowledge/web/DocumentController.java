package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.application.DocumentView;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档建档端点（契约 {@code frontend/src/api/ingestion.ts} 的 documents 子资源）。
 *
 * <p>★ 本批只落<strong>建档</strong>（上传/导入的最小可用形态）：把文档建出来、元数据收准。 解析 / 分块 / 向量化是第 6–7 周的事，那些端点（retry /
 * embedding / reembed …）还没实现。
 *
 * <p>★ 权限点（{@code 07 §4.1}）：
 *
 * <ul>
 *   <li>{@code GET} 列表 ⇒ {@code knowledge:view}（与库列表同口径）
 *   <li>{@code POST} 建档 ⇒ {@code knowledge:document:upload}（上传文档 / 切分 / 向量化这一档）
 * </ul>
 *
 * <p>★ 写端点<strong>必须</strong>带 {@code @PreAuthorize}：{@code AuthorizationDeclarationTest}
 * 构建期检查，漏一个就红。
 *
 * <p>★ 可见性继承库：库不可见 ⇒ 其文档读/写都 <strong>404</strong>（不是 403）—— 与批 1 同一判定，见 {@code DocumentService}。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class DocumentController {

  private final DocumentService documentService;

  public DocumentController(DocumentService documentService) {
    this.documentService = documentService;
  }

  /**
   * 按库分页列文档（契约 {@code listDocuments}）。
   *
   * <p>★ 库不可见 ⇒ 404（文档可见性继承库）。
   */
  @GetMapping("/{kbId}/documents")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<PageResult<DocumentView>> list(
      @PathVariable UUID kbId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(documentService.list(kbId, page, size));
  }

  /**
   * 建档（契约 {@code uploadDocuments} / {@code importDocuments} 的最小可用形态）。
   *
   * <p>★ 三项必填（文种/责任部门/密级）缺失 ⇒ 400 并指出是哪一项； 密级高于库 ceiling ⇒ 400 + 可读原因（{@code 13 §6}）。
   *
   * <p>★ 真文件暂存（{@code stageUploads}）与 multipart 上传随上传链补； 本批用文件名清单把文档建出来即可。
   */
  @PostMapping("/{kbId}/documents")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<List<DocumentView>> register(
      @PathVariable UUID kbId,
      @RequestBody RegisterDocumentsBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.register(
            kbId,
            body.names(),
            body.docType(),
            body.department(),
            body.classification(),
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 建档请求体。
   *
   * <p>★ 批次级三项标签：一次导入给这批文档同一套（契约 {@code ImportDocumentsBody} 的同名三项）。 <em>逐篇覆盖是后续</em>（等单篇编辑端点落地）。
   *
   * @param names 文件名清单（最小建档形态；真文件暂存随上传链补）
   * @param docType 文种 ★ <b>必填</b>
   * @param department 责任部门 ★ <b>必填</b>
   * @param classification 密级 ★ <b>必填</b> —— 且必须 ≤ 库的 ceiling
   */
  record RegisterDocumentsBody(
      List<String> names, String docType, String department, ClassificationLevel classification) {}
}
