package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.ChatHistoryService;
import cn.lincoln.aiplatform.conversation.application.ChatMessageView;
import cn.lincoln.aiplatform.conversation.application.ChatMode;
import cn.lincoln.aiplatform.conversation.application.ChatService;
import cn.lincoln.aiplatform.conversation.application.ChatStopService;
import cn.lincoln.aiplatform.conversation.application.ChatStreamAssembler;
import cn.lincoln.aiplatform.conversation.application.ConversationFeedbackService;
import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.application.PublicChatService;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
 * 条</strong>（看起来像"知识库里没有"，而真实原因是"身份没跟过来"）。 ★ 那件事的载体现在是 {@link ChatStreamAssembler}
 * ——本类与公开面共用它，于是"传播"只有一处实现（本批从本类抽出去的，见那个类的注释）。
 *
 * <h3>★ 权限点</h3>
 *
 * <p>{@code conversation:view}（{@code Permissions.CONVERSATION_VIEW}）：问答是"用会话"这件事， 它自带 {@code
 * conversation:share} / {@code conversation:manage} 两档更细的权限，与它们同族。 ★ 写端点<strong>必须</strong>带
 * {@code @PreAuthorize}：{@code AuthorizationDeclarationTest} 构建期检查，漏一个就红。
 *
 * <p>★★ 免登录面的同一个端点（{@code POST /api/v1/public/{token}/messages}）在 {@code PublicChatController}：
 * 它<strong>不</strong>是本类的特例分支，而是"同一台引擎换一个正当性入口"（{@code 08 §4.4.4}）， 两者共用 {@link
 * ChatStreamAssembler} 与 {@link ChatService}。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ChatController {

  private final ChatService chatService;
  private final ChatStreamAssembler assembler;
  private final PublicChatService publicChat;
  private final ChatStopService chatStop;
  private final ChatHistoryService chatHistory;
  private final ConversationListService conversationList;
  private final ConversationFeedbackService conversationFeedback;

  public ChatController(
      ChatService chatService,
      ChatStreamAssembler assembler,
      PublicChatService publicChat,
      ChatStopService chatStop,
      ChatHistoryService chatHistory,
      ConversationListService conversationList,
      ConversationFeedbackService conversationFeedback) {
    this.chatService = chatService;
    this.assembler = assembler;
    this.publicChat = publicChat;
    this.chatStop = chatStop;
    this.chatHistory = chatHistory;
    this.conversationList = conversationList;
    this.conversationFeedback = conversationFeedback;
  }

  /**
   * ★★ 会话历史消息（契约 {@code listMessages}；登录面这一半 —— 页面**已在调**它： {@code useChatSession#loadHistory}
   * 的非公开面分支）。
   *
   * <h3>两种取数方式（契约 {@code MessageQuery} 逐字）</h3>
   *
   * <ul>
   *   <li>{@code page}/{@code size}：常规分页；★ <strong>{@code page=-1} = 最后一页</strong> （聊天窗口先看最近几条）；
   *   <li>{@code around}：以某条消息为中心取窗口；<strong>传了它就忽略 {@code page}</strong>； {@code size}
   *       在这里是<strong>窗口宽度</strong>；★ <strong>找不到该消息 ⇒ 404</strong>， 不是静默回退第一页。
   * </ul>
   *
   * <p>★ 判定与读法全在 {@link ChatHistoryService#list}：会话看不见 ⇒ 404（与 {@code stop} 同一条口径）。
   *
   * <p>★ 权限点 {@code conversation:view}（与 {@code chat} / {@code stop} 同一个）：读历史是"用会话"这件事 的一部分。★
   * 这是<strong>读</strong>端点，所以它不触发 {@code AuthorizationDeclarationTest} 的写端点规则； 但会话归属的判定仍然必须有 ——
   * 少了它会变成"任何登录用户都能读别人的会话"。
   */
  /**
   * 会话列表（契约 `listConversations`：`GET /conversations`）。
   *
   * <p>★★ 这个端点补的是一个**让 A2 在界面上不成立**的洞：会话页加载时调它拿"历史会话"， 而它此前不存在 ⇒ 侧栏永远空 ⇒ **刷新后没有会话可恢复**（诊断逐字见
   * `ConversationListService` 的类注释）。服务层那一半一直是对的。
   *
   * @param appId 只看某个应用的会话（可空 = 全部）
   * @param keyword 按标题模糊搜索（可空）
   * @param page 页码，{@code -1} = 最后一页（与历史消息同一条约定）
   */
  /**
   * 新建会话（契约 `createConversation`：`POST /conversations`，body `{appId?}`）。
   *
   * <p>★ 与列表一起落：契约闸门要求「同路径上的方法单边变更会静默漂移」， 而页面的「新建会话」按钮调的就是它（真栈诊断逐字见过 `接口不存在：api/v1/conversations`）。
   */
  /**
   * 会话评分（设计册 `05 §6.2`：`POST /conversations/{id}/feedback`，body `{ score, comment? }`）。
   *
   * <p>★ 补的是 S3 的最后一条（`ROADMAP §2.1`「会话评分」）。★ 形状**不是猜的**：设计册 `05:602` 写了端点，`02:415`
   * 写了聚合口径（`Feedback`：消息、会话、用户、赞踩、评分、评论）。 ★ 教训与 REGENERATE 那次相同：**先读契约与设计册，再问人**。
   *
   * <p>★ 再提交是**覆盖**（一人一场会话一条），不是追加 —— 设计册里只有「提交评分」。
   */
  @PostMapping("/{conversationId}/feedback")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<ConversationFeedbackView> submitConversationFeedback(
      @PathVariable UUID conversationId,
      @RequestBody ConversationFeedbackBody body,
      @AuthenticationPrincipal Jwt jwt) {
    var saved =
        conversationFeedback.submit(subjectOf(jwt), conversationId, body.score(), body.comment());
    return ApiResponse.ok(
        new ConversationFeedbackView(
            saved.conversationId(),
            saved.score(),
            saved.comment(),
            saved.createdAt(),
            saved.updatedAt()));
  }

  /** 会话评分的请求体（契约 `submitConversationFeedback`）。 */
  public record ConversationFeedbackBody(int score, String comment) {}

  /**
   * 会话评分的响应形状。
   *
   * @param conversationId 会话
   * @param score 1–5
   * @param comment 可选评论
   * @param createdAt 首次提交时间
   * @param updatedAt 最近一次提交时间（★ 覆盖提交时它会变，`createdAt` 不变）
   */
  public record ConversationFeedbackView(
      UUID conversationId, int score, String comment, Instant createdAt, Instant updatedAt) {}

  @PostMapping
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<ConversationListService.ConversationListItem> createConversation(
      @RequestBody(required = false) CreateConversationBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        conversationList.create(subjectOf(jwt), body == null ? null : body.appId()));
  }

  /** 新建会话的请求体（契约里只有一个可选字段）。 */
  public record CreateConversationBody(UUID appId) {}

  @GetMapping
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<PageResult<ConversationListService.ConversationListItem>> conversations(
      @RequestParam(required = false) UUID appId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(conversationList.list(subjectOf(jwt), appId, keyword, page, size));
  }

  @GetMapping("/{conversationId}/messages")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<PageResult<ChatMessageView>> messages(
      @PathVariable UUID conversationId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) UUID around,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(chatHistory.list(conversationId, subjectOf(jwt), page, size, around));
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
  // ★★ 两个正当性入口（08 §4.4.4）—— 一条端点、两种身份：
  //   · 登录面：带 JWT 且令牌里有 conversation:view ⇒ 照旧（现状一个字节没改）
  //   · 公开面：**没有 JWT、但有 X-Access-Credential**（前端 lib/sse.ts:161 的免登录形状）
  //     ⇒ 走 PublicChatService：PublicTokenResolver 解析 + asSubject(归属人, 冻结值)
  //   ★ 为什么要写成 `or`：这条端点必须在过滤器链上 permitAll（匿名访客没有 JWT），
  //     于是**授权只剩方法级这一处** —— 它必须同时表达两种正当性，少写半个条件就是"对匿名全开"。
  @PreAuthorize("hasAuthority('conversation:view') or #credentialToken != null")
  public ResponseEntity<StreamingResponseBody> chat(
      @RequestBody ChatStreamBody body,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "X-Access-Credential", required = false) String credentialToken) {
    // ★ 凭据头优先 —— ★ 这一条**刻意**与"有 JWT 走登录面"的字面顺序不同，理由如下：
    //   ① 带链接的请求是"关于这条链接"的请求，而 asSubject 保证生效范围只会**收窄**、绝不放大
    //      （密级取两者更低的、其余分量照抄调用者）⇒ 这条分支不可能让它看到更多；
    //   ② 反过来（JWT 优先）的代价是"登录用户拿同一条链接不放大"这条判据**在 HTTP 层读不到**；
    //   ③ 前端两条通道互斥（lib/sse.ts 只设其中之一）⇒ 这个分支在生产上不影响任何人。
    if (credentialToken != null) {
      // ★★ 公开面显式拒绝「重新生成」—— 必须在**任何落库之前**（见 rejectRegenerate 的 javadoc：
      //   放在 startTurn 之后再拒会留下一条永远生成不完的空消息）。
      if (ChatMode.parse(body.mode()) != ChatMode.NEW) {
        publicChat.rejectRegenerate();
      }
      UUID requesterId = jwt == null ? null : subjectOf(jwt);
      PublicChatService.PreparedTurn turn =
          publicChat.startTurn(credentialToken, body.question(), requesterId);
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_EVENT_STREAM)
          .body(
              assembler.streamOf(
                  new ChatStreamAssembler.AsyncTurn(
                      turn.prepared(),
                      body.question().strip(),
                      null,
                      publicChat.streamTurn(turn))));
    }
    UUID userId = subjectOf(jwt);
    // ★ 落库与归属判定走服务层（看不见 ⇒ 404）；它在写流之前发生，所以错误还能用包络返回
    // ★★ 按**模式**启动：REGENERATE 的问题来自库里（那一条回答之前最近的用户消息），
    //   所以必须用返回的 question，不能用请求体里的那个。
    ChatService.StartedTurn started =
        chatService.startTurn(
            new ChatService.ChatRequest(
                body.appId(), body.conversationId(), body.question(), userId),
            cn.lincoln.aiplatform.conversation.application.ChatMode.parse(body.mode()),
            body.targetMessageId());
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .body(
            assembler.streamOf(
                new ChatStreamAssembler.AsyncTurn(started.turn(), started.question())));
  }

  /**
   * ★★ 停止生成（契约 {@code stopMessage}）—— <strong>显式停止的唯一表达位</strong>（{@code #119} 的收口）。
   *
   * <h3>为什么必须是一条<strong>独立</strong>的端点</h3>
   *
   * <p>契约给两个不同的终态：{@code ABORT ⇒ done(STOPPED)}（不发 error、保留已生成内容）与 {@code DISCONNECT ⇒ error +
   * done(FAILED)}。而前端的 {@code abort()} 掐的是 fetch ——
   * 服务端只看到"连接没了"，<strong>分不出</strong>"用户点了停止"与"网线掉了"。 用户意图只能由一次显式请求表达，这条端点就是它。
   *
   * <h3>★★ 它与 {@code chat} 端点的一条重要差别：<strong>不</strong>是流</h3>
   *
   * <p>这是一次普通的 JSON 请求（{@code 200} + 回执），不是 {@code text/event-stream}：
   * 它回答的是"请求收下了没有"，而"这一轮最终停在哪一态"由生成那条流上的 {@code done} 帧回答。 把两件事塞进一条流只会让"停止请求到底生效了吗"更难读。
   *
   * <h3>★★ 判定顺序（写死，不看竞态）</h3>
   *
   * <p>收到显式停止<strong>优先于</strong>连接断开：先停止、再断连 ⇒ 终态是 {@code STOPPED}； 从没收到过停止、只是断连 ⇒ {@code
   * FAILED}。顺序的落点在 {@code ChatService.Run#decideTerminalPath()} 的第一行。
   *
   * <h3>★ 权限点</h3>
   *
   * <p>{@code conversation:view}（与 {@code chat} 登录面那一半同一个权限点）：停止是"用会话"这件事的一部分， 它自带 {@code
   * conversation:share} / {@code conversation:manage} 两档更细的权限，与它们同族。 ★ 写端点<strong>必须</strong>带
   * {@code @PreAuthorize}（{@code AuthorizationDeclarationTest} 构建期检查）。
   *
   * <p>★ 这条端点<strong>只有登录面</strong>：{@code chat} 端点的公开面分支（{@code X-Access-Credential}） 在 {@code
   * chat} 那条上共用，而公开面今天的"停止"仍退化为客户端 {@code abort()} ——
   * 那是<strong>已知缺口</strong>（写在交付报告与契约注释里），不是这里漏了一个分支。
   *
   * @param conversationId 会话 id
   * @param messageId 助手消息 id（契约 {@code meta} 帧给出）
   * @param jwt 当前登录用户（★ 取不出 {@code sub} ⇒ 401，与 {@code chat} 同一条口径）
   * @return 停止回执（{@code accepted} + 服务端此刻读到的状态）
   */
  @PostMapping("/{conversationId}/messages/{messageId}/stop")
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<ChatStopService.Receipt> stop(
      @PathVariable UUID conversationId,
      @PathVariable UUID messageId,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(chatStop.requestStop(conversationId, messageId, subjectOf(jwt)));
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
          // ★ 公开面四态码（40401–40404）：它们的数值不与 HTTP 状态重合（40001–59999），全局处理器
          //   会落进 default 给 400 —— 而这四态与 NOT_FOUND 是同一族"这条链接打不开"。
          //   ★ 判据取包络里的 code，不取 HTTP 状态（前端 utils/public-gate.ts 就是这么分支的）。
          case PUBLIC_TOKEN_UNKNOWN,
              PUBLIC_TOKEN_REVOKED,
              PUBLIC_TOKEN_EXPIRED,
              PUBLIC_TOKEN_DISABLED ->
              HttpStatus.NOT_FOUND;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ApiResponse.from(cause));
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
      Boolean thinking,
      // 契约 ChatMode = 'NEW' | 'REGENERATE' | 'CONTINUE'（不传按 NEW 处理）
      String mode,
      // 重生成 / 继续生成的目标助手消息（REGENERATE 必填）
      UUID targetMessageId) {}
}
