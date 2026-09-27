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

  /** 兜底：未预期的异常也要是可读的包络，不能漏出堆栈（{@code ROADMAP §7.3} 第 ③ 条：不存在静默失效）。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception cause) {
    log.error("未预期的异常", cause);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiResponse<>(500, "服务内部错误", null, "请把这次操作的时间反馈给管理员，日志里有对应的堆栈"));
  }

  private static HttpStatus httpStatusOf(ErrorCode code) {
    return switch (code) {
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case FORBIDDEN -> HttpStatus.FORBIDDEN;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.BAD_REQUEST;
    };
  }
}
