package cn.lincoln.aiplatform.conversation.application;

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

  public PublicChatService(
      List<PublicTokenResolver> tokenResolvers,
      PublicAppPort publicApps,
      AccessScopePort accessScope,
      ChatService chatService) {
    this.tokenResolvers = tokenResolvers;
    this.publicApps = publicApps;
    this.accessScope = accessScope;
    this.chatService = chatService;
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

  // ────────────────────────── 内部 ──────────────────────────

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
