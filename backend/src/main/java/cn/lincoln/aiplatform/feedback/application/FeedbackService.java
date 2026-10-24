package cn.lincoln.aiplatform.feedback.application;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 反馈管理读/写用例（契约 {@code /feedbacks} 六个端点，Issue {@code #194}）。
 *
 * <p>★ 数据口径：赞踩本身在 {@code cv_message.feedback}（V35），处理状态在 {@code
 * cv_feedback_state}（V37）。本类不复制问题/答案：问题取同会话中该助手消息之前最近一条 USER
 * 消息，答案取该助手消息正文。这样历史赞踩不需要回填，且“改一条消息正文”不会出现两份不一致的副本。
 *
 * <p>★ 状态机：{@code PENDING → PROCESSING → RESOLVED / IGNORED}；后两者可通过 reopen 回到
 * PROCESSING。本类只校验目标状态取值与 RESOLVED 必须有处理说明，不替运营决定合法迁移顺序 —— 页面已经按状态给了按钮，后端再拦一次“只能从 PENDING 到
 * PROCESSING”只会让批量办理更脆。
 */
@Service
public class FeedbackService {

  private static final Set<String> STATUSES =
      Set.of("PENDING", "PROCESSING", "RESOLVED", "IGNORED");

  private static final String BASE_SELECT =
      """
      select m.id,
             c.assistant_id as app_id,
             coalesce(a.name, '通用问答') as app_name,
             m.conversation_id,
             nullif(c.title, '') as conversation_title,
             m.feedback as type,
             coalesce((select pm.content
                         from cv_message pm
                        where pm.conversation_id = m.conversation_id
                          and pm.role = 'USER'
                          and pm.created_at < m.created_at
                        order by pm.created_at desc, pm.id desc
                        limit 1), '') as question,
             m.content as answer,
             '' as comment,
             coalesce(s.status, 'PENDING') as status,
             s.assignee,
             coalesce(s.resolution, '') as resolution,
             s.promoted_to_kb_id,
             kb.name as promoted_to_kb_name,
             m.created_at,
             s.handled_at,
             c.user_id as requester_id
        from cv_message m
        join cv_conversation c on c.id = m.conversation_id
        left join as_assistant a on a.id = c.assistant_id
        left join cv_feedback_state s on s.message_id = m.id
        left join kb_knowledge_base kb on kb.id = s.promoted_to_kb_id
       where m.feedback is not null
         and c.deleted_at is null
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final QaPairService qaPairs;

  public FeedbackService(NamedParameterJdbcTemplate jdbc, QaPairService qaPairs) {
    this.jdbc = jdbc;
    this.qaPairs = qaPairs;
  }

  /** 列表：分页 + status/appId/mine/keyword 过滤；只返回有赞踩的消息。 */
  public FeedbackPage list(
      String status, UUID appId, boolean mine, UUID userId, String keyword, int page, int size) {
    int safePage = Math.max(1, page);
    int safeSize = Math.min(100, Math.max(1, size));
    MapSqlParameterSource params = new MapSqlParameterSource();
    StringBuilder where = new StringBuilder();
    if (status != null && !status.isBlank()) {
      String normalized = normalizeStatus(status);
      where.append(" and coalesce(s.status, 'PENDING') = :status");
      params.addValue("status", normalized);
    }
    if (appId != null) {
      where.append(" and c.assistant_id = :appId");
      params.addValue("appId", appId);
    }
    if (mine) {
      where.append(" and c.user_id = :userId");
      params.addValue("userId", userId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          """
           and (m.content ilike :keyword
                or c.title ilike :keyword
                or exists (select 1 from cv_message q
                            where q.conversation_id = m.conversation_id
                              and q.role = 'USER'
                              and q.created_at < m.created_at
                              and q.content ilike :keyword))
          """);
      params.addValue("keyword", "%" + keyword.trim() + "%");
    }

    String base = BASE_SELECT + where;
    Long total = jdbc.queryForObject("select count(*) from (" + base + ") t", params, Long.class);
    params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
    List<FeedbackView> rows =
        jdbc.query(
            base + " order by m.created_at desc, m.id desc limit :limit offset :offset",
            params,
            (rs, rowNum) -> map(rs));
    return new FeedbackPage(rows, total == null ? 0L : total, safePage, safeSize);
  }

  /** 统计：四个状态都给数（缺的补 0），避免前端把 undefined 画成 0。 */
  public Map<String, Long> stats() {
    Map<String, Long> result = new LinkedHashMap<>();
    for (String s : List.of("PENDING", "PROCESSING", "RESOLVED", "IGNORED")) {
      result.put(s, 0L);
    }
    jdbc.query(
        """
        select coalesce(s.status, 'PENDING') as status, count(*) as n
          from cv_message m
          join cv_conversation c on c.id = m.conversation_id
          left join cv_feedback_state s on s.message_id = m.id
         where m.feedback is not null and c.deleted_at is null
         group by coalesce(s.status, 'PENDING')
        """,
        Map.of(),
        rs -> {
          result.put(rs.getString("status"), rs.getLong("n"));
        });
    return result;
  }

  /** 单条详情；id 是消息 id（见类注释）。 */
  public FeedbackView get(UUID id) {
    List<FeedbackView> rows =
        jdbc.query(BASE_SELECT + " and m.id = :id", Map.of("id", id), (rs, n) -> map(rs));
    if (rows.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "反馈不存在：" + id);
    }
    return rows.get(0);
  }

  /**
   * 单条办理。
   *
   * <p>★ 批量入口也逐条调用本方法：这样每条在自己的事务里，失败一条不会回滚已办成的；回执逐条给原因（契约明确要求）。
   */
  @Transactional
  public FeedbackView handle(
      UUID id, String status, String assignee, String resolution, UUID promoteToKbId) {
    String normalized = normalizeStatus(status);
    ensureFeedback(id);
    String safeResolution = resolution == null ? "" : resolution.trim();
    if ("RESOLVED".equals(normalized) && safeResolution.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "处理为「已处理」时必须填写处理说明");
    }
    if (promoteToKbId != null && !knowledgeBaseExists(promoteToKbId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库不存在：" + promoteToKbId);
    }
    String safeAssignee = assignee == null || assignee.isBlank() ? null : assignee.trim();
    jdbc.update(
        """
        insert into cv_feedback_state
          (message_id, status, assignee, resolution, promoted_to_kb_id, handled_at, updated_at)
        values
          (:id, :status, :assignee, :resolution, :kb,
           case when :status in ('RESOLVED', 'IGNORED') then now() else null end, now())
        on conflict (message_id) do update
           set status = excluded.status,
               assignee = excluded.assignee,
               resolution = excluded.resolution,
               promoted_to_kb_id = excluded.promoted_to_kb_id,
               handled_at = excluded.handled_at,
               updated_at = now()
        """,
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("status", normalized)
            .addValue("assignee", safeAssignee)
            .addValue("resolution", safeResolution)
            .addValue("kb", promoteToKbId));
    FeedbackView saved = get(id);
    if (promoteToKbId != null) {
      promoteToQaPair(promoteToKbId, id, saved);
    }
    return saved;
  }

  /**
   * 把一条反馈真正写进知识库问答对（{@code #208}）。
   *
   * <p>★ 用已有的 {@link QaPairService#importPairs} 而不是复制一条 INSERT：它已经带库可见性、密级 ceiling、出处归属三条校验，也把
   * origin 固定成 {@link QaPairOrigin#FEEDBACK}。本方法只补两件它不知道的事：从会话取密级、用 {@code feedback:<id>} 做幂等键。
   */
  private void promoteToQaPair(UUID kbId, UUID feedbackId, FeedbackView feedback) {
    String ref = "feedback:" + feedbackId;
    Integer existing =
        jdbc.queryForObject(
            """
            select count(*) from kb_qa_pair
             where kb_id = :kb and source_ref = :ref and deleted_at is null
            """,
            Map.of("kb", kbId, "ref", ref),
            Integer.class);
    if (existing != null && existing > 0) {
      return;
    }
    String level =
        jdbc.queryForObject(
            "select max_classification from cv_conversation where id = :id",
            Map.of("id", feedback.conversationId()),
            String.class);
    qaPairs.importPairs(
        kbId,
        List.of(
            new QaPairService.ImportPair(
                feedback.question(),
                feedback.answer(),
                null,
                ClassificationLevel.valueOf(level),
                QaPairOrigin.FEEDBACK,
                ref)),
        null);
  }

  /** 重新打开：RESOLVED/IGNORED → PROCESSING，清空处理时间。 */
  @Transactional
  public FeedbackView reopen(UUID id) {
    ensureFeedback(id);
    jdbc.update(
        """
        insert into cv_feedback_state (message_id, status, handled_at, updated_at)
        values (:id, 'PROCESSING', null, now())
        on conflict (message_id) do update
           set status = 'PROCESSING', handled_at = null, updated_at = now()
        """,
        Map.of("id", id));
    return get(id);
  }

  /** 批量入口把 assigneeId 解析成显示名；用户不存在时给可读 400。 */
  public String assigneeName(UUID assigneeId) {
    List<String> names =
        jdbc.query(
            "select display_name from iam_user where id = :id and deleted_at is null",
            Map.of("id", assigneeId),
            (rs, n) -> rs.getString(1));
    if (names.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "处理人不存在：" + assigneeId);
    }
    return names.get(0);
  }

  private void ensureFeedback(UUID id) {
    Long n =
        jdbc.queryForObject(
            """
            select count(*) from cv_message m
              join cv_conversation c on c.id = m.conversation_id
             where m.id = :id and m.feedback is not null and c.deleted_at is null
            """,
            Map.of("id", id),
            Long.class);
    if (n == null || n == 0) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "反馈不存在：" + id);
    }
  }

  private boolean knowledgeBaseExists(UUID id) {
    Long n =
        jdbc.queryForObject(
            "select count(*) from kb_knowledge_base where id = :id", Map.of("id", id), Long.class);
    return n != null && n > 0;
  }

  private static String normalizeStatus(String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(normalized)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "反馈状态非法：" + status + "（合法值：PENDING/PROCESSING/RESOLVED/IGNORED）");
    }
    return normalized;
  }

  private static FeedbackView map(ResultSet rs) throws SQLException {
    return new FeedbackView(
        rs.getObject("id", UUID.class),
        rs.getObject("app_id", UUID.class),
        rs.getString("app_name"),
        rs.getObject("conversation_id", UUID.class),
        rs.getString("conversation_title"),
        rs.getString("type"),
        rs.getString("question"),
        rs.getString("answer"),
        rs.getString("comment"),
        rs.getString("status"),
        rs.getString("assignee"),
        rs.getString("resolution"),
        rs.getObject("promoted_to_kb_id", UUID.class),
        rs.getString("promoted_to_kb_name"),
        toInstant(rs, "created_at"),
        toInstant(rs, "handled_at"),
        rs.getObject("requester_id", UUID.class));
  }

  private static Instant toInstant(ResultSet rs, String column) throws SQLException {
    var ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  public record FeedbackView(
      UUID id,
      UUID appId,
      String appName,
      UUID conversationId,
      String conversationTitle,
      String type,
      String question,
      String answer,
      String comment,
      String status,
      String assignee,
      String resolution,
      UUID promotedToKbId,
      String promotedToKbName,
      Instant createdAt,
      Instant handledAt,
      UUID requesterId) {}

  public record FeedbackPage(List<FeedbackView> list, long total, int page, int size) {}

  public record BatchResult(List<FeedbackView> accepted, List<Rejected> rejected) {}

  public record Rejected(UUID id, String reason) {}
}
