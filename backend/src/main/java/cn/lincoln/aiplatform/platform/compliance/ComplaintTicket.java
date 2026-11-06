package cn.lincoln.aiplatform.platform.compliance;

import java.time.Instant;
import java.util.UUID;

/**
 * 投诉举报工单视图（{@code #83} 第十五条）。
 *
 * <p>字段口径：
 *
 * <ul>
 *   <li>{@code ticketNo}：人类可读工单号（{@code CMP-日期-随机段}），公示与沟通里引用它， 不引用主键。
 *   <li>{@code dueAt}：应反馈时间 = 提交时刻 + 合规设置里的 {@code complaintFeedbackDays}
 *       （<strong>运营口径，非法条数字</strong>，见 {@code
 *       V83__platform_compliance_baseline.sql}）。超期只在界面提示，不自动告警（告警是 {@code #73} 的事，本件不做）。
 *   <li>{@code subject / content / kind / contact}：提交后不可改（举证完整性），所以视图是全量只读快照。
 * </ul>
 */
public record ComplaintTicket(
    UUID id,
    String ticketNo,
    ComplaintKind kind,
    String subject,
    String content,
    String contact,
    ComplaintStatus status,
    UUID handlerId,
    String handlerName,
    String handlerNote,
    Instant handledAt,
    Instant dueAt,
    UUID requesterId,
    String requesterName,
    Instant createdAt,
    Instant updatedAt) {}
