package cn.lincoln.aiplatform.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营分析（Issue #193）。
 *
 * <p>数据全部来自已有表：{@code cv_conversation} / {@code cv_message} / {@code as_assistant} / {@code
 * iam_user}。 <strong>不新建统计表、不引入异步汇总</strong> —— 本批要做的是“页面不再整页 404”，不是“实时数仓”。
 *
 * <h3>★ 哪些读数今天可以真算，哪些只能留空</h3>
 *
 * <ul>
 *   <li><b>能真算</b>：提问数、活跃用户、新增用户、token 总量、满意度、趋势、应用排行、引用数、赞踩数。
 *   <li><b>今天没有数据源，返回 null</b>：首字延迟 P50（{@code cv_message} 只存总耗时 {@code duration_ms}，没有首 token
 *       时间戳）、结构化输出失败率（没有 artifact 结果列）、待处理反馈数 （反馈处理闭环是 {@code #194} 的域，尚未落表）。
 * </ul>
 *
 * <p>★ 为什么不用 {@code duration_ms} 冒充首字延迟：两者在生产上不是同一个量，拿总耗时当首字延迟会让
 * “用户感知”这条指标永久失真，而且看不出失真。宁可界面显示“—”，也不报一个假数。
 *
 * <p>★ 所有区间都是 {@code [from 00:00, to+1 00:00)}，按服务端时区解释（与前端 {@code dayjs} 的本地日期一致）。
 */
@Service
public class AnalyticsService {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final int MAX_RANGE_DAYS = 366;

  private final JdbcTemplate jdbc;

  public AnalyticsService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 总览（全平台口径）。 */
  @Transactional(readOnly = true)
  public Overview overview(LocalDate from, LocalDate to) {
    Range range = range(from, to);
    long questions =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.role = 'USER' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long activeUsers =
        number(
            "select count(distinct c.user_id) from cv_message m join cv_conversation c on c.id ="
                + " m.conversation_id where c.deleted_at is null and m.role = 'USER' and"
                + " m.created_at >= ? and m.created_at < ?",
            range);
    long newUsers =
        number(
            "select count(*) from iam_user where deleted_at is null and created_at >= ? and"
                + " created_at < ?",
            range);
    long tokens =
        number(
            "select coalesce(sum(coalesce(m.prompt_tokens, 0) + coalesce(m.completion_tokens, 0)),"
                + " 0) from cv_message m join cv_conversation c on c.id = m.conversation_id where"
                + " c.deleted_at is null and m.role = 'ASSISTANT' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    Double satisfaction =
        nullableDecimal(
            "select 100.0 * count(*) filter (where m.feedback = 'UP') / nullif(count(*) filter"
                + " (where m.feedback is not null), 0) from cv_message m join cv_conversation c on"
                + " c.id = m.conversation_id where c.deleted_at is null and m.feedback is not null"
                + " and m.created_at >= ? and m.created_at < ?",
            range);
    return new Overview(questions, activeUsers, newUsers, tokens, satisfaction);
  }

  /** 按天趋势；没有数据的天补 0，前端折线不缺齿。 */
  @Transactional(readOnly = true)
  public List<TrendPoint> trend(LocalDate from, LocalDate to) {
    Range range = range(from, to);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select to_char(m.created_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD') as day, "
                + "count(*) filter (where m.role = 'USER') as questions, "
                + "count(distinct c.user_id) filter (where m.role = 'USER') as users "
                + "from cv_message m join cv_conversation c on c.id = m.conversation_id "
                + "where c.deleted_at is null and m.created_at >= ? and m.created_at < ? "
                + "group by 1 order by 1",
            Timestamp.from(range.start()),
            Timestamp.from(range.end()));
    Map<String, TrendPoint> byDay = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      byDay.put(
          String.valueOf(row.get("day")),
          new TrendPoint(
              String.valueOf(row.get("day")),
              ((Number) row.get("questions")).longValue(),
              ((Number) row.get("users")).longValue()));
    }
    List<TrendPoint> points = new ArrayList<>();
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      points.add(byDay.getOrDefault(day.toString(), new TrendPoint(day.toString(), 0, 0)));
    }
    return points;
  }

  /** 应用排行（只列区间内有提问的应用）。 */
  @Transactional(readOnly = true)
  public List<AssistantRankItem> topAssistants(LocalDate from, LocalDate to, int limit) {
    Range range = range(from, to);
    int capped = Math.max(1, Math.min(limit, 50));
    return jdbc.query(
        "select a.id, a.name, count(*) filter (where m.role = 'USER') as call_count,"
            + " coalesce(sum(case when m.role = 'ASSISTANT' then coalesce(m.prompt_tokens, 0) +"
            + " coalesce(m.completion_tokens, 0) else 0 end), 0) as token_count, 100.0 * count(*)"
            + " filter (where m.feedback = 'UP') / nullif(count(*) filter (where m.feedback is not"
            + " null), 0) as satisfaction from as_assistant a join cv_conversation c on"
            + " c.assistant_id = a.id and c.deleted_at is null join cv_message m on"
            + " m.conversation_id = c.id where m.created_at >= ? and m.created_at < ? group by"
            + " a.id, a.name having count(*) filter (where m.role = 'USER') > 0 order by call_count"
            + " desc, a.name asc limit ?",
        (rs, rowNum) ->
            new AssistantRankItem(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getLong("call_count"),
                rs.getLong("token_count"),
                round(rs.getBigDecimal("satisfaction"))),
        Timestamp.from(range.start()),
        Timestamp.from(range.end()),
        capped);
  }

  /** 质量读数；见类注释：没有数据源的三项返回 null，不伪造。 */
  @Transactional(readOnly = true)
  public Quality quality(LocalDate from, LocalDate to) {
    Range range = range(from, to);
    Double avgRefs =
        nullableDecimal(
            "select avg(jsonb_array_length(m.reference_snapshot)) from cv_message m join"
                + " cv_conversation c on c.id = m.conversation_id where c.deleted_at is null and"
                + " m.role = 'ASSISTANT' and m.reference_snapshot is not null and"
                + " jsonb_typeof(m.reference_snapshot) = 'array' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long up =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.feedback = 'UP' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long down =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.feedback = 'DOWN' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    return new Quality(avgRefs == null ? 0.0 : avgRefs, null, null, up, down, 0);
  }

  /** 消费者自己的口径；与全平台口径刻意不共用查询。 */
  @Transactional(readOnly = true)
  public MeSummary me(UUID userId, LocalDate from, LocalDate to) {
    if (userId == null) {
      throw new IllegalArgumentException("当前用户不能为空");
    }
    Range range = range(from, to);
    long questions =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id "
                + "where c.user_id = ? and c.deleted_at is null and m.role = 'USER' "
                + "and m.created_at >= ? and m.created_at < ?",
            range,
            userId);
    long conversations =
        number(
            "select count(distinct c.id) from cv_message m join cv_conversation c on c.id ="
                + " m.conversation_id where c.user_id = ? and c.deleted_at is null and m.created_at"
                + " >= ? and m.created_at < ?",
            range,
            userId);
    Instant lastActive =
        jdbc.queryForObject(
                    "select max(m.created_at) from cv_message m join cv_conversation c on c.id ="
                        + " m.conversation_id where c.user_id = ? and c.deleted_at is null and"
                        + " m.created_at >= ? and m.created_at < ?",
                    Timestamp.class,
                    userId,
                    Timestamp.from(range.start()),
                    Timestamp.from(range.end()))
                instanceof Timestamp ts
            ? ts.toInstant()
            : null;
    long up =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id "
                + "where c.user_id = ? and c.deleted_at is null and m.feedback = 'UP' "
                + "and m.created_at >= ? and m.created_at < ?",
            range,
            userId);
    long down =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id "
                + "where c.user_id = ? and c.deleted_at is null and m.feedback = 'DOWN' "
                + "and m.created_at >= ? and m.created_at < ?",
            range,
            userId);
    return new MeSummary(questions, conversations, lastActive, up, down);
  }

  private long number(String sql, Range range) {
    Long value = jdbc.queryForObject(sql, Long.class, range.startTs(), range.endTs());
    return value == null ? 0 : value;
  }

  private long number(String sql, Range range, UUID userId) {
    Long value = jdbc.queryForObject(sql, Long.class, userId, range.startTs(), range.endTs());
    return value == null ? 0 : value;
  }

  private Double nullableDecimal(String sql, Range range) {
    BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, range.startTs(), range.endTs());
    return round(value);
  }

  private static Double round(BigDecimal value) {
    return value == null ? null : value.setScale(1, RoundingMode.HALF_UP).doubleValue();
  }

  private static Range range(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("统计区间不能为空");
    }
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("统计区间的开始日期不能晚于结束日期");
    }
    if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
      throw new IllegalArgumentException("统计区间不能超过 " + MAX_RANGE_DAYS + " 天");
    }
    Instant start = from.atStartOfDay(ZONE).toInstant();
    Instant end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
    return new Range(start, end);
  }

  private record Range(Instant start, Instant end) {
    Timestamp startTs() {
      return Timestamp.from(start);
    }

    Timestamp endTs() {
      return Timestamp.from(end);
    }
  }

  public record Overview(
      long questionCount,
      long activeUserCount,
      long newUserCount,
      long tokenCount,
      Double satisfaction) {}

  public record TrendPoint(String day, long questions, long users) {}

  public record AssistantRankItem(
      UUID appId, String name, long callCount, long tokenCount, Double satisfaction) {}

  public record Quality(
      double avgRefCount,
      Double firstTokenLatencyP50,
      Double artifactFailureRate,
      long upCount,
      long downCount,
      long pendingFeedbackCount) {}

  public record MeSummary(
      long myQuestionCount,
      long myConversationCount,
      Instant lastActiveAt,
      long myUp,
      long myDown) {}
}
