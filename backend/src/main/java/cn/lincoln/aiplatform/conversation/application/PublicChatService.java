package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRole;
import cn.lincoln.aiplatform.conversation.domain.MessageStatus;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.PublicTokenResolver;
import cn.lincoln.aiplatform.platform.assistant.PublicAppPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 公开面（免登录）问答的<strong>编排入口</strong>（第 17 周 · 0.4.5）—— "同一台引擎换一个正当性入口"。
 *
 * <h3>★★ 它<strong>不</strong>是第二份编排</h3>
 *
 * <p>取会话 / 建消息 / 检索 / 落库 / 收尾全部仍然由 {@link ChatService} + {@link ChatTurnWriter} 承担；
 * 本类只做<strong>三件公开面独有的事</strong>：
 *
 * <ol>
 *   <li><strong>解析令牌</strong>（{@code PublicTokenResolver} —— {@code 08 §4.2.3} 规定的唯一入口）；
 *   <li><strong>把取数装进链接的范围里</strong>（{@link AccessScopePort#asSubject}，{@code 08 §4.4.4}）；
 *   <li>把"应用展示面"读出来（{@link PublicAppPort}）。
 * </ol>
 *
 * <p>★ 一句判据：把本类删掉，登录面一行都不受影响；把本类里的 {@code asSubject} 换成"直接跑"，
 * 公开面就会<strong>静默地</strong>以"当前请求者"的身份取数 —— 匿名访客什么都看不见（{@code nobody()}），
 * 而登录用户会借链接放大。两者都不报错，所以它必须只有一处（本类）。
 *
 * <h3>★★ 为什么{@code asSubject} 在<strong>这里</strong>而不是在控制器里</h3>
 *
 * <p>因为"要用链接的范围取数"这件事同时包住<strong>落库</strong>（{@code startTurn} → 应用可见性判定）与
 * <strong>检索</strong>（{@link ChatService} 读 {@code currentScope()} 的密级上限）。 把其中一段留在控制器里，
 * 就成了"两个入口各包一半" —— 而那正是最容易只改一处的地方。
 *
 * <h3>★★ 时间点：令牌的可用性判定必须发生在"流开始之前"</h3>
 *
 * <p>{@link #startTurn} 在<strong>请求线程</strong>上跑完解析（四态码 {@code 40401}–{@code 40404} 由此抛出），
 * 于是这些错误仍然走统一包络（JSON + 4xx）；一旦开始写流，HTTP 已经是 200，错误只能进流里 （那会让前端把"链接失效"读成"生成失败"）。
 *
 * <p>★ 而 {@link #streamTurn} 返回的是一段<strong>稍后在异步线程上执行</strong>的取数逻辑 —— 范围必须在那一刻重新装上（{@code
 * ThreadLocal} 不跨线程），见它自己的注释。
 */
@Service
public class PublicChatService {

  private static final Logger log = LoggerFactory.getLogger(PublicChatService.class);

  private final List<PublicTokenResolver> tokenResolvers;
  private final PublicAppPort publicApps;
  private final AccessScopePort accessScope;
  private final ChatService chatService;
  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ChatStopSignal stopSignal;

  public PublicChatService(
      List<PublicTokenResolver> tokenResolvers,
      PublicAppPort publicApps,
      AccessScopePort accessScope,
      ChatService chatService,
      ConversationRepository conversations,
      MessageRepository messages,
      ChatStopSignal stopSignal) {
    this.tokenResolvers = tokenResolvers;
    this.publicApps = publicApps;
    this.accessScope = accessScope;
    this.chatService = chatService;
    this.conversations = conversations;
    this.messages = messages;
    this.stopSignal = stopSignal;
  }

  /**
   * 用一条公开链接换应用上下文（契约 {@code GET /public/:token} → {@code PublicContext}）。
   *
   * <p>★★ <strong>可见性判定走委派范围</strong>：先解析令牌，再在 {@code asSubject(归属人, 冻结密级)} 里读应用展示面。
   * 少了后一步，公开面的读取用的是<strong>调用者自己的范围</strong> —— 匿名访客是 {@code nobody()}（什么都读不到），
   * 登录用户则可能读到超出这条链接冻结密级的东西。
   *
   * @param rawToken 链接里的令牌原文
   * @param requesterId 发起解析的已登录用户；匿名时为 {@code null}
   * @return 应用展示面 + <strong>创建时冻结</strong>的密级上限
   * @throws BusinessException 令牌四态之一（{@code 40401} / {@code 40402} / {@code 40403} / {@code
   *     40404}）· 或凭证指向的应用已不在（{@code 404}，与四态刻意分开 —— 见 {@code
   *     PublicCredentialResolver.targetMissing}）
   */
  public PublicContext resolve(String rawToken, UUID requesterId) {
    PublicTokenResolver.PublicAccess link = resolveCredential(rawToken, requesterId);
    PublicAppPort.PublicApp app =
        accessScope.asSubject(
            link.subjectId(),
            link.maxClassification(),
            () -> publicApps.findVisibleById(link.assistantId()).orElse(null));
    if (app == null) {
      throw linkUnavailable();
    }
    // ★ maxClassification 直接取**解析结果里那个冻结值**（不是重新算一次，也不是应用上的任何字段）：
    //   它与 AccessCredential.max_classification 同源同义，只是换了一个消费面（13 §5.2 第①级）
    return new PublicContext(
        app.appId(),
        app.name(),
        app.icon(),
        app.description(),
        app.prologue(),
        app.quickQuestions(),
        link.maxClassification());
  }

  /**
   * ★★ 公开面<strong>显式拒绝</strong>「重新生成」（契约 {@code ChatMode.REGENERATE}）。
   *
   * <h3>为什么必须拒绝，而不是"当 NEW 处理"</h3>
   *
   * <p>契约的 {@code REGENERATE} 语义是「<strong>覆盖</strong> {@code targetMessageId} 对应的原答案
   * （<strong>不新增消息对</strong>）」。公开面若静默按 {@code NEW} 走，就会 <strong>新增一问一答</strong> ——
   * 与契约直接矛盾，而界面上它看起来完全正常 （用户点"重新生成"，多出来一条），这正是本仓头号病 {@code silent-failure} 的形状。
   *
   * <p>★ 为什么本轮不给公开面<strong>做</strong>重生成：公开面的目标消息归属只能靠 "链接归属人"间接判定，而重生成会<strong>覆盖</strong>那条答案 ——
   * 覆盖面比"读"大得多。 这一格留到"公开面是否允许改动历史"这条口径定下来之后（已记进冲刺清单）。
   *
   * <p>★★ 位置是刻意的：它必须在<strong>任何落库之前</strong>抛。放在 {@code chatService.startTurn} 之后再拒，会留下一条 {@code
   * GENERATING} 的空消息 （"一条永远生成不完的回答"），那比"拒绝"糟得多。
   */
  public void rejectRegenerate() {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "公开链接不支持重新生成", "请在登录后于会话页重新生成；公开页每一轮都是新会话");
  }

  /**
   * 开一轮免登录问答：解析令牌 → 在链接的范围里落库（建会话 / 建两条消息 / 定模型）。
   *
   * @throws BusinessException 令牌四态之一 · 链接当前不可用（{@code 404}）· 没有可用对话模型 / 空提问（{@code 400}）
   */
  public PreparedTurn startTurn(String rawToken, String question, UUID requesterId) {
    PublicTokenResolver.PublicAccess link = resolveCredential(rawToken, requesterId);
    ChatTurnWriter.PreparedTurn prepared =
        accessScope.asSubject(
            link.subjectId(),
            link.maxClassification(),
            () -> {
              // ★★ 先问一次"这条链接现在打得开吗"（与 resolve 同一条判定、同一个端口）：
              //   主体被停用 / 角色没了 ⇒ SubjectIdentityPort 载入不出 ⇒ nobody() ⇒ 应用展示面读不出来。
              //   ★ 不先问这一句，请求会掉进 ChatTurnWriter 那句"应用不存在"——**那是撒谎**：
              //   应用在，是发链接的账号当前不可用（口径见 linkUnavailable）。
              //   ★ 它同时让两个端点（GET 上下文 / POST 流）对同一种失效给**同一句话**。
              if (publicApps.findVisibleById(link.assistantId()).isEmpty()) {
                throw linkUnavailable();
              }
              return chatService.startTurn(
                  new ChatService.ChatRequest(
                      // ★ 应用来自链接（契约的 PublicContext 由凭证决定，"客户端无法指定"），不是请求体里传的
                      link.assistantId(),
                      // ★ 本批不接收 conversationId：公开页每一轮都是新会话
                      null,
                      question,
                      // ★ 会话归属人 = 链接的归属人（公开面没有"当前用户"这一维）
                      link.subjectId()));
            });
    return new PreparedTurn(link.subjectId(), link.maxClassification(), prepared);
  }

  /**
   * ★★ "这条链接当前不可用"（第 17 周 · Lead 裁决 ②）—— 与<strong>四态码分开</strong>，且<strong>不撒谎</strong>。
   *
   * <h3>它覆盖的是哪一格</h3>
   *
   * <p>凭证<strong>本身是好的</strong>（存在 / 未撤销 / 未过期 / 未停用 —— 那四态由 {@code PublicCredentialResolver}
   * 各抛各的码），但<strong>以链接的范围读不出它指向的应用</strong>。 今天唯一会走到这里的现实原因是：<strong>发链接的那个账号被停用了</strong> （{@code
   * 08 §4.4.4} 末尾那条产品口径：{@code SubjectIdentityPort} 载入不出 ⇒ {@code nobody()}）。
   *
   * <h3>为什么文案必须是这一句（而不是"应用不存在"）</h3>
   *
   * <p>★ "应用不存在"是<strong>事实错误</strong>：应用在，是账号不可用。说错了会把分享者引向错误动作 （去重建应用），而正确动作是"换一条链接 / 让管理员恢复账号"。
   * ★ 也刻意<strong>不</strong>借用四态里的任何一个码 （{@code 40401}–{@code
   * 40404}）：那四个码各自有明确的界面文案与下一步动作，把这一格塞进其中一个是 "四态静默退化"的翻版。 ⇒ 中性 {@code 404} + 这一句，由 {@code
   * GlobalExceptionHandler} 的 {@code NOT_FOUND} 分支给出 HTTP 形状。
   *
   * <h3>★ 为什么不告诉访客"账号被停用"</h3>
   *
   * <p>那是<strong>归属人的账号状态</strong>，属于内部事实：说给外部的匿名访客听，等于泄露"这个人被处置了"。
   * 所以对外只给"这条链接当前不可用"，把原因留给服务端日志（{@code SubjectIdentityAdapter} 已经 warn 过一行）。
   */
  private static BusinessException linkUnavailable() {
    return new BusinessException(ErrorCode.NOT_FOUND, "这条链接当前不可用", "请联系分享者重新生成一条链接");
  }

  /**
   * 真正跑这一轮 —— ★ 它<strong>必须在 {@code asSubject} 里</strong>跑，而且要在<strong>异步线程上</strong>再装一次。
   *
   * <h3>★★ 为什么这里要第二次 {@code asSubject}（这不是"两处判定"）</h3>
   *
   * <p>委派范围是 {@code ThreadLocal}（{@code AccessScopeAdapter.DELEGATED}），而 MVC 的异步响应体跑在
   * <strong>另一条线程</strong>上。{@link ChatService} 在那一轮里要读 {@code currentScope().maxClassification()}
   * 给检索谓词当上限 —— 在异步线程上读不到委派范围时，读到的是 {@code nobody()} ⇒ <strong>检索安静地返 0 条</strong>
   * （界面上像"知识库里没有内容"，而真实原因是"范围没跟过来"）。
   *
   * <p>★ 它<strong>不</strong>是第二处可见性判定：范围仍然只由 {@code AccessScopeAdapter.asSubject} 一处构造（
   * 主体身份从库里读、密级取冻结值、有登录调用者时再收窄）。本类只负责"在正确的线程上把它装上"， 输入仍是<strong>同两个值</strong>（{@code subjectId} +
   * {@code frozenClearance}）， 所以两次装上的范围必然相同。
   *
   * <p>★ 反过来说：把 {@code asSubject} 只留在请求线程上，是<strong>本批最容易犯且最难发现</strong>的错 ——
   * 它不会报错，只会让公开问答的引用永远为空（而那恰好与"这个知识库里确实没有相关内容"长得一模一样）。
   *
   * @param turn {@link #startTurn} 的结果（带着归属人与冻结密级）
   * @return 正当性钩子 —— 交给 {@link ChatStreamAssembler} 在异步线程上执行
   */
  public ChatStreamAssembler.Authorizer streamTurn(PreparedTurn turn) {
    return body ->
        accessScope.asSubject(
            turn.subjectId(),
            turn.frozenClearance(),
            () -> {
              body.run();
              return null;
            });
  }

  /**
   * ★★ 公开面（免登录）的<strong>停止生成</strong>（{@code #143} 前半，用户 2026-10-16 拍板"做端点"）。
   *
   * <h3>为什么是<strong>按消息 id</strong>，而不是照抄登录面那条形状</h3>
   *
   * <p>三条都可核对：① 公开页的 {@code conversationId} 是<strong>合成串</strong>（{@code public:<token>}，{@code
   * pages/conversation/public/Index.vue}）⇒ 打到登录面那条 {@code @PathVariable UUID conversationId} 上必
   * 400；② 公开面<strong>每一轮都是新会话</strong> （{@link #startTurn} 不接收 {@code conversationId}）⇒ 请求体里没有可传的会话
   * id； ③ 登录面的归属判定是"会话 {@code user_id} = 调用者"（{@code ChatStopService}），而公开面里"调用者"这个概念是
   * <strong>链接</strong>。
   *
   * <p>⇒ 会话 id <strong>由服务端从消息行反解</strong>，端点只收消息 id。这同时消掉了"客户端指定这条链接能停哪个会话"那条越权 （请求体里根本没有那个字段可填）。
   *
   * <h3>★★ 为什么<strong>不</strong>包 {@code asSubject}（与 {@link #resolve} / {@link #startTurn} 刻意不同）
   * </h3>
   *
   * <p>{@code asSubject} 管的是"<strong>看得见哪些数据</strong>"。而停止既不读内容、也不产出内容：它的全部效果是 {@link
   * ChatStopSignal#request} 一个按消息 id 的一次性信号，而那个信号只对<strong>这一条消息</strong>生效。 把它包进 {@code asSubject}
   * 会造出<strong>第二处看起来像"停止的权限判定"</strong>的东西 —— 下一个人会以为"不包就不安全"， 于是两处各写一半。★ 真正的边界由下面两条判定承担：令牌有效 +
   * 消息属于链接归属人的会话。
   *
   * <h3>★ 与登录面同一条语义（已收尾的那条消息：{@code accepted=false} 且不置信号）</h3>
   *
   * <p>为一条已经收尾的消息置信号，那个 id 会永远留在 {@link ChatStopSignal} 里（它是"一次性事件"、只由 {@code consume} 取走） ——
   * 那是一种没有症状的泄漏：直到某天有人重发同一个消息 id，它一上来就被停。这条口径与 {@code ChatStopService} 逐字相同。
   *
   * @param rawToken 链接里的令牌原文
   * @param messageId 助手消息 id（契约 {@code meta} 帧给出）
   * @param requesterId 发起请求的已登录用户；匿名时为 {@code null}（只用于收窄，绝不是正当性来源）
   * @return 停止回执（与登录面同形状：{@code accepted} + 服务端此刻读到的状态）
   * @throws BusinessException 令牌四态之一 · 消息不属于这条链接的会话（{@code 404}）· 该消息是用户消息（{@code 400}）
   */
  public StopReceipt stopTurn(String rawToken, UUID messageId, UUID requesterId) {
    // ── ① 令牌：五道判定（四态码 + 链接当前可用）复用唯一那个解析入口，一行都不重写 ──
    PublicTokenResolver.PublicAccess link = resolveCredential(rawToken, requesterId);
    // ★★ "这条链接现在打得开吗"必须**装在链接的范围里**问 —— 与 resolve / startTurn 逐字同一条：
    //   应用展示面的可见性判定读的是 `currentScope()`（`PublicAppAdapter#findVisibleById` → `canSee`），
    //   而匿名访客**自己的**范围是 nobody() ⇒ 不装就永远空 ⇒ 每一次停止都返回「这条链接当前不可用」。
    //   ★ 本类实测踩到过这个坑（判据①第一次跑红在 404），所以这里显式记一笔：**端口调用读范围，就得装范围**。
    boolean linkUsable =
        accessScope.asSubject(
            link.subjectId(),
            link.maxClassification(),
            () -> publicApps.findVisibleById(link.assistantId()).isPresent());
    if (!linkUsable) {
      // ★ 与 resolve / startTurn 同一个出口、同一句话（主体被停用 / 角色没了 ⇒ 应用展示面读不出来）
      throw linkUnavailable();
    }
    if (messageId == null) {
      throw notFound("消息不存在", "缺少助手消息 id；它由问答流的 meta 帧给出");
    }

    // ── ② 消息：必须存在，且必须挂在**这条链接归属人的**会话上（先判会话，免得从消息的报错里漏出存在性）──
    Message message =
        messages.findById(messageId).orElseThrow(() -> notFound("消息不存在", "请刷新公开页后重试"));
    Conversation conversation =
        conversations
            .findById(message.conversationId())
            .filter(found -> found.userId().equals(link.subjectId()))
            .orElseThrow(() -> notFound("消息不存在", "它不在这条链接的会话里"));

    if (message.role() != MessageRole.ASSISTANT) {
      // ★ 与登录面同一条：停止信号是按助手消息 id 查的，收下用户消息的 id 只会得到一个"谁都不看"的信号
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只能停止助手回答", "用户消息没有生成过程，不需要停止");
    }

    // ── ③ 还能不能停：已经收尾 ⇒ 回执说清"没停成"，且**不置信号**（见类注释末条）──
    if (message.status() != MessageStatus.GENERATING) {
      log.info("公开面收到停止请求，但助手消息 {} 已经收尾（{}）⇒ accepted=false，不置信号", messageId, message.status());
      return new StopReceipt(false, message.status());
    }

    // ── ④ 置上 → 登记：与登录面**同一个机制、同一条时序**（先 request 再 accept）──
    //   ★ accept=false = 这一轮在这一瞬已经收尾 ⇒ 当场拒收，**不留残留**（#143 后半；理由见 ChatStopService 第 ④ 步）。
    stopSignal.request(messageId);
    if (!stopSignal.agree(messageId)) {
      log.info("公开面停止请求晚到了：助手消息 {} 在这一瞬已经收尾 ⇒ 当场拒收（不留残留）", messageId);
      return new StopReceipt(false, message.status());
    }
    log.info(
        "公开面已认领停止信号：会话 {} 的助手消息 {} 将按 ABORT 收尾（STOPPED，保留已生成内容）", conversation.id(), messageId);
    return new StopReceipt(true, message.status());
  }

  /**
   * ★★ 公开面（免登录）的<strong>历史消息</strong>（契约 {@code listPublicMessages}；页面**已经在调**它： {@code
   * useChatSession#loadHistory} 在有 {@code credentialToken} 时走这一条）。
   *
   * <h3>★★ 归属键 = {@code (链接归属人, 应用)} 的"会话链"（{@code 13 §5.2.1}，本批新增的口径）</h3>
   *
   * <p>端点**只收 token**（契约 {@code listPublicMessages(token)}），所以"读哪条会话"必须由服务端反解：
   * 取<strong>归属人在这个应用下最近的一条会话</strong>，把它那一整串消息按 {@code created_at} 升序返回。
   * 于是公开页<strong>刷新之后读得回历史</strong> —— 这既是 A2「状态真实性：刷新页面不重置」在公开面的那一半， 也是"同一台引擎换一个正当性入口"这句话的必然要求。
   *
   * <p>★ 它**不</strong>按"每次访问"或"每个访客"分桶：拿到链接的人本来就能看到归属人范围内的检索结果， 所以"看到同一条对话"**不放大**任何东西；真正约束它的仍是
   * {@code 13 §5.2} 那两级过滤 （每次访问按**冻结值**实时再滤），见该节的表。
   *
   * <p>★ 今天<strong>没有分页</strong>（{@code MessageRepository#listByConversation} 就是"整条会话"这个最小形状）： 分页 /
   * 窗口那一批属登录面的 {@code GET /conversations/:id/messages}，与 {@code page} / {@code around} 的优先级口径同批落 ——
   * 在这里先发明一个分页形状会让两处口径分叉。
   *
   * @param rawToken 链接里的令牌原文
   * @param requesterId 发起请求的已登录用户；匿名时为 {@code null}（只用于收窄，不是正当性来源）
   * @return 该会话链上的全部消息（{@code created_at} 升序；**没有**则空列表）
   * @throws BusinessException 令牌四态之一 · 链接当前不可用（{@code 404}）
   */
  public List<ChatMessageView> listMessages(String rawToken, UUID requesterId) {
    PublicTokenResolver.PublicAccess link = resolveCredential(rawToken, requesterId);
    // ★ 与 resolve / startTurn / stopTurn 逐字同一条：可见性判定读的是 `currentScope()`，
    //   所以必须装在链接的范围里问（匿名访客自己的范围是 nobody() ⇒ 不装就永远"链接不可用"）。
    boolean linkUsable =
        accessScope.asSubject(
            link.subjectId(),
            link.maxClassification(),
            () -> publicApps.findVisibleById(link.assistantId()).isPresent());
    if (!linkUsable) {
      throw linkUnavailable();
    }
    return conversations
        .findLatestByUserAndAssistant(link.subjectId(), link.assistantId())
        .map(
            latest ->
                messages.listByConversation(latest.id()).stream().map(ChatMessageView::of).toList())
        // ★ 还没问过 ⇒ **空列表**而不是 404：链接是好的，"这个人还没发过言"是正常状态
        //   （与"令牌无效 / 链接不可用"必须可分 —— 前者前端该显示空对话，后者该显示四态文案）
        .orElseGet(List::of);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 公开面停止的回执（与登录面的 {@code ChatStopService.Receipt} <strong>同形状</strong>，契约里就是同一个 {@code
   * ChatStopReceipt}：{@code accepted} + 服务端此刻读到的状态）。
   *
   * <p>★ 有意<strong>不</strong>复用那一个 record：它是 {@code conversation.application} 里另一个用例的类型，
   * 复用会把"公开面停止"耦合到"登录面停止"的实现上；而两者共享的只是<strong>契约形状</strong>。
   */
  public record StopReceipt(boolean accepted, MessageStatus status) {}

  private static BusinessException notFound(String message, String hint) {
    return new BusinessException(ErrorCode.NOT_FOUND, message, hint);
  }

  /**
   * 解析令牌 —— ★ 公开面上<strong>唯一</strong>的解析入口（{@code 08 §4.2.3}）。
   *
   * <p>★ 逐个问每个实现而不是硬编码一个：端口的设计就是"一个接口、多个实现"（应用级凭证今天是 {@code assistant} 那个实现，将来的会话级分享是另一个），谁认得这个
   * token 谁回答。 认不出时返回 {@link Optional#empty()}， 由本方法统一翻成四态的第 ① 态（{@code 40401}）——
   * 这样"加一种凭证"不需要改公开面端点。
   */
  private PublicTokenResolver.PublicAccess resolveCredential(String rawToken, UUID requesterId) {
    for (PublicTokenResolver resolver : tokenResolvers) {
      Optional<PublicTokenResolver.PublicAccess> resolved = resolver.resolve(rawToken, requesterId);
      if (resolved.isPresent()) {
        log.debug(
            "公开面解析成功：凭证 {} → 应用 {}（发起人 {}）",
            resolved.get().credentialId(),
            resolved.get().assistantId(),
            requesterId == null ? "匿名访客" : requesterId);
        return resolved.get();
      }
    }
    // ★ 四态的第①态（认不出）：与"已撤销 / 已过期 / 已停用"三个码并列，文案由 PublicCredentialResolver 那一族承载
    throw new BusinessException(
        ErrorCode.PUBLIC_TOKEN_UNKNOWN, "访问凭证不存在", "确认链接是否完整；已删除的凭证不会因为旧链接还在而恢复");
  }

  /**
   * 契约 {@code PublicContext} 的服务端形状（{@code frontend/src/api/conversations.ts}）。
   *
   * <p>★ 字段名逐字对齐，一个不多一个不少 —— 契约已冻结，后端不许自己发明字段。
   *
   * <p>★★ {@code maxClassification} <strong>必填</strong>（契约 2026-10-14 从可选改成必填，第 17 周判据 1）：
   * 访客应当知道"这条链接读得到多密的内容"，否则他会以为"打不开 = 这里没有"。 ★ 类型用 Java 枚举（序列化出 {@code PUBLIC / INTERNAL / SECRET /
   * CONFIDENTIAL} 机器码），与库值、与前端 {@code ClassificationLevel} 三者同名（14 册 §2.1）。
   */
  public record PublicContext(
      UUID appId,
      String appName,
      String icon,
      String description,
      String prologue,
      List<String> quickQuestions,
      ClassificationLevel maxClassification) {

    /** 集合一律防御性拷贝：它会被序列化进响应，也可能会在多处被读。 */
    public PublicContext {
      quickQuestions = quickQuestions == null ? List.of() : List.copyOf(quickQuestions);
    }
  }

  /**
   * 一轮公开问答的读数：{@link ChatTurnWriter.PreparedTurn} 加上"用哪一份范围跑检索"的两个值。
   *
   * @param subjectId ★ 链接的<strong>归属人</strong> —— 取数行使的是他的范围（{@code 13 §5.2} 落地口径）
   * @param frozenClearance ★ 凭证上<strong>创建时冻结</strong>的密级上限（{@code 13 §5.2} 第 ① 级）
   * @param prepared 落库读数（会话 / 两条消息 / 模型 / 要检索的库）
   */
  public record PreparedTurn(
      UUID subjectId, ClassificationLevel frozenClearance, ChatTurnWriter.PreparedTurn prepared) {}
}
