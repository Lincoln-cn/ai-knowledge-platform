package cn.lincoln.aiplatform.platform.web;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
   * <strong>状态不允许</strong>这个动作（如「已完成的文档再次开始向量化」）⇒ 400 + <strong>原文展示原因</strong>。
   *
   * <p>★ 不能让它落进兜底 handler 变成 500：状态机抛的消息本来就是<strong>写给用户看的</strong>
   * （「当前状态是「SUCCEEDED」，不允许转到「EMBEDDING」（允许的来源状态：…）」）。 500 会把它盖成「服务内部错误」，用户既不知道发生了什么、也不知道下一步做什么 ——
   * 而这类拒绝的真实语义是「你这个动作在当前状态下不合法」，属于调用方可以照着改的一类。
   *
   * <p>★ {@code IllegalArgumentException} 同族处理（领域校验如「分段正文不能为空」「向量维度不匹配」）—— 它们的消息同样是写给人看的中文，没有理由降级成
   * 500。
   */
  /**
   * 上传文件超过大小上限（`FR-03-05-3` 要求单文件 100MB、超限拒绝并提示）。
   *
   * <h3>★★ 为什么必须有这一条</h3>
   *
   * <p>实测过一个很难查的现场：16 个语料文件上传，**≤662KB 的全成功、≥1368KB 的全部 HTTP 500**。 原因是 Spring Boot 的 multipart
   * 默认上限 **1MB**，而本仓此前**没有**任何 multipart 配置， 也**没有**这条异常处理 ⇒ 超限被兜底成 500「服务器内部错误」。
   *
   * <p>★ 这条的两种失真都很贵：
   *
   * <ul>
   *   <li><b>限额错了 100 倍</b>：设计书 `FR-03-05-3` 写的是 **100MB**，实际 1MB；
   *   <li><b>状态码误导</b>：500 让调用方以为"服务端坏了"，于是**重试** —— 而重试永远不会成功。 正确的形状是 413 + "这个文件多大、上限多大"。
   * </ul>
   *
   * <p>★ 用 413（Payload Too Large）而不是 400：4xx 里只有它准确表达"不是格式错，是太大了"。
   *
   * @param cause Spring 抛的原始异常（里面带着具体是哪个文件超了）
   * @return 413 + 可读原因
   */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> onUploadTooLarge(
      org.springframework.web.multipart.MaxUploadSizeExceededException cause) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(
            ApiResponse.fail(
                ErrorCode.BAD_REQUEST,
                "上传文件超过大小上限",
                "单文件上限 100MB（FR-03-05-3）。请拆分或压缩后重试 —— ★ 重试同一个文件不会成功"));
  }

  @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
  public ResponseEntity<ApiResponse<Void>> onIllegalState(RuntimeException cause) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, cause.getMessage()));
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

  /**
   * 请求体不是合法 JSON（如 {@code {username:admin}} —— 字段名/字符串没加双引号）。
   *
   * <p>★ 必须显式处理，否则会被下面的兜底 handler 抓成 <strong>500</strong>： {@code HttpMessageNotReadableException}
   * 继承自 {@code Exception}。它与"非法 UUID ⇒ 500"同族 —— <strong>是你传错了</strong>（400，调用方自己能修），
   * 被报成"我崩了"（500，得来找后端翻堆栈）。
   *
   * <p>★ 陷阱（与 {@link #onNoHandler} / {@link #onTypeMismatch} 同一条）： <strong>兜底
   * {@code @ExceptionHandler(Exception.class)} 会把"请求格式错误"改写成 500</strong>。
   * 凡是"调用方能修的输入问题"，必须在兜底之前有明确的 4xx 映射。
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> onUnreadableBody(HttpMessageNotReadableException cause) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiResponse.fail(
                ErrorCode.BAD_REQUEST,
                "请求体不是合法 JSON",
                "请检查请求体是否为合法 JSON、字段名用双引号（如 {\"username\":\"admin\"}）"));
  }

  /**
   * 路径存在、但<strong>方法不对</strong>（如对只注册了 {@code GET} 的路径发 {@code POST}）⇒ 405 + 可用方法。
   *
   * <p>★ 必须显式处理，否则会被兜底 handler 抓成 <strong>500「服务内部错误」</strong>。 这条的真实现场（不是假设）：前端契约里有一条 {@code POST
   * /segments/batch-move}，后端还没实现它， 而 Spring 会把这条路径匹配到 {@code GET /segments/{id}}（占位符吃到 {@code
   * batch-move}）， 方法不符 ⇒ {@code HttpRequestMethodNotSupportedException}；
   * 于是联调时看到的是「服务内部错误，请把这次操作的时间反馈给管理员」—— 一句把运维引向后端堆栈、而真实原因只是「这个接口还没做 / 方法写错了」的话。
   *
   * <p>★ 与 {@link #onNoHandler} 的区别值得写清：那里是「路径都不认识」（404），这里是「路径认识、方法不认识」（405）。 两者都属于「调用方能自己修」，都不能是
   * 500。
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> onMethodNotSupported(
      HttpRequestMethodNotSupportedException cause) {
    String supported =
        cause.getSupportedHttpMethods() == null
            ? "未知"
            : cause.getSupportedHttpMethods().stream()
                .map(org.springframework.http.HttpMethod::name)
                .sorted()
                .collect(Collectors.joining("/"));
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(
            ApiResponse.fail(
                ErrorCode.BAD_REQUEST,
                "请求方法不被允许：" + cause.getMethod() + " 该路径",
                "该路径只接受 " + supported + " —— 若这是前端契约里已声明、后端尚未实现的端点，" + "它在后端还不存在（不是服务器故障）"));
  }

  /**
   * 路径参数类型不匹配（如 {@code /users/not-a-uuid}）。
   *
   * <p>★ 必须显式映射成 <strong>400</strong>：它是"调用方传错了 id"（他自己能修）， 落进兜底 handler 会变成 500「服务内部错误」（得来找后端）。
   * 这条差别在联调时立刻显形。
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> onTypeMismatch(
      MethodArgumentTypeMismatchException cause) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiResponse.fail(
                ErrorCode.BAD_REQUEST, "参数类型不正确：" + cause.getName(), "请检查 id 是否为合法的 UUID"));
  }

  /**
   * 已认证但无权限（{@code @PreAuthorize} 拒绝）。
   *
   * <p>★ 真实机制值得写下来，因为第一反应几乎一定会搞反：{@code @PreAuthorize} 的拒绝发生在
   * <strong>控制器方法调用中</strong>（DispatcherServlet 内部）， 而 DispatcherServlet
   * 的异常解析（{@code @RestControllerAdvice}）<strong>先于</strong> {@code ExceptionTranslationFilter}
   * 决定怎么处理。于是下面那条匹配一切的 {@code @ExceptionHandler(Exception.class)} 先把安全异常改写成 500， {@code
   * ExceptionTranslationFilter} / {@code AccessDeniedHandler} 根本没机会看到它。
   *
   * <p>★ 也就是说：问题<strong>不是</strong>"异常类型对不上"（{@code AuthorizationDeniedException} 确实继承 {@code
   * AccessDeniedException}），而是"兜底 handler 抢在安全过滤器链前面"。 403 是"你没这个权限点"（可找管理员开），500 是"我崩了"（得来查后端）——
   * 混起来会把运维引到错误的方向。
   *
   * <p>★ 教训（很隐蔽）：<strong>一个匹配一切的 {@code @ExceptionHandler(Exception.class)}
   * 会抢在安全过滤器链前面改写安全异常</strong>。 只列 {@code AccessDeniedException} 即可覆盖子类 {@code
   * AuthorizationDeniedException} —— 两个都列反而暗示它们无关。
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> onDenied(AccessDeniedException cause) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.fail(ErrorCode.FORBIDDEN, "无权限", "请联系管理员为你的角色勾选该权限点"));
  }

  /**
   * 兜底：未预期的异常也要是可读的包络，不能漏出堆栈（{@code ROADMAP §7.3} 第 ③ 条：不存在静默失效）。
   *
   * <p>★ 陷阱：这个"匹配一切"的 handler 会把<strong>请求格式错误</strong>也改写成 500 —— {@code
   * HttpMessageNotReadableException}（非法 JSON）、 {@code MethodArgumentTypeMismatchException}（非法
   * UUID）、 {@code NoResourceFoundException} 都继承 {@code Exception}。 凡是"调用方能修的输入问题"必须在上面显式映射成 4xx，
   * 否则联调时会看到"传错了 body / id"被报成"服务内部错误"， 人被指到后端去翻堆栈。
   */
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
