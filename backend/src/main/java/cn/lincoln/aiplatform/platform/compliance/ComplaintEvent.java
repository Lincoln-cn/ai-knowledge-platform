package cn.lincoln.aiplatform.platform.compliance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 工单事件流的一个条目（`#356`）。
 *
 * <p>★ 它是**追加写**的：{@code platform_complaint_ticket} 只存终态，这里存过程。 现状的病灶是「办理」直接覆盖 {@code handler_note}
 * —— 一单办三次就只剩最后一句话，当事人看到的是"整个过程看不见"。 事件流让每一次状态流转、每一句补充说明都各占一行。
 *
 * @param side 哪一侧做的（{@code REQUESTER} 投诉人 / {@code HANDLER} 处理人 / {@code SYSTEM} 系统自动）。 ★ 同一个"操作人"
 *     字段里混两种口吻，读者会误以为补充说明也是官方的
 * @param kind {@code CREATED} 提交 / {@code NOTE} 补充说明或回复 / {@code STATUS_CHANGED} 状态流转 / {@code
 *     ATTACHMENT} 只挂了个附件
 * @param fromStatus 流转前状态；{@code CREATED} 与 {@code NOTE} 为 null
 * @param toStatus 流转后状态；同上
 * @param attachments 这一条下面挂的附件（截图为空是正常的：文字补充说明不带文件）
 */
public record ComplaintEvent(
    UUID id,
    UUID ticketId,
    Instant at,
    UUID actorId,
    String actorName,
    Side side,
    Kind kind,
    ComplaintStatus fromStatus,
    ComplaintStatus toStatus,
    String note,
    List<Attachment> attachments) {

  /** 哪一侧做的。 */
  public enum Side {
    REQUESTER,
    HANDLER,
    SYSTEM
  }

  /** 事件种类。 */
  public enum Kind {
    CREATED,
    NOTE,
    STATUS_CHANGED,
    ATTACHMENT
  }

  /**
   * 附件元数据（不含字节）。
   *
   * <p>★ 字节在 {@code FileStorePort}，这里只存键与元数据 —— 与 {@code cv_file} 同一套约定。
   */
  public record Attachment(
      UUID id,
      UUID eventId,
      String fileName,
      String contentType,
      long sizeBytes,
      Instant uploadedAt,
      UUID uploadedBy,
      String uploadedName,
      Side side) {}
}
