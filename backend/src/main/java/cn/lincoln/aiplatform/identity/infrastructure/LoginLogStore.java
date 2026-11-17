package cn.lincoln.aiplatform.identity.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录日志的写入与查询（`#354`）。
 *
 * <h3>★ 为什么写入用 {@code REQUIRES_NEW}</h3>
 *
 * <p>与 {@link cn.lincoln.aiplatform.identity.application.AuthSecurityRecorder} 同一个理由：
 * 登录失败的分支在记录之后**紧接着抛异常**（401 / 423）。如果这条 insert 与业务共用一个事务， 异常会把日志一起回滚 ⇒
 * **被拒的登录永远查不到**，而这正是安全排查最需要的那一半。 只记成功的登录日志，等于"出事之后只能看到攻击者成功那一次"。
 *
 * <h3>★ 为什么 IP 与 User-Agent 在这里取，而不是让调用方传</h3>
 *
 * <p>它们只有在 web 线程里才拿得到。让 {@code AuthService.login} 多两个参数，会把 "这是个 HTTP 请求"这件事泄漏到应用层（而 login
 * 也可能被定时任务或测试调用）。 所以集中在这一点取，取不到就写 null —— <b>缺 IP 不是失败理由</b>，登录日志不能因为拿不到请求头而拒掉一次登录。
 */
@Component
public class LoginLogStore {

  /** 登录结果。与库里的 {@code result} 列取值一致。 */
  public enum Result {
    SUCCESS,
    BAD_CREDENTIALS,
    LOCKED,
    DISABLED,
    CAPTCHA
  }

  private final JdbcTemplate jdbc;

  public LoginLogStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 记一次登录尝试。成功与失败都记。
   *
   * @param at 事件时刻
   * @param username 用户填的名字（账号不存在时也照记）
   * @param userId 解析到的用户；不存在为 null
   * @param result 结果
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(Instant at, String username, UUID userId, Result result) {
    HttpServletRequest request = currentRequest();
    String ip = request == null ? null : clientIp(request);
    String userAgent = request == null ? null : truncate(request.getHeader("User-Agent"), 512);
    jdbc.update(
        "insert into pf_login_log (id, at, username, user_id, result, ip, user_agent)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        Timestamp.from(at),
        truncate(username, 128),
        userId,
        result.name(),
        truncate(ip, 64),
        userAgent);
  }

  /**
   * 按条件查一页（时间倒序）。
   *
   * @param days 只看最近几天（>= 1）
   * @param result 结果过滤，null = 全部
   * @param keyword 用户名包含（不区分大小写），null = 全部
   * @param page 从 1 开始
   * @param size 每页条数
   */
  public Page page(int days, Result result, String keyword, int page, int size) {
    Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
    StringBuilder where = new StringBuilder(" from pf_login_log where at >= ?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(Timestamp.from(since));
    if (result != null) {
      where.append(" and result = ?");
      args.add(result.name());
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and lower(username) like ?");
      args.add("%" + keyword.trim().toLowerCase() + "%");
    }

    Long total = jdbc.queryForObject("select count(*)" + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    List<Entry> list =
        jdbc.query(
            "select id, at, username, user_id, result, ip, user_agent"
                + where
                + " order by at desc limit ? offset ?",
            (rs, rowNum) ->
                new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getTimestamp("at").toInstant(),
                    rs.getString("username"),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("result"),
                    rs.getString("ip"),
                    rs.getString("user_agent")),
            args.toArray());
    return new Page(list, total == null ? 0 : total);
  }

  /** 最近 {@code days} 天各结果的次数（给页面顶部那排小计用）。 */
  public List<Count> counts(int days) {
    Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
    return jdbc.query(
        "select result, count(*) as c from pf_login_log where at >= ? group by result order by c"
            + " desc",
        (rs, rowNum) -> new Count(rs.getString("result"), rs.getLong("c")),
        Timestamp.from(since));
  }

  /**
   * 客户端 IP。
   *
   * <p>★ 只有直连地址与 {@code X-Forwarded-For} 的第一段 —— 后者可以被伪造，所以它在这里只是
   * "排查线索"，不能作为鉴权依据。写进日志时不做可信度校验，但也不能假装它是可信的。
   */
  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
    }
    return request.getRemoteAddr();
  }

  private static HttpServletRequest currentRequest() {
    if (RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes) {
      return attributes.getRequest();
    }
    return null;
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** 一行登录日志。 */
  public record Entry(
      UUID id,
      Instant at,
      String username,
      UUID userId,
      String result,
      String ip,
      String userAgent) {}

  /** 一页 + 总数。 */
  public record Page(List<Entry> list, long total) {}

  /** 某个结果的次数。 */
  public record Count(String result, long count) {}
}
