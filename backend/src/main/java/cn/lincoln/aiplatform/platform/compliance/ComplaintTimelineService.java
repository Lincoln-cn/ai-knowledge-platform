package cn.lincoln.aiplatform.platform.compliance;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 工单事件流与附件的读写（`#356`）。
 *
 * <h3>★ 它为什么单独存在，而不是塞进 {@link ComplaintService}</h3>
 *
 * <p>{@code ComplaintService} 管的是**状态机**（能不能从 A 流到 B、终态要不要填说明）； 这里管的是**留痕**（每一次动作各记一行， 外加截图/日志文件）。
 * 两件事的读者不同：状态机的读者是业务规则，留痕的读者是当事人与合规核查。混在一个类里， 改状态机的 if 分支时会顺手改到留痕 —— 这类"顺手"正是留痕最容易断的地方。
 *
 * <h3>★ 附件字节走 {@link FileStorePort}，不走数据库</h3>
 *
 * <p>与 {@code cv_file} 同一套约定：原始文件名只落库用于展示，<strong>绝不参与定位文件</strong> （否则这个端口就有了路径穿越的攻击面）。
 */
@Service
public class ComplaintTimelineService {

  /** 单个附件上限：10 MB。截图与日志够用，再大的一律走对象存储，不占工单链路。 */
  private static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

  private static final String EVENT_COLUMNS =
      """
      select id, ticket_id, at, actor_id, actor_name, side, kind, from_status, to_status, note
        from platform_complaint_event
      """;

  private static final String ATTACHMENT_COLUMNS =
      """
      select id, ticket_id, event_id, file_name, content_type, size_bytes, storage_key,
             uploaded_at, uploaded_by, uploaded_name, side
        from platform_complaint_attachment
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final FileStorePort files;

  public ComplaintTimelineService(NamedParameterJdbcTemplate jdbc, FileStorePort files) {
    this.jdbc = jdbc;
    this.files = files;
  }

  /**
   * 记一条事件，返回它的 id（附件要挂在新事件上时用得上）。
   *
   * <p>★ 调用方在同一个事务里：留痕失败必须让业务动作一起失败。 "状态改了但没留痕"比"状态没改"更难查 —— 后者用户会再点一次，前者没有任何痕迹提示你去查。
   */
  public UUID record(
      UUID ticketId,
      ComplaintEvent.Side side,
      ComplaintEvent.Kind kind,
      ComplaintStatus fromStatus,
      ComplaintStatus toStatus,
      String note,
      Instant at,
      UUID actorId,
      String actorName) {
    UUID id = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("id", id);
    params.put("ticketId", ticketId);
    params.put("at", Timestamp.from(at));
    params.put("actorId", actorId);
    params.put("actorName", actorName == null || actorName.isBlank() ? "系统" : actorName);
    params.put("side", side.name());
    params.put("kind", kind.name());
    params.put("fromStatus", fromStatus == null ? null : fromStatus.name());
    params.put("toStatus", toStatus == null ? null : toStatus.name());
    params.put("note", note);
    jdbc.update(
        """
        insert into platform_complaint_event
          (id, ticket_id, at, actor_id, actor_name, side, kind, from_status, to_status, note)
        values
          (:id, :ticketId, :at, :actorId, :actorName, :side, :kind, :fromStatus, :toStatus, :note)
        """,
        params);
    return id;
  }

  /** 整条时间线（时间正序，附件按所属事件挂回去）。 */
  public List<ComplaintEvent> timeline(UUID ticketId) {
    List<ComplaintEvent> events =
        jdbc.query(
            EVENT_COLUMNS + " where ticket_id = :ticketId order by at, id",
            Map.of("ticketId", ticketId),
            (rs, n) ->
                new ComplaintEvent(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("ticket_id"),
                    rs.getTimestamp("at").toInstant(),
                    (UUID) rs.getObject("actor_id"),
                    rs.getString("actor_name"),
                    ComplaintEvent.Side.valueOf(rs.getString("side")),
                    ComplaintEvent.Kind.valueOf(rs.getString("kind")),
                    rs.getString("from_status") == null
                        ? null
                        : ComplaintStatus.valueOf(rs.getString("from_status")),
                    rs.getString("to_status") == null
                        ? null
                        : ComplaintStatus.valueOf(rs.getString("to_status")),
                    rs.getString("note"),
                    new ArrayList<>()));
    if (events.isEmpty()) {
      return events;
    }

    Map<UUID, List<ComplaintEvent.Attachment>> byEvent = new LinkedHashMap<>();
    List<ComplaintEvent.Attachment> orphans = new ArrayList<>();
    for (ComplaintEvent.Attachment attachment : attachments(ticketId)) {
      if (attachment.eventId() == null) {
        orphans.add(attachment);
      } else {
        byEvent.computeIfAbsent(attachment.eventId(), key -> new ArrayList<>()).add(attachment);
      }
    }

    List<ComplaintEvent> merged = new ArrayList<>(events.size());
    for (ComplaintEvent event : events) {
      merged.add(
          new ComplaintEvent(
              event.id(),
              event.ticketId(),
              event.at(),
              event.actorId(),
              event.actorName(),
              event.side(),
              event.kind(),
              event.fromStatus(),
              event.toStatus(),
              event.note(),
              byEvent.getOrDefault(event.id(), List.of())));
    }
    // ★ 直接挂在工单上的附件（提交时一次性带上、还没对应事件）挂到第一条上，而不是悄悄丢掉：
    //   丢掉的表现是"我明明上传了截图，时间线里找不到"，而数据库里有 —— 最难查的那种不一致。
    if (!orphans.isEmpty() && !merged.isEmpty()) {
      ComplaintEvent first = merged.get(0);
      List<ComplaintEvent.Attachment> combined = new ArrayList<>(first.attachments());
      combined.addAll(orphans);
      merged.set(
          0,
          new ComplaintEvent(
              first.id(),
              first.ticketId(),
              first.at(),
              first.actorId(),
              first.actorName(),
              first.side(),
              first.kind(),
              first.fromStatus(),
              first.toStatus(),
              first.note(),
              combined));
    }
    return merged;
  }

  /**
   * 上传一个附件。
   *
   * @param eventId 挂到哪条事件上；null = 直接挂在工单上
   * @throws BusinessException 空文件、超上限
   */
  public ComplaintEvent.Attachment attach(
      UUID ticketId,
      UUID eventId,
      String fileName,
      String contentType,
      byte[] content,
      Instant at,
      UUID actorId,
      String actorName,
      ComplaintEvent.Side side) {
    if (content == null || content.length == 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "附件是空的：" + fileName);
    }
    if (content.length > MAX_ATTACHMENT_BYTES) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "附件「" + fileName + "」超过 10 MB —— 日志文件请先截取相关片段，或改用对象存储");
    }
    String key = files.store(fileName, content);
    UUID id = UUID.randomUUID();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("id", id);
    params.put("ticketId", ticketId);
    params.put("eventId", eventId);
    params.put("fileName", fileName == null || fileName.isBlank() ? "未命名附件" : fileName);
    params.put(
        "contentType",
        contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
    params.put("size", (long) content.length);
    params.put("storageKey", key);
    params.put("at", Timestamp.from(at));
    params.put("uploadedBy", actorId);
    params.put("uploadedName", actorName == null ? "" : actorName);
    params.put("side", side == ComplaintEvent.Side.HANDLER ? "HANDLER" : "REQUESTER");
    jdbc.update(
        """
        insert into platform_complaint_attachment
          (id, ticket_id, event_id, file_name, content_type, size_bytes, storage_key,
           uploaded_at, uploaded_by, uploaded_name, side)
        values
          (:id, :ticketId, :eventId, :fileName, :contentType, :size, :storageKey,
           :at, :uploadedBy, :uploadedName, :side)
        """,
        params);
    return new ComplaintEvent.Attachment(
        id,
        eventId,
        (String) params.get("fileName"),
        (String) params.get("contentType"),
        content.length,
        at,
        actorId,
        (String) params.get("uploadedName"),
        side);
  }

  /**
   * 取一个附件的元数据与字节（下载用）。
   *
   * <p>★ 归属校验由调用方先做：这里是"取文件"，不是"判权限"。 把权限判断藏进存取层，第二次调用就会忘掉它。
   */
  public Stored open(UUID attachmentId) {
    List<Stored> found =
        jdbc.query(
            ATTACHMENT_COLUMNS + " where id = :id",
            Map.of("id", attachmentId),
            (rs, n) ->
                new Stored(
                    (UUID) rs.getObject("ticket_id"),
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getLong("size_bytes"),
                    rs.getString("storage_key")));
    Stored stored =
        found.stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "附件不存在：" + attachmentId));
    // 字节读不到要抛（FileStorePort 的约定）：返回空数组会让"文件丢了"与"文件是空的"看起来一样
    return stored;
  }

  /** 读一个附件的字节。 */
  public byte[] bytes(Stored stored) {
    return files.read(stored.storageKey());
  }

  private List<ComplaintEvent.Attachment> attachments(UUID ticketId) {
    return jdbc.query(
        ATTACHMENT_COLUMNS + " where ticket_id = :ticketId order by uploaded_at, id",
        Map.of("ticketId", ticketId),
        (rs, n) ->
            new ComplaintEvent.Attachment(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("event_id"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getTimestamp("uploaded_at").toInstant(),
                (UUID) rs.getObject("uploaded_by"),
                rs.getString("uploaded_name"),
                ComplaintEvent.Side.valueOf(rs.getString("side"))));
  }

  /** 下载用的载体：元数据 + 存储键（键不外传给前端）。 */
  public record Stored(
      UUID ticketId, String fileName, String contentType, long sizeBytes, String storageKey) {}
}
