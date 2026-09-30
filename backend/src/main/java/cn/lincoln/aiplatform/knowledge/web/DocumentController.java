package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.application.DocumentView;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.List;
import java.util.Map;
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
   * <p>★ 契约两套形状并存时<strong>禁止静默忽略</strong>：只传 {@code uploadIds} / {@code segmentationPlan}（暂存导入形态）
   * 而不传 {@code names}（轻量建档形态）⇒ 400 且指路 —— 「收下但不用」会让调用方以为暂存导入已生效。 同时给时以 {@code names} 为准。
   *
   * <p>★ 真文件暂存（{@code stageUploads}）与 multipart 上传随上传链补； 本批用文件名清单把文档建出来即可。
   */
  @PostMapping("/{kbId}/documents")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<List<DocumentView>> register(
      @PathVariable UUID kbId,
      @RequestBody RegisterDocumentsBody body,
      @AuthenticationPrincipal Jwt jwt) {
    // ★ 形状校验在入口做：暂存导入形态「出现但 names 缺失」不许静默忽略
    if (body.stagedShapeRequested() && body.namesMissing()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "暂存上传尚未支持，请用 names 提供文档名",
          "uploadIds / segmentationPlan 是暂存导入形态（stageUploads 的产物），后端尚未消费——收下但不用会让人以为导入生效了。当前请用"
              + " names 提供文件名清单建档");
    }
    return ApiResponse.ok(
        documentService.register(
            kbId,
            body.names(),
            null,
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
   * <p>★ 两套建档形状（与契约 {@code ImportDocumentsBody} 同口径）：
   *
   * <ul>
   *   <li>{@code names} = <strong>轻量建档</strong>（只登记文档名，不带文件）—— 当前唯一落地形态
   *   <li>{@code uploadIds} / {@code segmentationPlan} = <strong>暂存上传后的导入</strong> —— 后端尚未消费，属欠账；
   *       只传它们不传 {@code names} ⇒ 400，同时给时以 {@code names} 为准
   * </ul>
   *
   * @param names 文件名清单（轻量建档形态；当前后端唯一消费的建档入参）
   * @param uploadIds 暂存上传产物 id —— <b>后端尚未消费</b>，只为「出现时能显式拒」而收下，不许静默丢弃
   * @param segmentationPlan 切分计划 —— 同上，<b>后端尚未消费</b>
   * @param docType 文种 ★ <b>必填</b>
   * @param department 责任部门 ★ <b>必填</b>
   * @param classification 密级 ★ <b>必填</b> —— 且必须 ≤ 库的 ceiling
   */
  /**
   * ★ 带文件字节的建档（契约的 {@code uploadDocuments}）：multipart 上传 ⇒ <strong>建档即解析</strong>。
   *
   * <p>与 JSON 形态（{@link #register}）共用同一套校验与同一套解析 —— 只是多了字节，于是能真的把 DOCX/PDF 解析成 Markdown（第 6 周演示判据）。
   */
  @PostMapping(
      value = "/{kbId}/documents",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<List<DocumentView>> registerFiles(
      @PathVariable UUID kbId,
      @org.springframework.web.bind.annotation.RequestParam("files")
          List<org.springframework.web.multipart.MultipartFile> files,
      @org.springframework.web.bind.annotation.RequestParam("docType") String docType,
      @org.springframework.web.bind.annotation.RequestParam("department") String department,
      @org.springframework.web.bind.annotation.RequestParam("classification")
          ClassificationLevel classification,
      @AuthenticationPrincipal Jwt jwt)
      throws java.io.IOException {
    java.util.List<String> names = new java.util.ArrayList<>();
    java.util.List<byte[]> contents = new java.util.ArrayList<>();
    for (org.springframework.web.multipart.MultipartFile file : files) {
      names.add(file.getOriginalFilename());
      contents.add(file.getBytes());
    }
    return ApiResponse.ok(
        documentService.register(
            kbId,
            names,
            contents,
            docType,
            department,
            classification,
            UUID.fromString(jwt.getSubject())));
  }

  record RegisterDocumentsBody(
      List<String> names,
      List<String> uploadIds,
      List<Map<String, Object>> segmentationPlan,
      String docType,
      String department,
      ClassificationLevel classification) {

    /** 暂存导入形态是否「出现」（字段传了就算，空数组也算 —— 出现即不许静默吞）。 */
    boolean stagedShapeRequested() {
      return uploadIds != null || segmentationPlan != null;
    }

    /** names 缺失或为空清单。 */
    boolean namesMissing() {
      return names == null || names.isEmpty();
    }
  }
}
