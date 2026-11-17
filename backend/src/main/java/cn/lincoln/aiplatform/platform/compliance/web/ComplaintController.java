package cn.lincoln.aiplatform.platform.compliance.web;

import cn.lincoln.aiplatform.platform.compliance.ComplaintEvent;
import cn.lincoln.aiplatform.platform.compliance.ComplaintKind;
import cn.lincoln.aiplatform.platform.compliance.ComplaintService;
import cn.lincoln.aiplatform.platform.compliance.ComplaintStatus;
import cn.lincoln.aiplatform.platform.compliance.ComplaintTicket;
import cn.lincoln.aiplatform.platform.compliance.ComplaintTimelineService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * 投诉举报入口（{@code #83} 第十五条：「便捷入口 + 公布处理流程与反馈时限」的后端半边）。
 *
 * <h3>★ 读与写的权限分开</h3>
 *
 * <ul>
 *   <li>提交（POST）与查看自己的工单：<strong>登录即可</strong> —— 入口要便捷，不能让投诉先去申请权限。
 *   <li>看全部工单与办理：{@code complaint:handle}。★ 越权防线在 {@link ComplaintService}（按 提交者裁剪 + 越权
 *       404），这里只是把"能不能办"卡在动作上，两层都要有。
 *   <li>★ 名字留痕存**登录名**（令牌里没有 displayName claim，见 {@code JwtTokenService}）：
 *       工单是举证记录，登录名足够定位到人；展示层将来要显示名再解析，不为此加一次查询。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/complaints")
public class ComplaintController {

  private final ComplaintService complaints;

  public ComplaintController(ComplaintService complaints) {
    this.complaints = complaints;
  }

  /** 提交投诉举报（便捷入口）。提交即起算反馈时限（{@code dueAt}）。 */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<ComplaintTicket> create(
      @RequestBody CreateBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        complaints.create(
            body.kind() == null ? ComplaintKind.COMPLAINT : body.kind(),
            body.subject(),
            body.content(),
            body.contact(),
            UUID.fromString(jwt.getSubject()),
            jwt.getClaimAsString("username")));
  }

  /**
   * 工单列表：有 {@code complaint:handle} 看全部，否则只看自己提交的。
   *
   * <p>{@code mine=true} 时**只**返回自己提交的，哪怕有办理权限 —— 「我的投诉」与「投诉待办」是同一接口的两种视角 （{@code #356}）。
   */
  @GetMapping
  public ApiResponse<PageResult<ComplaintTicket>> list(
      @RequestParam(value = "status", required = false) ComplaintStatus status,
      @RequestParam(value = "kind", required = false) ComplaintKind kind,
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "mine", required = false, defaultValue = "false") boolean mine,
      @RequestParam(value = "page", required = false, defaultValue = "1") int page,
      @RequestParam(value = "size", required = false, defaultValue = "20") int size,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        complaints.list(
            status,
            kind,
            keyword,
            UUID.fromString(jwt.getSubject()),
            canHandleAll(jwt),
            mine,
            page,
            size));
  }

  /** 工单详情（越权读取 404，不暴露单据存在性）。 */
  @GetMapping("/{id}")
  public ApiResponse<ComplaintTicket> get(
      @PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(complaints.get(id, UUID.fromString(jwt.getSubject()), canHandleAll(jwt)));
  }

  /** 办理工单：状态流转 + 处理说明留痕（办结/不予受理必填）。 */
  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('complaint:handle')")
  public ApiResponse<ComplaintTicket> patch(
      @PathVariable("id") UUID id, @RequestBody PatchBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        complaints.patch(
            id,
            body.status(),
            body.handlerNote(),
            UUID.fromString(jwt.getSubject()),
            jwt.getClaimAsString("username")));
  }

  /**
   * 整条时间线（`#356`）：谁在什么时候从哪个状态改到哪个状态、说了什么、附了什么文件。
   *
   * <p>★ 读它的人和读详情的人一样：当事人看自己的单，处理人看全部。归属校验在 {@link ComplaintService#timeline}。
   */
  @GetMapping("/{id}/events")
  public ApiResponse<List<ComplaintEvent>> events(
      @PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        complaints.timeline(id, UUID.fromString(jwt.getSubject()), canHandleAll(jwt)));
  }

  /**
   * 在工单上补一条说明。
   *
   * <p>★ 投诉人与处理人都能写，但**不会因此改状态** —— "我再补一句"与"我把这单办结了"是两件事， 混成一个动作会让"谁推进了流程"变成谜。
   */
  @PostMapping("/{id}/notes")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<ComplaintTicket> note(
      @PathVariable("id") UUID id, @RequestBody NoteBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        complaints.addNote(
            id,
            body.note(),
            UUID.fromString(jwt.getSubject()),
            jwt.getClaimAsString("username"),
            canHandleAll(jwt)));
  }

  /**
   * 上传附件（截图、日志文件）。
   *
   * <p>★ 投诉人也能传：`#356` 的原话是「提交和回复要允许截图、日志文件等附加内容」—— 只给处理人开上传口， 等于把"举证"这件事留在了电话里。
   */
  @PostMapping("/{id}/attachments")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<ComplaintEvent.Attachment> attach(
      @PathVariable("id") UUID id,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "eventId", required = false) UUID eventId,
      @AuthenticationPrincipal Jwt jwt) {
    UUID actorId = UUID.fromString(jwt.getSubject());
    boolean handler = canHandleAll(jwt);
    // 先做归属校验：传了别人的工单 id 一律 404，与读详情同一个出口
    complaints.get(id, actorId, handler);
    try {
      return ApiResponse.ok(
          complaints.attach(
              id,
              eventId,
              file.getOriginalFilename(),
              file.getContentType(),
              file.getBytes(),
              actorId,
              jwt.getClaimAsString("username"),
              handler ? ComplaintEvent.Side.HANDLER : ComplaintEvent.Side.REQUESTER));
    } catch (IOException error) {
      // 读 multipart 字节失败 = 这次上传真的没成，不能返回 200 让用户以为传上了
      throw new BusinessException(ErrorCode.BAD_REQUEST, "读取上传内容失败：" + error.getMessage());
    }
  }

  /** 下载附件（归属校验与工单一致）。 */
  @GetMapping("/attachments/{attachmentId}")
  public ResponseEntity<byte[]> download(
      @PathVariable("attachmentId") UUID attachmentId, @AuthenticationPrincipal Jwt jwt) {
    ComplaintTimelineService.Stored stored = complaints.attachment(attachmentId);
    complaints.get(stored.ticketId(), UUID.fromString(jwt.getSubject()), canHandleAll(jwt));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(stored.contentType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(stored.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(complaints.attachmentBytes(stored));
  }

  /** 权限点判定：令牌的 {@code authorities} claim 里有没有 {@code complaint:handle}。 */
  private static boolean canHandleAll(Jwt jwt) {
    List<String> authorities = jwt.getClaimAsStringList("authorities");
    return authorities != null && authorities.contains("complaint:handle");
  }

  /** 提交请求体。★ {@code kind} 缺省 {@code COMPLAINT}（大多数入口是"投诉"，举报走同一入口选类别）。 */
  public record CreateBody(ComplaintKind kind, String subject, String content, String contact) {}

  /** 办理请求体：目标状态 + 处理说明（办结结果 / 不予受理理由）。 */
  public record PatchBody(ComplaintStatus status, String handlerNote) {}

  /** 补充说明的请求体。 */
  public record NoteBody(String note) {}
}
