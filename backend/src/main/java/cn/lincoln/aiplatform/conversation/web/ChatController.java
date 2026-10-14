package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ChatStreamWriter;
import cn.lincoln.aiplatform.conversation.application.ChatTurnWriter;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/**
 * 问答流端点（契约：{@code frontend/src/lib/sse.ts} 的 {@code chatStreamViaSse}）。
 *
 * <h3>★★ 逐字照契约的四处</h3>
 *
 * <table>
 *   <tr><th>契约</th><th>值</th><th>出处</th></tr>
 *   <tr><td>方法 + 路径</td><td>{@code POST /conversations/chat}</td>
 *       <td>{@code lib/sse.ts}：{@code fetch(`${API_PREFIX}/conversations/chat`, { method: 'POST' … })}</td></tr>
 *   <tr><td>请求体</td><td>{@code {appId?, conversationId?, question, attachmentIds?, thinking?}}</td>
 *       <td>同上，{@code JSON.stringify({...})} 那五行</td></tr>
 *   <tr><td>响应</td><td>{@code text/event-stream}，事件 {@code meta/refs/delta/artifact/done/error}</td>
 *       <td>{@code stream-types.ts} 的六种事件 + {@code lib/sse.ts} 的 {@code switch}</td></tr>
 *   <tr><td>心跳</td><td>SSE <strong>注释帧</strong> {@code : keep-alive}</td>
 *       <td>{@code stream-types.ts} 契约①（{@code HeartbeatComment}）</td></tr>
 * </table>
 *
 * <p>★ <strong>契约里没有的字段一律不收</strong>：{@code mode} / {@code targetMessageId}（重生成 / 继续生成） 与 {@code
 * attachmentIds} 在契约里，但本批不落 —— 收下不用会让调用方以为它们生效了。 见交付报告的「明确没做的」。
 *
 * <h3>★★ 为什么用 {@link StreamingResponseBody} 而不是 {@code SseEmitter}</h3>
 *
 * <p>要的三件事它都能给，而 {@code SseEmitter} 会弄错一件：
 *
 * <ol>
 *   <li><strong>每帧立刻 flush</strong>（{@code Writer#flush}）—— {@code SseEmitter} 也有， 但它的 {@code send}
 *       会把事件重新拼一遍，"事件名从 {@code EventKind} 取"这条唯一性就多了一个绕行点；
 *   <li><strong>注释帧</strong>—— {@code SseEmitter} 只发 {@code event/data}，发注释要塞一个特殊对象再自己序列化，
 *       那等于把心跳的形状搬到控制器里；
 *   <li><strong>写失败能抛出 {@code IOException}</strong>—— 这是 {@code DISCONNECT} 唯一的观测方式； {@code
 *       SseEmitter} 把写失败变成"完成回调"，调用方拿不到那个异常。
 * </ol>
 *
 * <h3>★★ 安全上下文必须显式传播（否则异步线程上什么都看不见）</h3>
 *
 * <p>{@code SecurityContextHolder} 默认是 {@code ThreadLocal}：MVC 的异步执行在<strong>另一条线程</strong>上，
 * 而检索侧的密级谓词读的是 {@code currentScope()} —— 不传播的后果是 {@code AccessScopeAdapter} 返回 {@link
 * cn.lincoln.aiplatform.platform.access.ScopeFilter#nobody()} ⇒ <strong>检索安静地返 0
 * 条</strong>（看起来像"知识库里没有"，而真实原因是"身份没跟过来"）。 所以这里手工把上下文交给异步线程（项目没开 {@code SecurityContextHolder}
 * 的策略配置， 而"默认的东西"正是最容易在换容器 / 换线程模型时静默失效的那一层）。
 *
 * <h3>★ 权限点</h3>
 *
 * <p>{@code conversation:view}（{@code Permissions.CONVERSATION_VIEW}）：问答是"用会话"这件事， 它自带 {@code
 * conversation:share} / {@code conversation:manage} 两档更细的权限，与它们同族。 ★ 写端点<strong>必须</strong>带
 * {@code @PreAuthorize}：{@code AuthorizationDeclarationTest} 构建期检查，漏一个就红。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ChatController {

  /**
   * 开流时那条"填充"注释帧的载荷 —— ★ 它必须<strong>足够长</strong>，长到能让容器把 HTTP 响应头提交出去。
   *
   * <p>2KB 是本机实测的可用量级（Tomcat 默认的响应缓冲是 8KB，但它对"已经 flush 过一次"的响应会立刻提交头部）。 ★ 它的前 6 个字符是 {@code open:
   * }：注释帧的内容对前端没有语义，但一个可读的前缀能让 "抓包看到的第一条注释帧"自己说明来意（而不是一串看起来像乱码的填充）。
   */
  private static final String OPEN_PADDING =
      "open: " + "sse-response-header-commit-padding.".repeat(80);

  private final ChatService chatService;
  private final ChatStreamWriter writer;

  public ChatController(ChatService chatService, ObjectMapper json) {
    this.chatService = chatService;
    this.writer = new ChatStreamWriter(json);
  }

  /**
   * 一轮问答的流式端点（契约的 {@code POST /conversations/chat}）。
   *
   * <p>★ 返回 {@code 200 + text/event-stream}：契约要求"先建立连接、再谈生成"； 而"会话看不见 /
   * 没有模型"这类<strong>流还没开始</strong>的问题在开始写流之前就抛出去 —— 于是它们仍然走统一的包络（{@code 404} / {@code 400}），而不是"先
   * 200 再在流里报错" （后者会让前端把一次越权读成"生成失败"）。
   *
   * <h3>★★ 这里<strong>刻意不写</strong> {@code produces = TEXT_EVENT_STREAM_VALUE}（本批实测踩到过）</h3>
   *
   * <p>写了它，Spring 的内容协商会把整个映射的"可接受表示"钉死成 {@code text/event-stream} —— 于是一旦 {@code startTurn}
   * 抛出业务异常（越权 {@code 404} / 没有模型 {@code 400}）， {@code GlobalExceptionHandler} 想写 JSON
   * 包络时<strong>没有可用的 message converter</strong>： 报 {@code HttpMediaTypeNotAcceptableException: No
   * acceptable representation}， 最后由容器兜成 <strong>500</strong> —— 而前端的 {@code Accept} 恰好只有 {@code
   * text/event-stream} （{@code lib/sse.ts}），所以这会命中<strong>每一次</strong>越权。
   *
   * <p>⇒ 媒体类型由 {@code ResponseEntity.contentType(...)} <strong>显式给出</strong>（同一条口径：
   * 形状写在一处，而不是靠注解的隐式协商）。
   */
  @PostMapping("/chat")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ResponseEntity<StreamingResponseBody> chat(
      @RequestBody ChatStreamBody body, @AuthenticationPrincipal Jwt jwt) {
    UUID userId = subjectOf(jwt);
    // ★ 落库与归属判定走服务层（看不见 ⇒ 404）；它在写流之前发生，所以错误还能用包络返回
    ChatTurnWriter.PreparedTurn prepared =
        chatService.startTurn(
            new ChatService.ChatRequest(
                body.appId(), body.conversationId(), body.question(), userId));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(streamOf(prepared, body.question().strip()));
  }

  /**
   * 把"流还没开始"那一段的<strong>统一包络</strong>显式写出来（{@code 08 §4.1}）。
   *
   * <h3>★★ 为什么这条链路上的错误不能交给 {@code @RestControllerAdvice}（本批实测踩到过）</h3>
   *
   * <p>前端的 SSE 请求带的 {@code Accept} <strong>只有</strong> {@code text/event-stream} （{@code
   * lib/sse.ts}）。于是 {@code GlobalExceptionHandler} 想写 JSON 包络时， Spring 的异常处理路径上<strong>拿不到一个可用的
   * message converter</strong>： 日志里是 {@code Failure in @ExceptionHandler #onBusiness(...)} + {@code
   * HttpMediaTypeNotAcceptableException: No acceptable representation}， 最后容器把它兜成
   * <strong>500</strong>。
   *
   * <p>★★ 修法不是"改一处注解"：{@code produces} 去掉之后这条路径<strong>照样</strong>不可接受 （实测：从 {@code 400} 变成 {@code
   * 500}）。根因是"错误响应的媒体类型由请求的 Accept 协商"， 而这条链路的 Accept 恰好排除了 JSON。
   *
   * <p>⇒ 正确形状是<strong>显式给出错误响应的媒体类型</strong>： {@code ResponseEntity} 上的 {@code Content-Type}
   * 会盖过内容协商（这正是 {@code @ExceptionHandler} 做不到、而控制器可以做到的那一件事）。 于是这一条路径上的三类错误各有自己的码与文案，且都是 JSON：
   *
   * <ul>
   *   <li>会话看不见 / 不属于我 ⇒ {@code 404}（{@code 07 §3.3} 纪律 2：不确认存在性）；
   *   <li>没有可用的对话模型 / 空提问 ⇒ {@code 400}；
   *   <li>认证链出问题（取不出 {@code sub}）⇒ {@code 401}。
   * </ul>
   *
   * @return 统一包络 + 对应 HTTP 码，媒体类型恒为 {@code application/json}
   */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> onBusiness(BusinessException cause) {
    HttpStatus status =
        switch (cause.code()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.from(cause));
  }

  /**
   * 真正写帧的那一段（跑在 MVC 的异步线程上）。
   *
   * <p>★ 三件事：① 装上安全上下文（见类注释）；② 把每一个事件写成帧并 flush； ③ 让 {@code IOException} 以 {@link
   * UncheckedIOException} 传出去 —— 那是 {@code ChatService} 判定 {@code DISCONNECT} 的唯一依据。
   *
   * <h3>★★ 为什么开头要写一条"填充"注释帧并立刻 flush（本批实测踩到过）</h3>
   *
   * <p>{@code StreamingResponseBody} 的第一个字节写出去<strong>不等于</strong> HTTP 响应头已经提交：
   * 容器会把头部攒在缓冲区里，直到它觉得"差不多了"（数据够多 / 缓冲区满 / 响应结束）。 于是客户端在<strong>整条流结束之前</strong>一个字节都读不到 —— 第一帧
   * {@code meta} 也要等 10 秒才出现。
   *
   * <p>症状极具误导性：前端"生成中停止"的按钮要等 {@code meta} 拿到 {@code messageId} 才可用， 而 {@code meta} 要等整篇答案写完才到 ⇒
   * <strong>停止按钮永远点不了</strong>， 而界面看起来只是"有点慢"。★ 本批的停止用例就是这么发现的： 客户端置停止信号的那一刻，服务端早就写完了。
   *
   * <p>⇒ 开流先写一条 SSE <strong>注释帧</strong>（{@code : open} + 2KB 填充）再 flush： ①
   * 它<strong>不是</strong>业务事件（前端解析器跳过，不影响事件序列）； ② 2KB 足够让容器把响应头提交出去（本机实测：提交之后逐帧都是实时的）。
   * 心跳同样是注释帧，所以这一步不引入任何新形状。
   */
  private StreamingResponseBody streamOf(ChatTurnWriter.PreparedTurn prepared, String question) {
    SecurityContext context = SecurityContextHolder.getContext();
    return outputStream -> {
      SecurityContext previous = SecurityContextHolder.getContext();
      SecurityContextHolder.setContext(context);
      try (Writer out = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
        writer.writeComment(OPEN_PADDING, out);
        chatService.streamTurn(
            prepared,
            question,
            event -> {
              try {
                writer.write(event, out);
              } catch (IOException failure) {
                throw ChatStreamWriter.disconnected(failure);
              }
            });
      } finally {
        SecurityContextHolder.setContext(previous);
      }
    };
  }

  /**
   * 取当前登录用户（JWT 的 {@code sub}）。
   *
   * <p>★ 取不到 ⇒ {@code 401}（不是 500、也不是"当成匿名继续"）：本端点要求认证， 而"有请求、没身份"只可能来自网关 / 过滤器链出问题 ——
   * 当成匿名会让后面每一步都静默地少看见东西。
   */
  private static UUID subjectOf(Jwt jwt) {
    try {
      return jwt == null ? null : UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException notAUuid) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态无效", "请重新登录后再试");
    }
  }

  /**
   * 请求体（契约 {@code ChatStreamOptions} 的服务端那一半，逐字照 {@code lib/sse.ts} 的 {@code body}）。
   *
   * <p>★ {@code attachmentIds} 与 {@code thinking} 收下但<strong>不消费</strong>：契约会传它们， 而未知字段的处理取决于
   * Jackson 配置 —— 收成显式字段就不会因为"前端多发了一个"变成 400。 ★ 这是<strong>形状兼容</strong>，不是"功能已经支持"。
   *
   * @param appId 应用 id（可空 = 通用问答）
   * @param conversationId 会话 id（可空 = 新建一个会话）
   * @param question 提问（必填）
   * @param attachmentIds 契约里的附件 id 列表（★ 本批不消费）
   * @param thinking 契约里的"是否允许思考"（★ 本批不消费）
   */
  public record ChatStreamBody(
      UUID appId,
      UUID conversationId,
      String question,
      List<String> attachmentIds,
      Boolean thinking) {}
}
