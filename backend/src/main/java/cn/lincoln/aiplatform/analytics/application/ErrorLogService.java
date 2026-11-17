package cn.lincoln.aiplatform.analytics.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 错误日志（`#354`）：把系统里**已经记下来的失败**聚到一处。
 *
 * <h3>★ 为什么不新建一张 {@code pf_error_log}</h3>
 *
 * <p>项目今天的失败原因已经落在产生它的地方：回答失败在 {@code cv_message.fail_reason / fail_hint}， 解析与向量化失败在 {@code
 * kb_document.fail_reason / fail_hint}。再写一张"错误日志表"意味着 每条失败要落两处 —— 两处必然漂移，读的人还得判断哪一处更真。
 *
 * <p>因此这一页的口径是：**读已有的事实，不新增写入路径**。它也因此天生"没有滞后、不会漏写"。
 *
 * <h3>★ 它不包含的事</h3>
 *
 * <p>它<b>不含未捕获异常</b>（500 的堆栈）。那要靠集中式日志采集，属于 `#73` 的可观测那笔账；
 * 今天硬做只能做出一个"只在一台机器上、重启就没了"的假日志。页面上的说明也这么写， 免得有人以为"这里没记录 ⇒ 系统没出错"。
 */
@Service
public class ErrorLogService {

  private final JdbcTemplate jdbc;

  public ErrorLogService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 一页失败记录（时间倒序）。
   *
   * @param days 最近几天（>= 1）
   * @param source `CHAT` / `DOCUMENT`，空 = 全部
   */
  @Transactional(readOnly = true)
  public Page page(int days, String source, int page, int size) {
    Instant since = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);
    String filter = source == null || source.isBlank() ? "" : " where source = ?";
    Object[] filterArgs =
        source == null || source.isBlank()
            ? new Object[0]
            : new Object[] {source.trim().toUpperCase()};

    String cte =
        "with failures as ( select 'CHAT' as source, m.created_at as at, '回答失败' as title,"
            + " coalesce(m.fail_reason, '未知原因') as reason, m.fail_hint as hint,"
            + " m.conversation_id::text as ref, m.id::text as row_id from cv_message m join"
            + " cv_conversation c on c.id = m.conversation_id where c.deleted_at is null and m.role"
            + " = 'ASSISTANT' and m.fail_reason is not null and m.created_at >= ? union all select"
            + " 'DOCUMENT', d.updated_at, d.name, coalesce(d.fail_reason, '未知原因'), d.fail_hint,"
            + " d.id::text, d.id::text from kb_document d where d.deleted_at is null and"
            + " d.fail_reason is not null and d.updated_at >= ?)";

    Long total =
        jdbc.queryForObject(
            cte + " select count(*) from failures" + filter,
            Long.class,
            merge(filterArgs, Timestamp.from(since), Timestamp.from(since)));
    List<Entry> list =
        jdbc.query(
            cte
                + " select source, at, title, reason, hint, ref, row_id from failures"
                + filter
                + " order by at desc limit ? offset ?",
            (rs, rowNum) ->
                new Entry(
                    rs.getString("source"),
                    rs.getTimestamp("at").toInstant(),
                    rs.getString("title"),
                    rs.getString("reason"),
                    rs.getString("hint"),
                    rs.getString("ref"),
                    rs.getString("row_id")),
            merge(
                filterArgs,
                Timestamp.from(since),
                Timestamp.from(since),
                Math.max(1, size),
                (long) (Math.max(1, page) - 1) * Math.max(1, size)));
    List<Count> bySource =
        jdbc.query(
            cte + " select source, count(*) as c from failures group by source order by c desc",
            (rs, rowNum) -> new Count(rs.getString("source"), rs.getLong("c")),
            Timestamp.from(since),
            Timestamp.from(since));
    return new Page(list, total == null ? 0 : total, bySource);
  }

  /**
   * 把过滤参数放到 CTE 的两个时间占位符之后。
   *
   * <p>★ 这里必须手工拼而不是用命名参数：{@code JdbcTemplate} 的位置参数与 CTE 的占位符顺序绑死， 而过滤条件在 CTE 外面 ——
   * 顺序错了就会"过滤值当成时间"，症状是查不出任何行且不报错。
   */
  private static Object[] merge(Object[] filterArgs, Object... fixed) {
    Object[] all = new Object[fixed.length + filterArgs.length];
    System.arraycopy(fixed, 0, all, 0, fixed.length);
    System.arraycopy(filterArgs, 0, all, fixed.length, filterArgs.length);
    return all;
  }

  /** 一条失败。 */
  public record Entry(
      String source,
      Instant at,
      String title,
      String reason,
      String hint,
      String ref,
      String rowId) {}

  /** 一页 + 总数 + 来源分布。 */
  public record Page(List<Entry> list, long total, List<Count> bySource) {}

  /** 某个来源的条数。 */
  public record Count(String source, long count) {}
}
