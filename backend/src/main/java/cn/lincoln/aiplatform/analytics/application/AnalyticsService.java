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

  /**
   * 通用对话（{@code assistant_id} 为 null）在排行里的显示名。
   *
   * <p>★ 写成一个常量而不是散在 SQL 与前端：这行会成为用户截图里的一句话，两处各写一份迟早不一致。
   */
  static final String GENERIC_ASSISTANT_NAME = "通用对话（未绑定应用）";

  /**
   * 读数按「谁在问」切分：全部 / 只算通用问答 / 只算应用。
   *
   * <p>★ 为什么需要它：看板上那个「提问数」此前是**全平台所有会话的 USER 消息**。评测脚本批量跑出来的
   * 几百轮和真人问的一句混在同一个数里，读起来就是"用量涨得很猛，但业务上没人用"。 两者都是真数据，但**混在一起就没有意义** ——
   * 想知道的是"人用了多少"还是"机器跑了多少"，得能分开看。
   *
   * <p>★ 判据只有一条：{@code cv_conversation.assistant_id} 为 null = 通用问答，非 null = 某个应用。
   * 不去猜"哪个应用是评测用的"（靠名字猜必然错），也不加用途字段（那要迁移与人工维护）。
   */
  public enum SourceScope {
    ALL,
    GENERAL,
    APPLICATION
  }

  /**
   * 把来源条件拼进 where。
   *
   * <p>★ 字符串拼接在这里是安全的：入参是**枚举**，没有外部可控片段；写成 {@code and (? = 'ALL' or …)} 会让每条 SQL 多三个占位符，而且 EXPLAIN
   * 时看不出到底走没走条件。
   */
  private static String sourceClause(SourceScope scope) {
    if (scope == null || scope == SourceScope.ALL) {
      return "";
    }
    return scope == SourceScope.GENERAL
        ? " and c.assistant_id is null"
        : " and c.assistant_id is not null";
  }

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final int MAX_RANGE_DAYS = 366;

  private final JdbcTemplate jdbc;

  public AnalyticsService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 总览（全平台口径）。 */
  @Transactional(readOnly = true)
  public Overview overview(LocalDate from, LocalDate to) {
    return overview(from, to, SourceScope.ALL);
  }

  /**
   * 总览读数（可按来源切分）。
   *
   * <p>★ {@code newUserCount} 不跟着切：新注册用户是账号维度的事实，与"这一问来自通用问答还是某个应用" 无关 —— 跟着切会让人以为"应用侧新增了 N
   * 个用户"，那是编出来的数。
   */
  public Overview overview(LocalDate from, LocalDate to, SourceScope scope) {
    Range range = range(from, to);
    String source = sourceClause(scope);
    long questions =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null"
                + source
                + " and m.role = 'USER' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long activeUsers =
        number(
            "select count(distinct c.user_id) from cv_message m join cv_conversation c on c.id ="
                + " m.conversation_id where c.deleted_at is null"
                + source
                + " and m.role = 'USER' and m.created_at >= ? and m.created_at < ?",
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
                + " c.deleted_at is null"
                + source
                + " and m.role = 'ASSISTANT' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    Double satisfaction =
        nullableDecimal(
            "select 100.0 * count(*) filter (where m.feedback = 'UP') / nullif(count(*) filter"
                + " (where m.feedback is not null), 0) from cv_message m join cv_conversation c on"
                + " c.id = m.conversation_id where c.deleted_at is null"
                + source
                + " and m.feedback is not null"
                + " and m.created_at >= ? and m.created_at < ?",
            range);
    return new Overview(questions, activeUsers, newUsers, tokens, satisfaction);
  }

  /** 按天趋势；没有数据的天补 0，前端折线不缺齿。 */
  @Transactional(readOnly = true)
  public List<TrendPoint> trend(LocalDate from, LocalDate to) {
    return trend(from, to, SourceScope.ALL);
  }

  /** 按天趋势（可按来源切分）；没有数据的天补 0，前端折线不缺齿。 */
  @Transactional(readOnly = true)
  public List<TrendPoint> trend(LocalDate from, LocalDate to, SourceScope scope) {
    Range range = range(from, to);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select to_char(m.created_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD') as day, "
                + "count(*) filter (where m.role = 'USER') as questions, "
                + "count(distinct c.user_id) filter (where m.role = 'USER') as users "
                + "from cv_message m join cv_conversation c on c.id = m.conversation_id "
                + "where c.deleted_at is null"
                + sourceClause(scope)
                + " and m.created_at >= ? and m.created_at < ? "
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

  /**
   * 应用排行（只列区间内有提问的应用）。
   *
   * <p>★ <strong>通用对话也要有一行</strong>（{@code #284}）。原来这里是 {@code join as_assistant a on
   * c.assistant_id = a.id} —— <em>内连</em>，于是 {@code assistant_id} 为 null 的通用对话被整个丢掉： 总量 SQL 不含
   * assistant 条件（所以它算进去了），排行里却看不见它。两个数放在一页上读起来 像"对不上"，其实是同一个集合被切成两半、只显示了一半。
   *
   * <p>★ 用 {@code left join} 而不是 {@code union all}：前者一处改动、排序与 token/满意度口径自动一致； 后者要在两段 SQL
   * 里各写一遍同一套聚合，迟早漂移。
   *
   * <p>★ 虚拟行的 id 用全零 UUID（契约里 {@code id} 是必填）：前端据此区分"这是一行真实应用"还是 "这是通用对话"，不需要再加一个布尔字段。
   */
  @Transactional(readOnly = true)
  public List<AssistantRankItem> topAssistants(LocalDate from, LocalDate to, int limit) {
    Range range = range(from, to);
    int capped = Math.max(1, Math.min(limit, 50));
    return jdbc.query(
        "select coalesce(a.id, cast('00000000-0000-0000-0000-000000000000' as uuid)) as id,"
            + " coalesce(a.name, '"
            + GENERIC_ASSISTANT_NAME
            + "') as name, count(*) filter (where m.role = 'USER') as call_count,"
            + " count(distinct c.id) as conversation_count,"
            + " coalesce(sum(case when m.role = 'ASSISTANT' then coalesce(m.prompt_tokens, 0) +"
            + " coalesce(m.completion_tokens, 0) else 0 end), 0) as token_count, 100.0 * count(*)"
            + " filter (where m.feedback = 'UP') / nullif(count(*) filter (where m.feedback is not"
            + " null), 0) as satisfaction from cv_conversation c left join as_assistant a on"
            + " c.assistant_id = a.id join cv_message m on m.conversation_id = c.id"
            + " where c.deleted_at is null and m.created_at >= ? and m.created_at < ? group by"
            + " a.id, a.name having count(*) filter (where m.role = 'USER') > 0 order by call_count"
            + " desc, name asc limit ?",
        (rs, rowNum) -> {
          long calls = rs.getLong("call_count");
          long conversations = rs.getLong("conversation_count");
          return new AssistantRankItem(
              rs.getObject("id", UUID.class),
              rs.getString("name"),
              calls,
              conversations,
              // 每会话轮数（保留一位小数）。这是"机器在跑"的唯一可读信号 —— 277 次调用对 1 个会话是
              // 评测脚本，对 120 个会话才是真人在用。除零只在 conversations = 0 时发生，而 having 已经
              // 把没有提问的会话滤掉了，这里仍显式兜一下（宁可显示 0 也不抛异常）。
              conversations == 0 ? 0.0 : Math.round(calls * 10.0 / conversations) / 10.0,
              rs.getLong("token_count"),
              round(rs.getBigDecimal("satisfaction")));
        },
        Timestamp.from(range.start()),
        Timestamp.from(range.end()),
        capped);
  }

  /** 质量读数；见类注释：没有数据源的三项返回 null，不伪造。 */
  @Transactional(readOnly = true)
  public Quality quality(LocalDate from, LocalDate to) {
    return quality(from, to, SourceScope.ALL);
  }

  /** 质量读数（可按来源切分）；见类注释：没有数据源的三项返回 null，不伪造。 */
  @Transactional(readOnly = true)
  public Quality quality(LocalDate from, LocalDate to, SourceScope scope) {
    Range range = range(from, to);
    String source = sourceClause(scope);
    Double avgRefs =
        nullableDecimal(
            "select avg(jsonb_array_length(m.reference_snapshot)) from cv_message m join"
                + " cv_conversation c on c.id = m.conversation_id where c.deleted_at is null"
                + source
                + " and m.role = 'ASSISTANT' and m.reference_snapshot is not null and"
                + " jsonb_typeof(m.reference_snapshot) = 'array' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long up =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null"
                + source
                + " and m.feedback = 'UP' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    long down =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null"
                + source
                + " and m.feedback = 'DOWN' and m.created_at >= ? and"
                + " m.created_at < ?",
            range);
    return new Quality(avgRefs == null ? 0.0 : avgRefs, null, null, up, down, 0);
  }

  /**
   * 运营指标（`#357` 前半）：**质量**与**用量**两块，全平台口径。
   *
   * <h3>为什么它和 {@link #overview} 分开</h3>
   *
   * <p>总览回答"今天用了多少"，指标回答"答得好不好"。后者要的是**分母**——比如"无引用回答占比"的分母
   * 必须是成功回答数、不是全部回答数（失败的回答本来就没有引用，算进去会把产品问题说成检索问题）。 两件事的分母不同，硬塞进一个 record 会让调用方开始自己算比例。
   *
   * <h3>★ 哪些是真读数，哪些仍是"—"</h3>
   *
   * <p>能真算：提问数、回答数、成功/失败数、token、总耗时 p50/p95、平均引用数、无引用占比、活跃应用/用户、赞踩。
   *
   * <p>仍是 null：**首字延迟**（{@code cv_message} 只有 {@code duration_ms}，没有首 token 时间戳）、
   * **B1/B2/B3**（那是离线评测读数，不是运行期数据，见 {@code docs/检索基线读数.md}）。 这两类在这条接口上永远返回 null，界面显示"—"并写明出处 ——
   * 不让运行期接口冒充评测读数。
   *
   * @param from 起始日期（含）
   * @param to 结束日期（含）
   * @return 质量 + 用量读数
   */
  @Transactional(readOnly = true)
  public Metrics metrics(LocalDate from, LocalDate to) {
    Range range = range(from, to);
    String assistantScope =
        " from cv_message m join cv_conversation c on c.id = m.conversation_id"
            + " where c.deleted_at is null and m.role = 'ASSISTANT'"
            + " and m.created_at >= ? and m.created_at < ?";
    long questions =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.role = 'USER'"
                + " and m.created_at >= ? and m.created_at < ?",
            range);
    long answers = number("select count(*)" + assistantScope, range);
    long generated =
        number("select count(*)" + assistantScope + " and m.status = 'GENERATED'", range);
    long tokens =
        number(
            "select coalesce(sum(coalesce(m.prompt_tokens, 0) + coalesce(m.completion_tokens, 0)),"
                + " 0)"
                + assistantScope,
            range);
    Double avgDurationMs =
        nullableDecimal(
            "select avg(m.duration_ms)" + assistantScope + " and m.duration_ms is not null", range);
    Double p50 =
        nullableDecimal(
            "select cast(percentile_cont(0.5) within group (order by m.duration_ms) as numeric)"
                + assistantScope
                + " and m.duration_ms is not null",
            range);
    Double p95 =
        nullableDecimal(
            "select cast(percentile_cont(0.95) within group (order by m.duration_ms) as numeric)"
                + assistantScope
                + " and m.duration_ms is not null",
            range);
    Double avgRefs =
        nullableDecimal(
            "select avg(jsonb_array_length(m.reference_snapshot))"
                + assistantScope
                + " and m.reference_snapshot is not null and jsonb_typeof(m.reference_snapshot) ="
                + " 'array'",
            range);
    long withoutRefs =
        number(
            "select count(*)"
                + assistantScope
                + " and m.status = 'GENERATED'"
                + " and (m.reference_snapshot is null"
                + " or jsonb_typeof(m.reference_snapshot) is distinct from 'array'"
                + " or jsonb_array_length(m.reference_snapshot) = 0)",
            range);
    long activeAssistants =
        number(
            "select count(distinct c.assistant_id) from cv_conversation c"
                + " where c.deleted_at is null and c.assistant_id is not null"
                + " and exists (select 1 from cv_message m where m.conversation_id = c.id"
                + " and m.created_at >= ? and m.created_at < ?)",
            range);
    long activeUsers =
        number(
            "select count(distinct c.user_id) from cv_conversation c"
                + " where c.deleted_at is null"
                + " and exists (select 1 from cv_message m where m.conversation_id = c.id"
                + " and m.created_at >= ? and m.created_at < ?)",
            range);
    long up =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.feedback = 'UP'"
                + " and m.created_at >= ? and m.created_at < ?",
            range);
    long down =
        number(
            "select count(*) from cv_message m join cv_conversation c on c.id = m.conversation_id"
                + " where c.deleted_at is null and m.feedback = 'DOWN'"
                + " and m.created_at >= ? and m.created_at < ?",
            range);
    return new Metrics(
        questions,
        answers,
        generated,
        answers - generated,
        tokens,
        avgDurationMs,
        p50,
        p95,
        avgRefs == null ? 0.0 : avgRefs,
        withoutRefs,
        percent(withoutRefs, generated),
        activeAssistants,
        activeUsers,
        up,
        down,
        percent(up, up + down));
  }

  /** 百分比（保留 1 位）；分母为 0 时返回 null —— 「0/0」不是 0%，是没有样本。 */
  private static Double percent(long part, long whole) {
    if (whole <= 0) {
      return null;
    }
    return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
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

  /**
   * 应用排行的一行。
   *
   * @param appId 应用 id；通用问答这一行用全零 UUID（见 {@link #topAssistants} 的注释）
   * @param name 应用名，通用问答这一行是 {@link #GENERIC_ASSISTANT_NAME}
   * @param callCount 提问数（USER 消息条数）
   * @param conversationCount 会话数 —— 与提问数一起看才有意义：277 次 / 1 个会话是脚本，277 次 / 120 个会话才是人在用
   * @param turnsPerConversation 每会话轮数（提问数 ÷ 会话数，保留一位小数）
   * @param tokenCount prompt + completion
   * @param satisfaction 满意度（%），无赞踩时为 null
   */
  public record AssistantRankItem(
      UUID appId,
      String name,
      long callCount,
      long conversationCount,
      double turnsPerConversation,
      long tokenCount,
      Double satisfaction) {}

  public record Quality(
      double avgRefCount,
      Double firstTokenLatencyP50,
      Double artifactFailureRate,
      long upCount,
      long downCount,
      long pendingFeedbackCount) {}

  /**
   * 运营指标（质量 + 用量）。
   *
   * @param questionCount 提问数（USER 消息）
   * @param answerCount 回答数（ASSISTANT 消息，含失败）
   * @param generatedAnswerCount 成功回答数（status = GENERATED）
   * @param failedAnswerCount 失败回答数
   * @param tokenCount prompt + completion
   * @param avgDurationMs 平均总耗时（不是首字延迟）
   * @param durationP50Ms 总耗时 p50
   * @param durationP95Ms 总耗时 p95
   * @param avgRefCount 平均引用条数
   * @param answersWithoutRefs 无引用回答数（只数成功回答）
   * @param noRefRate 无引用占比（%），分母 = 成功回答数
   * @param activeAssistantCount 有消息往来的应用数
   * @param activeUserCount 有消息往来的用户数
   * @param upCount 赞
   * @param downCount 踩
   * @param satisfactionRate 满意度（%）= 赞 / (赞 + 踩)
   */
  public record Metrics(
      long questionCount,
      long answerCount,
      long generatedAnswerCount,
      long failedAnswerCount,
      long tokenCount,
      Double avgDurationMs,
      Double durationP50Ms,
      Double durationP95Ms,
      double avgRefCount,
      long answersWithoutRefs,
      Double noRefRate,
      long activeAssistantCount,
      long activeUserCount,
      long upCount,
      long downCount,
      Double satisfactionRate) {}

  public record MeSummary(
      long myQuestionCount,
      long myConversationCount,
      Instant lastActiveAt,
      long myUp,
      long myDown) {}
}
