package cn.lincoln.aiplatform.identity.web;

import cn.lincoln.aiplatform.identity.infrastructure.LoginLogStore;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录日志（`#354`）。
 *
 * <p>权限用 {@code audit:view} —— 与审计记录同一个权限点。理由：这两张表回答的是同一类问题（"出事了，去翻记录"），
 * 而能看审计记录的人本来就该能看"谁在试密码"。不新造一个 {@code login-log:view}： 多一个权限点就多一处要在四份清单里同步的地方，而它能分出来的差别今天没有第二类人。
 *
 * <p>★ 只读。这张表没有删除入口，也没有 {@code /purge}（审计那边有，登录日志不给）—— 被删掉的登录证据比没有更糟：事后无法判断"是没记录"还是"被清过"。
 */
@RestController
@RequestMapping("/api/v1/login-logs")
public class LoginLogController {

  private final LoginLogStore loginLogs;

  public LoginLogController(LoginLogStore loginLogs) {
    this.loginLogs = loginLogs;
  }

  /**
   * 一页登录日志 + 各结果的计数。
   *
   * @param days 最近几天（默认 7）
   * @param result 结果过滤（SUCCESS / BAD_CREDENTIALS / LOCKED / DISABLED / CAPTCHA），空 = 全部
   * @param keyword 用户名包含，空 = 全部
   */
  @GetMapping
  @PreAuthorize("hasAuthority('audit:view')")
  public ApiResponse<View> page(
      @RequestParam(defaultValue = "7") int days,
      @RequestParam(required = false) String result,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    int safeDays = Math.max(1, Math.min(days, 365));
    int safeSize = Math.max(1, Math.min(size, 200));
    LoginLogStore.Result parsed = null;
    if (result != null && !result.isBlank()) {
      try {
        parsed = LoginLogStore.Result.valueOf(result.trim().toUpperCase());
      } catch (IllegalArgumentException ignored) {
        // ★ 认不出的结果值当成"不过滤"，而不是 400：这是只读排查页，读者手上可能是一个旧值。
        //   静默退回"全部"在这里是安全的 —— 他看到的是更多行，不是更少的行。
        parsed = null;
      }
    }
    LoginLogStore.Page pageOf =
        loginLogs.page(safeDays, parsed, keyword, Math.max(1, page), safeSize);
    return ApiResponse.ok(new View(pageOf.list(), pageOf.total(), loginLogs.counts(safeDays)));
  }

  /** 一页 + 总数 + 最近几天各结果的次数。 */
  public record View(
      List<LoginLogStore.Entry> list, long total, List<LoginLogStore.Count> counts) {}
}
