package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.DocumentService;
import cn.lincoln.aiplatform.knowledge.application.DocumentView;
import cn.lincoln.aiplatform.knowledge.application.IngestionProgressView;
import cn.lincoln.aiplatform.knowledge.application.ReembedKbView;
import cn.lincoln.aiplatform.knowledge.domain.OrgAssignment;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
   * ★★ 指定/变更文档的责任部门（契约 {@code assignDocumentOrg}；{@code #150}）。
   *
   * <p>★ 它是 {@code #56} 那一档（{@code ?orgAssignment=UNASSIGNED}）真正的出口：没有它， 用户按提示找到"待补归属"的文档之后无事可做 ——
   * 只能删档重建，而重建会丢掉 {@code department_legacy} 那个存档值。
   *
   * <p>★ 权限点 {@code knowledge:document:upload}（与建档同一个）：改归属与建档属于同一类动作
   * （"把文档放进这个库并说明它归谁"），而**不是**阅读动作。
   *
   * <p>★ 只改归属：密级 / 文种 / 分段 / 向量一概不动，{@code department_legacy} 是存档列也不许清 （理由见 {@code
   * DocumentService#assignOrg} 的注释）。
   */
  @PatchMapping("/{kbId}/documents/{documentId}/org")
  @PreAuthorize("hasAuthority('knowledge:document:upload')")
  public ApiResponse<DocumentView> assignOrg(
      @PathVariable UUID kbId,
      @PathVariable UUID documentId,
      @RequestBody(required = false) AssignOrgBody body) {
    return ApiResponse.ok(
        documentService.assignOrg(kbId, documentId, body == null ? null : body.orgId()));
  }

  /** 指定责任部门的请求体（{@code orgId} 必填 —— 见 {@code Document#assignOrg} 的第二条纪律）。 */
  public record AssignOrgBody(UUID orgId) {}

  /**
   * 按库分页列文档（契约 {@code listDocuments}）。
   *
   * <p>★ 库不可见 ⇒ 404（文档可见性继承库）。
   *
   * <p>★★ {@code orgAssignment} 查询参数（{@code #56} 的出口）：{@code ?orgAssignment=UNASSIGNED} 列出
   * "回填责任部门时按名字匹配不上"的文档（{@code org_id} 为空、原值在 {@code department_legacy}）。 ★ 不传 = {@link
   * OrgAssignment#ANY} = 加这一档之前的行为，**既有调用方一个字节都不用改**。
   *
   * <p>★ 为什么用**状态名**而不是 {@code ?unassigned=true}：这一档回答的是"这些文档处于哪种归属状态"， 而布尔参数在调用点上读不出意图（与 {@code
   * OrgAssignment} 的类注释同一条）。
   */
  @GetMapping("/{kbId}/documents")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<PageResult<DocumentView>> list(
      @PathVariable UUID kbId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) OrgAssignment orgAssignment) {
    return ApiResponse.ok(documentService.list(kbId, page, size, orgAssignment));
  }

  /**
   * 库级入库进度（契约 {@code getIngestionProgress}）—— 库页面顶部进度条的<strong>唯一数据源</strong>。
   *
   * <p>★ 放在这里（而不是 {@code KnowledgeBaseController}）是因为它统计的是<strong>文档</strong>：
   * 与文档列表同源、同一条可见性判定，改的时候不会漏。
   *
   * <p>★ 前端只在 {@code running} 为 true 时轮询（{@code 03 §3.4}），所以这个字段的语义必须准。
   */
  @GetMapping("/{kbId}/ingestion-progress")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<IngestionProgressView> ingestionProgress(@PathVariable UUID kbId) {
    return ApiResponse.ok(documentService.progress(kbId));
  }

  /**
   * 库级重新向量化（契约 {@code reembedKnowledgeBase}）—— 整库排队，回执只给 {@code queued} 数。
   *
   * <p>★ 放在这里（而不是 {@code KnowledgeBaseController}）是因为它编排的是<strong>文档与分段</strong>： 与 {@link
   * #ingestionProgress} 同源，改的时候不会漏。
   *
   * <p>★ 权限点 {@code knowledge:segment:edit} —— 与前端那个「全部重新向量化」按钮的可见条件一致 （{@code SegmentPanel.vue} 的
   * {@code canEdit}）。
   */
  @PostMapping("/{kbId}/reembed")
  @PreAuthorize("hasAuthority('knowledge:segment:edit')")
  public ApiResponse<ReembedKbView> reembed(
      @PathVariable UUID kbId, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        documentService.reembedKnowledgeBase(kbId, UUID.fromString(jwt.getSubject())));
  }

  /**
   * 建档（契约 {@code uploadDocuments} / {@code importDocuments} 的最小可用形态）。
   *
   * <p>★ 三项必填（文种/责任部门/密级）缺失 ⇒ 400 并指出是哪一项； 密级高于库 ceiling ⇒ 400 + 可读原因（{@code 13 §6}）。
   *
   * <p>★ 责任部门自 {@code T300} 起是 <strong>组织树里的一个部门 id</strong>（{@code 07 §11.2}，issue #37）， 不再是自由文本
   * {@code department}：自由文本既进不了数据权限，又会因为可变/可重名而在按名字比较的 地方静默越权。★ 缺 {@code orgId} ⇒ <strong>可读的
   * 400</strong>（由用例层给"责任部门必填"）， 不是 500 —— 传错参数不能报成"服务器崩了"。
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
            body.orgId(),
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
   * @param orgId 责任部门（组织树里的部门 id）★ <b>必填</b> —— 它决定召回前缀里那段稳定 code
   * @param classification 密级 ★ <b>必填</b> —— 且必须 ≤ 库的 ceiling
   */
  /**
   * ★ 带文件字节的建档（契约的 {@code uploadDocuments}）：multipart 上传 ⇒ <strong>建档即解析</strong>。
   *
   * <p>与 JSON 形态（{@link #register}）共用同一套校验与同一套解析 —— 只是多了字节，于是能真的把 DOCX/PDF 解析成 Markdown（第 6 周演示判据）。
   *
   * <p>★ 表单字段 {@code orgId} 标成「非必填」是刻意的：缺它时上面的用例层会给出<strong>可读的 400</strong> （「责任部门必填」），而 Spring
   * 的「缺必填参数」会落进兜底 handler 变成 500「服务器崩了」—— 把"你传错了"报成"我崩了"会把排查引到错误的方向（{@code GlobalExceptionHandler}
   * 的注释里记着同一条教训）。
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
      @org.springframework.web.bind.annotation.RequestParam(value = "orgId", required = false)
          String orgId,
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
            parseOrgId(orgId),
            classification,
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 表单里的责任部门 id。
   *
   * <p>★ 空/缺失 ⇒ 返回 null，交给用例层给"责任部门必填"的可读 400（而不是在控制器里另写一套必填文案 —— 两处文案就是两处真相）。★ 非法 uuid ⇒ 400
   * 且说清是哪个字段：这是"调用方能自己修"的一类。
   */
  private static UUID parseOrgId(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "责任部门不是合法的部门 id：" + raw, "请从组织树选一个部门，并把它的 id 传进 orgId 字段");
    }
  }

  record RegisterDocumentsBody(
      List<String> names,
      List<String> uploadIds,
      List<Map<String, Object>> segmentationPlan,
      String docType,
      UUID orgId,
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
