package cn.lincoln.aiplatform.platform.web;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把异常写成统一包络（{@code 08 §4.1}）。
 *
 * <p>★ 第 1 周刻意没写这个类，理由是"它需要 HTTP 状态 ↔ 业务码的映射规则，而那属于端点契约的细节，
 * 猜一个等于埋一个静默失效"。现在有端点（登录/刷新/用户信息）了，规则可以**依据真实消费方**定下来：
 *
 * <table>
 *   <tr><th>业务码</th><th>HTTP</th><th>为什么</th></tr>
 *   <tr><td>401</td><td>401</td><td>★ 前端 {@code lib/http.ts:84} 看 <em>HTTP</em> 401 才触发静默刷新。
 *       只把 401 放进 body 会让那条逻辑永不触发，表现为"登录过期直接被踢回登录页"</td></tr>
 *   <tr><td>403 / 404</td><td>同值</td><td>与码同义，对齐之后网关、日志、前端三处口径一致</td></tr>
 *   <tr><td>其余业务码</td><td>400</td><td>它们的共同语义是"请求在当前状态下不合法"
 *       （分段超长、模型未就绪、发布校验不过）。具体原因由包络里的 code 承载，前端按 code 分支</td></tr>
 * </table>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> onBusiness(BusinessException cause) {
    return ResponseEntity.status(httpStatusOf(cause.code())).body(ApiResponse.from(cause));
  }

  /**
   * 请求体校验失败。
   *
   * <p>★ 这里把<strong>字段名</strong>带上（"password: 不能为空"），而不是笼统说"参数不合法" —— 缺字段的是调用方，可读的原因能让对接的人自己修好。
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> onValidation(MethodArgumentNotValidException cause) {
    String detail =
        cause.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .collect(Collectors.joining("；"));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, detail));
  }

  /**
   * 请求打到了一个**不存在的端点**。
   *
   * <p>★ 必须显式处理，否则会被下面的兜底 handler 抓成 <strong>500</strong>： Spring Boot 3.2+ 对未匹配的请求抛 {@code
   * NoResourceFoundException}， 而它继承自 {@code Exception}。这个差别在联调时立刻显形 —— 前端的分析看板调 {@code
   * /api/v1/analytics/overview}（后端还没实现）时， 界面收到的是 500「服务内部错误」，于是去翻后端堆栈，而真实原因是**接口还没做**。
   *
   * <p>★ 它同时是"契约漂移"的一类早期信号：前端先行开发的接口，后端可能还没有。 hint 直接把这一点说出来，比让人对着 500 猜要省事得多。
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> onNoHandler(NoResourceFoundException cause) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ApiResponse.fail(
                ErrorCode.NOT_FOUND,
                "接口不存在：" + cause.getResourcePath(),
                "确认后端是否已实现该端点 —— 也可能是前端先行开发留下的差异"));
  }

  /** 兜底：未预期的异常也要是可读的包络，不能漏出堆栈（{@code ROADMAP §7.3} 第 ③ 条：不存在静默失效）。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception cause) {
    log.error("未预期的异常", cause);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiResponse<>(500, "服务内部错误", null, "请把这次操作的时间反馈给管理员，日志里有对应的堆栈"));
  }

  private static HttpStatus httpStatusOf(ErrorCode code) {
    // ★ 码值本身就是 HTTP 语义时（400 / 401 / 403 / 404）直接对齐。
    //   写成"按码值解析"而不是逐个 case，是为了让以后新增这类码**不必再回来改这张表** ——
    //   最初漏掉 ACCOUNT_LOCKED 就是"加了码、忘了改映射"的典型后果：
    //   它悄悄落进 default 变成了 400，而 400 的语义是"你的请求有问题"，
    //   实际却是"账号被锁"。这类错误不会报任何异常。
    HttpStatus direct = HttpStatus.resolve(code.code());
    if (direct != null && direct.is4xxClientError()) {
      return direct;
    }

    return switch (code) {
      // 业务码（1001 / 2001 / 3001 / 3002 / 6001 …）里语义与 HTTP 不一致的，在这里显式列出
      case ACCOUNT_LOCKED ->
          // 语义是"请求被理解但被拒绝"（账号被锁），不是"参数错误"
          HttpStatus.FORBIDDEN;
      // 其余业务码的共同语义是"请求在当前状态下不合法"（密码太弱、模型未就绪、
      // 分段超长、发布校验不过）—— 具体原因由包络里的 code 承载，前端按 code 分支
      default -> HttpStatus.BAD_REQUEST;
    };
  }
}
