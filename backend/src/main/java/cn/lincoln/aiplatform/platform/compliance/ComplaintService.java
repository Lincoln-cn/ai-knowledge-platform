package cn.lincoln.aiplatform.platform.compliance;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 投诉举报工单服务（{@code #83} 第十五条）。
 *
 * <h3>★ 与 feedback（{@code #194}）不是一回事，不许合并</h3>
 *
 * <p>{@code /feedbacks} 管的是**内容质量反馈**（赞踩 → 处理 → 沉淀问答对），数据源是会话消息； 本件管的是**合规投诉举报**（第十五条：便捷入口 +
 * 公布处理流程与反馈时限 + 落库工单）， 数据源是用户主动提交的工单。两者的状态机、留痕要求、公示义务都不同：
 *
 * <ul>
 *   <li>投诉举报是**留痕举证**记录：提交后正文不可改、终态不重开（翻案 = 再提一单）。
 *   <li>投诉举报有**反馈时限**（{@code due_at}，第十五条明文要求公布）；feedback 没有。
 * </ul>
 *
 * <h3>★ 数据范围（越权防线在服务端，不在前端隐藏按钮）</h3>
 *
 * <p>没有 {@code complaint:handle} 权限的用户，列表与详情**只能看到自己提交的工单**； 传了别人的 id 一律 404（不是 403 —— 不暴露「这张单存在」）。
 */
@Service
public class ComplaintService {

  private static final String BASE_SELECT =
      """
      select id, ticket_no, kind, subject, content, contact, status,
             handler_id, handler_name, handler_note, handled_at, due_at,
             requester_id, requester_name, created_at, updated_at
        from platform_complaint_ticket
      """;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final DateTimeFormatter TICKET_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final String TICKET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private final NamedParameterJdbcTemplate jdbc;
  private final ComplianceSettingsService settings;

  public ComplaintService(NamedParameterJdbcTemplate jdbc, ComplianceSettingsService settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  /**
   * 提交一张投诉举报工单（便捷入口的落点）。
   *
   * <p>提交即受理承诺的起点：{@code dueAt} = 提交时刻 + 合规设置的反馈时限（天）。
   */
  public ComplaintTicket create(
      ComplaintKind kind,
      String subject,
      String content,
      String contact,
      UUID requesterId,
      String requesterName) {
    if (subject == null || subject.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "投诉举报必须填写标题");
    }
    if (content == null || content.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "投诉举报必须填写具体内容");
    }
    Instant now = Instant.now();
    Instant dueAt = now.plusSeconds(settings.current().complaintFeedbackDays() * 86400L);
    UUID id = UUID.randomUUID();

    // 工单号唯一索引兜底：随机段撞号时换一段重试（32 位字符空间，撞 3 次是运维级事故）
    String ticketNo = null;
    for (int attempt = 0; attempt < 3 && ticketNo == null; attempt++) {
      String candidate = newTicketNo();
      try {
        jdbc.update(
            """
            insert into platform_complaint_ticket
              (id, ticket_no, kind, subject, content, contact, status,
               due_at, requester_id, requester_name, created_at, updated_at)
            values
              (:id, :ticketNo, :kind, :subject, :content, :contact, 'SUBMITTED',
               :dueAt, :requesterId, :requesterName, :now, :now)
            """,
            Map.of(
                "id",
                id,
                "ticketNo",
                candidate,
                "kind",
                kind.name(),
                "subject",
                subject,
                "content",
                content,
                "contact",
                contact == null ? "" : contact,
                "dueAt",
                Timestamp.from(dueAt),
                "requesterId",
                requesterId,
                "requesterName",
                requesterName == null ? "" : requesterName,
                "now",
                Timestamp.from(now)));
        ticketNo = candidate;
      } catch (DuplicateKeyException collision) {
        if (attempt == 2) {
          throw new BusinessException(ErrorCode.BAD_REQUEST, "工单号生成连续撞号，请稍后重试");
        }
      }
    }
    return get(id, requesterId, true);
  }

  /**
   * 工单列表（分页，待处理优先、新提交在前）。
   *
   * @param canHandleAll 有 {@code complaint:handle} 权限 = 看全部；否则只能看自己提交的
   */
  public PageResult<ComplaintTicket> list(
      ComplaintStatus status,
      ComplaintKind kind,
      String keyword,
      UUID requesterId,
      boolean canHandleAll,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where 1 = 1");
    Map<String, Object> params = new LinkedHashMap<>();
    if (!canHandleAll) {
      where.append(" and requester_id = :requesterId");
      params.put("requesterId", requesterId);
    }
    if (status != null) {
      where.append(" and status = :status");
      params.put("status", status.name());
    }
    if (kind != null) {
      where.append(" and kind = :kind");
      params.put("kind", kind.name());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (subject ilike :kw or content ilike :kw)");
      params.put("kw", "%" + keyword.trim() + "%");
    }

    long total =
        jdbc.queryForObject(
            "select count(*) from platform_complaint_ticket" + where, params, Long.class);
    params.put("limit", size);
    params.put("offset", Math.max(0, page - 1) * (long) size);
    List<ComplaintTicket> list =
        jdbc.query(
            BASE_SELECT
                + where
                // ★ 空白要显式：文本块首换行不计入内容，接在 :requesterId 后会拼出 :requesterIdorder（真踩过）。
                + " order by case status when 'SUBMITTED' then 0 when 'PROCESSING' then 1"
                + " when 'RESOLVED' then 2 else 3 end, created_at desc"
                + " limit :limit offset :offset",
            params,
            (rs, n) -> map(rs));
    return PageResult.of(list, total, page, size);
  }

  /** 工单详情。★ 越权读取一律 404（不暴露单据存在性）。 */
  public ComplaintTicket get(UUID id, UUID requesterId, boolean canHandleAll) {
    ComplaintTicket ticket =
        jdbc.query(BASE_SELECT + " where id = :id", Map.of("id", id), (rs, n) -> map(rs)).stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "工单不存在：" + id));
    if (!canHandleAll && !ticket.requesterId().equals(requesterId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在：" + id);
    }
    return ticket;
  }

  /**
   * 办理工单（状态流转 + 处理说明留痕）。需要 {@code complaint:handle}。
   *
   * <p>规则（{@link ComplaintStatus} 的 javadoc 三条）：只往前流转；{@code RESOLVED} / {@code REJECTED}
   * 必须写处理说明；正文不可改。
   */
  public ComplaintTicket patch(
      UUID id, ComplaintStatus target, String handlerNote, UUID handlerId, String handlerName) {
    ComplaintTicket current = get(id, handlerId, true);
    if (!current.status().canTransitionTo(target)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + statusLabel(current.status()) + "」不能流转到「" + statusLabel(target) + "」");
    }
    boolean terminal = target == ComplaintStatus.RESOLVED || target == ComplaintStatus.REJECTED;
    if (terminal && (handlerNote == null || handlerNote.isBlank())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          target == ComplaintStatus.RESOLVED ? "处理为「已办结」时必须填写办结结果（反馈内容）" : "处理为「不予受理」时必须填写不予受理理由");
    }
    Instant now = Instant.now();
    jdbc.update(
        """
        update platform_complaint_ticket
           set status = :status, handler_id = :handlerId, handler_name = :handlerName,
               handler_note = :handlerNote, handled_at = :handledAt, updated_at = :now
         where id = :id
        """,
        Map.of(
            "id",
            id,
            "status",
            target.name(),
            "handlerId",
            handlerId,
            "handlerName",
            handlerName == null ? "" : handlerName,
            "handlerNote",
            handlerNote == null ? "" : handlerNote,
            "handledAt",
            Timestamp.from(now),
            "now",
            Timestamp.from(now)));
    return get(id, handlerId, true);
  }

  private static String newTicketNo() {
    StringBuilder suffix = new StringBuilder(4);
    for (int i = 0; i < 4; i++) {
      suffix.append(TICKET_ALPHABET.charAt(RANDOM.nextInt(TICKET_ALPHABET.length())));
    }
    return "CMP-" + LocalDate.now().format(TICKET_DATE) + "-" + suffix;
  }

  private static String statusLabel(ComplaintStatus status) {
    return switch (status) {
      case SUBMITTED -> "已提交";
      case PROCESSING -> "处理中";
      case RESOLVED -> "已办结";
      case REJECTED -> "不予受理";
    };
  }

  private static ComplaintTicket map(ResultSet rs) throws SQLException {
    Timestamp handledAt = rs.getTimestamp("handled_at");
    UUID handlerId = (UUID) rs.getObject("handler_id");
    return new ComplaintTicket(
        (UUID) rs.getObject("id"),
        rs.getString("ticket_no"),
        ComplaintKind.valueOf(rs.getString("kind")),
        rs.getString("subject"),
        rs.getString("content"),
        rs.getString("contact"),
        ComplaintStatus.valueOf(rs.getString("status")),
        handlerId,
        rs.getString("handler_name"),
        rs.getString("handler_note"),
        handledAt == null ? null : handledAt.toInstant(),
        rs.getTimestamp("due_at").toInstant(),
        (UUID) rs.getObject("requester_id"),
        rs.getString("requester_name"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
