package cn.lincoln.aiplatform.platform.web;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;

/**
 * 统一响应包络 —— <strong>单层</strong>（{@code 08 §4.1} 第 1 条）。
 *
 * <p>契约真源：{@code frontend/src/api/types.ts} 的 {@code ApiResult<T>} = {@code { code, message, data
 * }}。★ 注意它<strong>没有</strong> {@code hint}： {@code hint} 在原设计里是 {@code ApiError} 类的字段，前端当前只从 {@code
 * code} / {@code message} 构造它。但 {@code 08 §4.1} 明确要求后端产出 {@code hint}， 且契约允许"只加可选字段"，所以这里多输出一个
 * {@code hint} —— <em>成功时为 null</em>，前端不读也不受影响。
 *
 * <p>⚠️ 已知缺口（不在本模块职责内，仅在此记录以免被当成已实现）：前端 {@code src/lib/http.ts} 在两条构造 {@code ApiError} 的路径上（成功响应里的非
 * 0 码、 HTTP 错误分支）<strong>都没有取 {@code hint}</strong>。也就是说后端填了 hint， 目前也到不了界面。修复需要动前端两行 —— 属"前端欠账"，见
 * CHANGELOG 0.0.1。
 *
 * <p>★ {@code data} 在失败时输出 {@code null} 而<strong>不是省略</strong>： 契约里 {@code data} 是必填位（{@code data:
 * T}），省略它会迫使前端写 {@code payload.data ?? null} 这种防御代码。
 */
public record ApiResponse<T>(int code, String message, T data, String hint) {

  private static final String OK_MESSAGE = "ok";

  /** 成功，带数据。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(ErrorCode.OK.code(), OK_MESSAGE, data, null);
  }

  /** 成功，无数据体。用于 204 语义或纯动作型端点。 */
  public static ApiResponse<Void> ok() {
    return new ApiResponse<>(ErrorCode.OK.code(), OK_MESSAGE, null, null);
  }

  /** 成功，自定义文案（如"会员已停用"这类需要回显动作结果的场景）。 */
  public static <T> ApiResponse<T> ok(String message, T data) {
    return new ApiResponse<>(ErrorCode.OK.code(), message, data, null);
  }

  /** 失败，用错误码的兜底文案。★ 只在确实没有更具体的话可说时用。 */
  public static <T> ApiResponse<T> fail(ErrorCode code) {
    return new ApiResponse<>(code.code(), code.defaultMessage(), null, null);
  }

  public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
    return new ApiResponse<>(code.code(), message, null, null);
  }

  public static <T> ApiResponse<T> fail(ErrorCode code, String message, String hint) {
    return new ApiResponse<>(code.code(), message, null, hint);
  }

  /** 从业务异常构造。Web 层的 {@code @ExceptionHandler} 只需要这一行。 */
  public static <T> ApiResponse<T> from(BusinessException e) {
    return new ApiResponse<>(e.code().code(), e.getMessage(), null, e.hint());
  }

  /** 契约判成功的方式：{@code code === 0}（前端 {@code ErrorCode.OK}）。 */
  public boolean isOk() {
    return code == ErrorCode.OK.code();
  }
}
