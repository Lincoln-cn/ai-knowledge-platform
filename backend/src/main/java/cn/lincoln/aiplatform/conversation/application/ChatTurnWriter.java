package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRole;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问答轮的<strong>落库侧</strong>（第 17 周 · 0.4.5）—— 取会话 / 建消息 / 周期性 flush / 终态回写。
 *
 * <h3>★★ 为什么把这些动作单独一个类（而不是塞进编排里）</h3>
 *
 * <p>编排那条链跑在<strong>另一条线程</strong>上（SSE 的 {@code StreamingResponseBody} 由 MVC 的异步支持执行），
 * 而落库动作用的是<strong>短事务</strong>：每一笔（建消息 / flush / 终态）各自一个事务。两者混在一个类里，
 * 最自然的写法会变成"整条链一个长事务"——而那正是要避免的：一个事务跨越"等模型吐字"意味着 <strong>数据库连接被占住整个生成过程</strong>（可以几十秒），且中途的 flush
 * 对外不可见 （另一个连接读不到未提交的行 —— 而 {@code A2 刷新不重置} 正是靠"生成中途能读到"成立的）。
 *
 * <h3>★★ 状态机与累积都<strong>不在这里</strong></h3>
 *
 * <p>三态转移（{@code complete}/{@code stop}/{@code fail}）、文本累积（{@code appendText}）、 引用快照（{@code
 * bindReferences}）全部在 {@link Message} 上，本类只调用它们 —— 所以"状态机在哪儿生效"只有一个答案（{@code MessageRepository}
 * 的类注释把这条写死了）。
 *
 * <h3>★★ 会话的两列冗余计数在这里写（{@code #108} 的第二个交付）</h3>
 *
 * <p>{@code cv_conversation.message_count} / {@code last_message_at} 在本批之前<strong>没有任何写入方</strong>
 * （恒 {@code 0} / {@code NULL}）。它们由 {@link #recordMessage} 在<strong>消息落库的同一个事务里</strong>
 * 更新（每落一条消息一次），落库动作本身（{@code message_count + 1} 与 {@code greatest(现值,
 * max(cv_message.created_at))}）在库侧一条 UPDATE 里 —— 见 {@code ConversationRepository#recordMessage}。★
 * 于是列表页的那个数字<strong>来自库</strong>， 刷新页面不会把它重置（{@code ROADMAP §二} 的 {@code A2}）。
 *
 * <h3>★ 归属判定：看不见 ⇒ 404（不是 403）</h3>
 *
 * <p>会话是私人资源（{@code 07 §3.1}：默认 {@code PRIVATE} + {@code ownerId}）。
 * "别人的会话"与"不存在的会话"返回<strong>同一个</strong> 404：403 等于告诉对方"这个 id 是存在的"， 那是一次免费的探测（{@code 07 §3.3} 纪律 2
 * / {@code 08 §4.4.3} 纪律 2）。
 *
 * <h3>★★ 应用那一侧<strong>不再</strong>直接依赖 {@code assistant}：走平台端口</h3>
 *
 * <p>本类此前持有 {@code Assistant}/{@code AssistantRepository}，于是 {@code conversation} 越界读了另一个 {@code
 * CLOSED} 模块的<strong>内部类型</strong>（{@code ModuleBoundaryTest} 判红： {@code Module 'conversation'
 * depends on non-exposed type assistant.domain.AssistantRepository}）。
 *
 * <p>★ 处置按本项目既定口径（<strong>跨模块 SPI 放 {@code platform}、实现放提供方模块</strong>）： 这里只认识 {@link
 * AppBindingPort} 给的那三个事实（{@code appId} / {@code modelId} / {@code kbIds}），
 * 而"应用是否存在、我看不看得见"的判定留在实现（{@code AppBindingAdapter}）里 —— 于是本类<strong>没有机会</strong>
 * 忘记那一次可见性检查，也不再认识应用实体的任何字段。
 */
@Service
public class ChatTurnWriter implements ChatTurnStore {

  private static final Logger log = LoggerFactory.getLogger(ChatTurnWriter.class);

  /** 新建会话的初始标题（{@code 02 §2.6}：默认取首个问题前若干字）。 */
  private static final int TITLE_MAX_CHARS = 30;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final AppBindingPort appBindings;
  private final ChatPort chatModels;

  public ChatTurnWriter(
      ConversationRepository conversations,
      MessageRepository messages,
      AppBindingPort appBindings,
      ChatPort chatModels) {
    this.conversations = conversations;
    this.messages = messages;
    this.appBindings = appBindings;
    this.chatModels = chatModels;
  }

  /**
   * 一轮问答的落库侧读数（编排用它填 {@code meta} 并驱动检索）。
   *
   * @param conversationId 会话 id（本轮消息都挂它）
   * @param assistantMessageId 新助手消息 id（{@code meta} 与 {@code done} 里的那个值）
   * @param userMessageId 新用户消息 id
   * @param traceId 链路追踪 id（本次问答一个；用户报障时靠它把界面与日志对上）
   * @param modelLabel 写进 {@code meta.model} 的模型标识（模型目录的 id 串）
   * @param modelId 真正要调用的模型 id（取不到模型时 {@link #prepare} 已经抛，不会是 {@code null}）
   * @param kbIds 本轮要检索的知识库（= 应用绑定的那些；应用没绑或通用问答 ⇒ 空 ⇒ 检索侧按"全部可见"处理）
   */
  public record PreparedTurn(
      UUID conversationId,
      UUID assistantMessageId,
      UUID userMessageId,
      String traceId,
      String modelLabel,
      UUID modelId,
      List<UUID> kbIds,
      // ★ `#168`：应用上的「多轮对话」开关。它此前是个**死开关**（`ChatService` 从不读）。
      boolean multiTurn,
      // ★ `#185`：应用的思考开关。通用问答为 false；应用只表达"想不想"，模型能力决定能不能
      boolean thinkingEnabled,
      // ★ 应用归属（null = 通用问答）与角色设定：提示词来源必须由这一轮的应用决定，
      //   不能在 ChatService 里再猜一次（#183）。
      UUID appId,
      String rolePrompt,
      // ★ `#215`：应用绑定的提示词正文（已排序、已去空、已去重）。它此前**不进链路** ⇒
      //   "给应用选提示词"是个只在界面上生效的设置。空 = 没绑。
      List<String> promptTexts,
      // ★ `#185`：应用级检索参数。通用问答（app == null）时是 null ⇒ 由 ChatService 回落到平台设置。
      RetrievalParams retrieval) {}

  /**
   * 建这一轮的两条消息（用户消息 + {@code GENERATING} 助手消息）并把模型定下来。
   *
   * <p>★ 顺序是刻意的：<strong>先落库再开流</strong>。这样"刷新页面看到生成中"（{@code A2}）在第一个字节发出去之前就成立； 反过来（先发 {@code
   * meta} 再落库）会出现一个窗口：界面已经显示生成中、库里却什么都没有。
   *
   * <p>★ 模型取不到时<strong>抛 400</strong>而不是静默降级：没有对话模型就没有回答， "答一句我没法回答"是编的。
   *
   * @param conversationId 会话 id；{@code null} = 新建会话（{@code appId} 决定它属于哪个应用）
   * @param appId 应用 id；{@code null} = 通用问答
   * @param question 用户提问（非空 —— 空提问由控制器与领域各自挡一道）
   * @param userId 当前登录用户（归属判定用；★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @return 这一轮的落库读数
   * @throws BusinessException 会话不存在 / 不属于当前用户（{@code 404}）· 应用不存在或不可见（{@code 404}）· 没有可用对话模型（{@code
   *     400}）
   */
  @Override
  @Transactional
  public PreparedTurn prepare(UUID conversationId, UUID appId, String question, UUID userId) {
    // ★ 请求体里的 appId 只在**新建会话**时生效；已有会话的应用以 conversation.assistantId 为准。
    //   否则「会话 A + appId B」会用 B 的模型/知识库写进 A 的会话（#190）。
    AppBindingPort.AppBinding requestedApp = appId == null ? null : requireVisibleApp(appId);
    Conversation conversation = resolveOrCreate(conversationId, requestedApp, question, userId);
    AppBindingPort.AppBinding app =
        conversation.assistantId() == null ? null : requireVisibleApp(conversation.assistantId());

    // ★ 用户消息先落库：它是"这轮问了什么"的唯一痕迹（助手消息失败/停止都不影响它）
    Message userMessage = Message.user(UUID.randomUUID(), conversation.id(), question);
    messages.save(userMessage);
    recordMessage(conversation);

    UUID modelId = pickModelId(app);
    Message assistant = new Message(UUID.randomUUID(), conversation.id(), "");
    assistant.bindModel(modelId);
    messages.save(assistant);
    recordMessage(conversation);

    String traceId = "trace_" + assistant.id();
    log.info(
        "会话 {} 开始一轮问答：助手消息 {}（trace {}，模型 {}，应用 {})",
        conversation.id(),
        assistant.id(),
        traceId,
        modelId,
        app == null ? "通用问答" : app.appId());

    return new PreparedTurn(
        conversation.id(),
        assistant.id(),
        userMessage.id(),
        traceId,
        modelId.toString(),
        modelId,
        app == null ? List.of() : app.kbIds(),
        // ★ `#168`：把开关带进这一轮 —— 只有它能让 `ChatService` 决定要不要带历史
        // ★★ 通用问答（app == null）**默认多轮**，而不是"没有应用所以单轮"。
        //
        //   这是个设计错位：`multiTurn` 被做成了**应用**的开关（`as_assistant.multi_turn`），
        //   而通用问答根本没有应用 ⇒ 那个开关对它恒为 false ⇒ 历史永远不读。
        //   实测症状（用户）：「Iberian colonialism」→「翻译一下」，模型答
        //   「我需要先获取你要翻译的内容」—— 它**看不到上一轮**。
        //
        //   ★ "多轮"本质上是**会话**的属性（同一个 conversationId 内前后相关），
        //     不是应用的属性；把默认值翻成 true 是对这个错位的最小修正：
        //     应用那边仍尊重它自己的 `multi_turn` 开关，通用问答则总是带上历史。
        app == null || app.multiTurn(),
        // ★ `#185`：通用问答没有应用 ⇒ 不思考；应用则尊重它自己的开关
        app != null && app.thinkingEnabled(),
        app == null ? null : app.appId(),
        app == null ? null : app.rolePrompt(),
        // ★ `#215`：提示词正文随这一轮进链路（此前只有 rolePrompt 一个来源）
        app == null ? List.of() : app.promptTexts(),
        // ★ `#185`：应用级检索参数；通用问答为 null（ChatService 回落到平台设置）
        app == null ? null : app.retrieval());
  }

  /**
   * 调试的一轮（{@code #184}）：<strong>只读应用绑定，不建会话 / 消息，不落库</strong>。
   *
   * <p>★★ 与 {@link #prepare} 的唯一差别是"不落库"：应用映射、模型选取、检索参数全部走同一段代码 —— 否则调试通过、 线上仍不一致（{@code BR-02-08}
   * 要防的正是这个）。
   *
   * <p>★ {@code conversationId} 是 {@code null}：调试没有会话，因此检索域里没有会话附件那一支（那是正确语义， 不是"漏了一支"）；{@code
   * userId} 也不需要 —— 可见性判定在 {@link AppBindingPort#findVisibleById} 里用当前范围。
   */
  @Override
  @Transactional(readOnly = true)
  public PreparedTurn prepareDebug(UUID appId, String question) {
    AppBindingPort.AppBinding app = requireVisibleApp(appId);
    UUID modelId = pickModelId(app);
    UUID assistantMessageId = UUID.randomUUID();
    return new PreparedTurn(
        // ★ 调试不属于任何会话：null ⇒ 检索域里没有会话附件那一支（正确语义）
        null,
        assistantMessageId,
        UUID.randomUUID(),
        "debug_" + assistantMessageId,
        modelId.toString(),
        modelId,
        app.kbIds(),
        // ★ 调试不带历史：没有会话可带
        false,
        app.thinkingEnabled(),
        app.appId(),
        app.rolePrompt(),
        // ★ `#215`：调试要看到和应用会话**同一段** prompt —— 否则"调试通过、线上不一致"（`BR-02-08`）
        app.promptTexts(),
        app.retrieval());
  }

  /**
   * 某一轮提问**之前**的对话历史（`#168`）。
   *
   * <p>★ 只取这条用户消息之前的、且**同一会话**的；按时间正序返回；`maxMessages` 是硬上限 （防"上下文无限长"把首字延迟拖垮 —— 窗口口径的最终定稿在
   * `#65`，这里是保守实现）。
   *
   * <p>★★ 只返回**已经有正文**的助手消息：一条 `GENERATING`（空正文）或 `FAILED` 的助手消息 送进模型没有信息量，反而会让模型以为"上一轮它答了个空"。
   *
   * @param conversationId 会话
   * @param userMessageId 本轮的用户消息（它的**之前**才算历史）
   * @param maxMessages 最多取几条（含用户与助手，按时间正序取**最近的**）
   * @return 历史消息；没有则空列表
   */
  @Transactional(readOnly = true)
  public java.util.List<cn.lincoln.aiplatform.platform.model.ChatMessage> historyBefore(
      UUID conversationId, UUID userMessageId, int maxMessages) {
    // ★ 用领域端口的 `listByConversation`（时间正序）—— 它是这条读路径的既有口径
    java.util.List<Message> all = messages.listByConversation(conversationId);
    java.util.List<cn.lincoln.aiplatform.platform.model.ChatMessage> history =
        new java.util.ArrayList<>();
    for (Message message : all) {
      if (message.id().equals(userMessageId)) {
        break;
      }
      boolean usableAssistant =
          message.role() == cn.lincoln.aiplatform.conversation.domain.MessageRole.ASSISTANT
              && message.content() != null
              && !message.content().isBlank();
      if (message.role() == cn.lincoln.aiplatform.conversation.domain.MessageRole.USER
          && message.content() != null
          && !message.content().isBlank()) {
        history.add(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.USER, message.content()));
      } else if (usableAssistant) {
        history.add(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.ASSISTANT,
                message.content()));
      }
    }
    if (history.size() > maxMessages) {
      return history.subList(history.size() - maxMessages, history.size());
    }
    return history;
  }

  /**
   * 周期性 flush（{@code 03 §1.4}：500ms 或 200 字先到者）—— 把<strong>还没落库的那一段</strong>追加进去。
   *
   * <p>★ 增量而不是整段覆盖：正文的唯一写路径是 {@link Message#appendText}（只追加）。 每次 flush 由编排方传入"已经落过库的字符数"，这里只追加尾巴 ——
   * 于是"库里那一行"在生成过程中 一直等于用户此刻看到的那一段（{@code A2} 的字面要求）。
   *
   * @param messageId 助手消息 id
   * @param fullTextSoFar 到目前为止的完整正文（装配器累加出来的）
   * @param persistedChars 上一次 flush 时已经落库的字符数
   * @return 本次 flush 之后已落库的字符数
   */
  @Override
  @Transactional
  public int flush(UUID messageId, String fullTextSoFar, int persistedChars) {
    if (fullTextSoFar.length() <= persistedChars) {
      return persistedChars;
    }
    Message message = requireMessage(messageId);
    // ★ 只追加尾巴：整段重放会绕过"只追加"这条口径（而它正是"停止保留内容"的前提）
    message.appendText(fullTextSoFar.substring(persistedChars));
    messages.save(message);
    return fullTextSoFar.length();
  }

  /**
   * 终态回写（★ 四路径 → 三态，状态取自 {@link StreamTerminationPath#status()} 那一处映射）。
   *
   * <p>★ 三件事的顺序有讲究，而且都必须发生：
   *
   * <ol>
   *   <li>补齐还没落库的正文（停止 / 失败路径上<strong>已生成的内容必须保留</strong>：{@code A5} / MSG-I2）；
   *   <li>绑引用快照（{@code A4} 的载体；★ 只在真的拿到过引用时绑 —— {@code null} 与 {@code []} 是两件事）；
   *   <li>走终态入口（{@code complete} / {@code stop} / {@code fail}）并计 tokens。
   * </ol>
   *
   * <p>★ 失败路径的 {reason, hint} 与 {@code error} 帧<strong>同源</strong>：都由装配器给出 （{@code StreamAssembler}
   * 按路径合成），这里原样收下 —— 若这里再编一句，就会出现 "界面上看到的失败原因"与"库里记的原因"不一致，而它不会报错。
   *
   * @param messageId 助手消息 id
   * @param path 终止路径（四选一）
   * @param fullTextSoFar 完整正文
   * @param persistedChars 已落库字符数
   * @param references 引用快照；{@code null} = 这一批引用从来没到过
   * @param usage 本次问答全部模型调用的用量（可空 = 一次都没报）
   * @param durationMs 从开始生成到收尾的耗时
   * @param failReason 失败原因（{@link StreamTerminationPath#errorPrecedesDone()} 为真时必填）
   * @param failHint 失败后的下一步提示（可空）
   */
  @Override
  @Transactional
  public void finish(
      UUID messageId,
      StreamTerminationPath path,
      String fullTextSoFar,
      int persistedChars,
      List<SourceRef> references,
      TokenUsage usage,
      long durationMs,
      String failReason,
      String failHint) {
    Message message = requireMessage(messageId);
    if (fullTextSoFar.length() > persistedChars) {
      message.appendText(fullTextSoFar.substring(persistedChars));
    }
    message.bindReferences(references);
    // ★★ 会话累计密级的**生产调用点**（#27，2026-10-16）：引用集合自带密级之后，抬升发生在
    //   "引用集合落库的同一个事务里"（本方法 @Transactional）—— 这正是 13 §2.2 / V32 注释写的那个写入点。
    //   ★ references == null 与空列表**不是一回事**（ChatService.java 传的是 `refsBound ? refs : null`）：
    //     null = 这一批引用从来没到过 ⇒ 水位不动；空列表 = 明确的零命中 ⇒ maxOf 也返回 null ⇒ 同样不动。
    //   ★ 只升不降由库侧那条 UPDATE 的 where 保证（Conversation#strictlyLowerThan 是唯一口径），
    //     这里**不做任何比较** —— 在调用方比一次就多一处会漂的口径（V32 注释逐字）。
    conversations.raiseClassification(message.conversationId(), maxClassificationOf(references));
    switch (path) {
      case COMPLETE -> message.complete(usage, durationMs);
      case ABORT -> message.stop(durationMs);
      case DISCONNECT, IDLE_TIMEOUT -> message.fail(failReason, failHint, durationMs);
    }
    messages.save(message);
    log.info(
        "会话 {} 的助手消息 {} 收尾：{}（正文 {} 字，耗时 {}ms）",
        message.conversationId(),
        messageId,
        path.status(),
        message.content().length(),
        durationMs);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * ★★ 重新生成的一轮：**覆盖** {@code targetMessageId} 那条助手消息，不新增消息对。
   *
   * <h3>与 {@link #prepare} 的三处根本差别</h3>
   *
   * <ol>
   *   <li><b>不新建任何消息</b> —— 契约「不新增消息对」；返回的 {@code assistantMessageId} 就是传进来的那个 id；
   *   <li><b>问题从库里取</b> —— {@code REGENERATE} 不带新问句，取<strong>同会话里、 target <b>之前</b>最近的一条 {@code
   *       USER} 消息</strong>；
   *   <li><b>先重置那一行再返回</b> —— {@link Message#restart()}（它会清正文/引用/失败原因/赞踩/用量）。
   * </ol>
   *
   * <h3>★★ 为什么问题必须取"之前最近的一条"</h3>
   *
   * <p>取错的两条路各自都<strong>不会被用户发现</strong>：
   *
   * <ul>
   *   <li>取"会话第一条 USER" ⇒ 多轮里重新生成会答非所问；
   *   <li>取"最后一条 USER" ⇒ 用户追问了下一句之后再重新生成上一轮，同样答非所问。
   * </ul>
   *
   * <p>两种的症状都只是"重新生成答得不对"，而界面上它看起来只是"重生成了一次"。 ⇒ 判据必须造<strong>两轮对话</strong>（问 A、答 A′、问 B、答 B′），对
   * {@code A′} 重新生成， 断言送进模型的问题是 <b>A</b> 而不是 B。
   *
   * @param targetMessageId 要覆盖的那条助手消息
   * @param userId 当前登录用户（★ 归属判定用它，看不见 ⇒ 404）
   * @return 这一轮 + <strong>从库里取出来的问题</strong>（调用方必须用它，不能用请求体里的 question）
   */
  // ★★ @Transactional 不能漏：Assistant.kbIds 是**懒加载集合**，事务一关就抛
  //   LazyInitializationException（实测：真栈上重新生成直接 500，堆栈逐字
  //   Cannot lazily initialize collection of role '...Assistant.kbIds' at
  // ChatTurnWriter.prepareRegenerate）。
  //   ★ 同类的 prepare 有它 —— 这正是两条路径之间最容易漏的那一处差异。
  @Transactional
  public PreparedTurn prepareRegenerate(UUID targetMessageId, UUID userId) {
    Message target =
        messages
            .findById(targetMessageId)
            .orElseThrow(() -> notFound("消息不存在", "它可能已被删除，或者不在你的可见范围内"));
    // ★ 归属的权威是**会话**（消息没有 userId），与 ChatHistoryService / MessageFeedbackService 逐字一致
    Conversation conversation =
        conversations
            .findById(target.conversationId())
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(() -> notFound("消息不存在", "它可能已被删除，或者不在你的可见范围内"));

    // ★ 领域层会拒"不是助手"与"还在生成中"（两条都是 IllegalArgumentException ⇒ controller 转 400）
    Message previous =
        preceding(conversation.id(), target.id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.BAD_REQUEST, "这条回答前面没有用户提问，无法重新生成", "重新生成需要找到这一轮原来的问题"));
    target.restart();
    messages.save(target);

    String traceId = "trace_" + target.id();
    log.info(
        "会话 {} 重新生成：覆盖助手消息 {}（trace {}，原问题取自前一条用户消息 {} 字）",
        conversation.id(),
        target.id(),
        traceId,
        previous.content() == null ? 0 : previous.content().length());
    // ★ 重新生成沿用**会话绑的应用**（不跟请求体漂移）—— 它决定了检索范围
    AppBindingPort.AppBinding app =
        conversation.assistantId() == null ? null : requireVisibleApp(conversation.assistantId());
    return new PreparedTurn(
        conversation.id(),
        target.id(),
        // ★ 用户消息 id 仍是那条**原来的**（本轮没有新用户消息）—— 它的用途是"这一轮问的是什么"
        previous.id(),
        traceId,
        target.modelId() == null ? "" : target.modelId().toString(),
        target.modelId(),
        app == null ? List.of() : app.kbIds(),
        // ★ `#168`：把开关带进这一轮 —— 只有它能让 `ChatService` 决定要不要带历史
        // ★★ 通用问答（app == null）**默认多轮**，而不是"没有应用所以单轮"。
        //
        //   这是个设计错位：`multiTurn` 被做成了**应用**的开关（`as_assistant.multi_turn`），
        //   而通用问答根本没有应用 ⇒ 那个开关对它恒为 false ⇒ 历史永远不读。
        //   实测症状（用户）：「Iberian colonialism」→「翻译一下」，模型答
        //   「我需要先获取你要翻译的内容」—— 它**看不到上一轮**。
        //
        //   ★ "多轮"本质上是**会话**的属性（同一个 conversationId 内前后相关），
        //     不是应用的属性；把默认值翻成 true 是对这个错位的最小修正：
        //     应用那边仍尊重它自己的 `multi_turn` 开关，通用问答则总是带上历史。
        app == null || app.multiTurn(),
        // ★ `#185`：通用问答没有应用 ⇒ 不思考；应用则尊重它自己的开关
        app != null && app.thinkingEnabled(),
        app == null ? null : app.appId(),
        app == null ? null : app.rolePrompt(),
        // ★ `#215`：提示词正文随这一轮进链路（此前只有 rolePrompt 一个来源）
        app == null ? List.of() : app.promptTexts(),
        // ★ `#185`：应用级检索参数；通用问答为 null（ChatService 回落到平台设置）
        app == null ? null : app.retrieval());
  }

  /**
   * "同会话里 target 之前最近的一条 USER 消息"<strong>唯一</strong>的实现。
   *
   * <p>★ 抽成一个方法而不是写两遍：问题与用户消息 id 必须命中<strong>同一条</strong>消息 —— 若两处各算一遍，"问的是 A、记的是 B"这种错会静默发生。
   */
  private Optional<Message> preceding(UUID conversationId, UUID targetId) {
    List<Message> all = messages.listByConversation(conversationId);
    Message userMessage = null;
    for (Message current : all) {
      if (current.id().equals(targetId)) {
        return Optional.ofNullable(userMessage);
      }
      if (current.role() == MessageRole.USER) {
        userMessage = current;
      }
    }
    return Optional.empty();
  }

  /**
   * 取某条用户消息的正文（{@code REGENERATE} 用它把"这一轮问的是什么"读回来）。
   *
   * <p>★ 为什么需要它：{@link #prepareRegenerate} 的返回值是既有的 {@code PreparedTurn}
   * （多处消费，改它会牵动所有调用点），而重新生成的问题是<strong>从库里取的</strong>， 调用方必须拿到它。★ 这一次读与 {@code prepareRegenerate}
   * 内部取问题的判定<strong>同源</strong> （都指向"target 之前最近的一条 USER 消息"的 id），不会分叉。
   *
   * @param userMessageId 用户消息 id（可为 {@code null} ⇒ 返回空串）
   * @return 那条消息的正文；取不到返回空串（★ 不抛：调用方已经过了归属与"有无前问"的校验）
   */
  public String questionOf(UUID userMessageId) {
    if (userMessageId == null) {
      return "";
    }
    return messages.findById(userMessageId).map(Message::content).orElse("");
  }

  /**
   * 取会话或新建一个。
   *
   * <p>★ 传了 {@code conversationId} 就<strong>不再看 appId</strong>：会话的应用在创建时定下来，
   * 让它跟着每一轮请求体漂移会让"这个会话属于哪个应用"变成两处口径（而它决定了检索范围）。
   *
   * <p>★★ <strong>没传 {@code conversationId} 时：续写"最近一条同 (归属人, 应用) 的会话"，找不到才新建</strong> （{@code 13
   * §5.2.1}）。这是公开面（免登录）那条链的**唯一**形状 —— 公开页拿不出一个真的 UUID （它手上的 {@code conversationId} 是合成串 {@code
   * public:<token>}），所以"写进哪条会话"必须由服务端反解；
   * 而"每轮新建一条"的后果是<strong>刷新后永远读不回历史</strong>（A2「刷新不重置」在公开面缺的那一半）。 ★ 它同一条结论也覆盖登录面"通用问答不带 {@code
   * conversationId}"那一档。
   */
  private Conversation resolveOrCreate(
      UUID conversationId, AppBindingPort.AppBinding app, String question, UUID userId) {
    if (conversationId != null) {
      Conversation found =
          conversations
              .findById(conversationId)
              .orElseThrow(() -> notFound("会话不存在", "它可能已被删除，请刷新会话列表后重试"));
      if (!found.userId().equals(userId)) {
        // ★ 与「不存在」同一个出口、同一段文案：403 会告诉对方「这个会话是存在的」
        throw notFound("会话不存在", "它可能已被删除，或者不在你的可见范围内");
      }
      // ★★ 占位标题 + 还没有任何消息 ⇒ 用**本次问题**作标题。
      //
      //   为什么需要这一步：前端「新建会话」会先调 `POST /conversations` 建一条空会话
      //   （标题是占位「新会话」），随后发第一条消息时走的是「会话已存在」这条路 ——
      //   原来这条路**不改标题**，于是标题永远停在占位值。
      //   实测症状：会话有 2 条消息，标题仍是「新会话」，用户无法在列表里认出它。
      //
      //   ★ 只在 `messageCount == 0` 时改：标题一旦由首个问题定下，后续轮次不再覆盖
      //     （否则每问一句标题就变一次，用户会找不到自己的会话）。
      //   ★ 也只在「标题还是占位值」时改：用户手动改过名的会话不许被自动覆盖。
      if (found.messageCount() == 0 && Conversation.PLACEHOLDER_TITLE.equals(found.title())) {
        found.rename(titleOf(question));
        return conversations.save(found);
      }
      return found;
    }
    Optional<Conversation> latest =
        conversations.findLatestByUserAndAssistant(userId, app == null ? null : app.appId());
    if (latest.isPresent()) {
      return latest.get();
    }
    Conversation created =
        new Conversation(
            UUID.randomUUID(), userId, app == null ? null : app.appId(), titleOf(question));
    return conversations.save(created);
  }

  /**
   * 应用存在且可见（看不见 ⇒ 404，与 {@code AccessCredentialService} 同一条口径）。
   *
   * <p>★ 判定<strong>不在这里</strong>：{@link AppBindingPort#findVisibleById} 把"不存在"与"看不见"
   * 合并成同一个空返回（实现里走的是 {@code AccessScopePort#canSee} 那一处 —— {@code 07 §3.3}："归属 + 可见性 +
   * 共享"三条的合成，在这里抄一遍就少一处会跟着改的地方）。
   *
   * <p>★ 所以这里只有一条出口、一段文案：能分辨两者就等于确认了这个 id 的存在性。
   */
  private AppBindingPort.AppBinding requireVisibleApp(UUID appId) {
    return appBindings
        .findVisibleById(appId)
        .orElseThrow(() -> notFound("应用不存在", "它可能已被删除，或者不在你的可见范围内"));
  }

  /**
   * 这一轮用哪个模型：应用绑了就用它，否则挑一个可用的。
   *
   * <p>★ 挑不到 ⇒ 400（不是 500、也不是静默用别的模型）：没有对话模型时这一轮<strong>无法产生答案</strong>。
   *
   * <p>★ 应用的 {@code modelId} 可以是 {@code null}（{@code WORKFLOW} 类型没有应用级模型）—— 那不是"出错"，
   * 而是"另挑一个可用的"这条正常降级输入（与 {@code ChatPort#pickChatModel} 的语义同源）。
   */
  private UUID pickModelId(AppBindingPort.AppBinding app) {
    if (app != null && app.modelId() != null) {
      return app.modelId();
    }
    return chatModels
        .pickChatModel()
        .orElseThrow(
            () ->
                new BusinessException(
                    ErrorCode.BAD_REQUEST, "当前没有可用的对话模型", "请在「模型管理」里加载一个对话模型后重试"));
  }

  /** 取消息；★ 不存在 ⇒ {@code IllegalStateException}（这是链路内部的破洞，不是调用方的输入问题）。 */
  private Message requireMessage(UUID messageId) {
    return messages
        .findById(messageId)
        .orElseThrow(() -> new IllegalStateException("助手消息 " + messageId + " 不见了 —— 本轮开始时它刚被落库"));
  }

  /**
   * ★★ <strong>记一条消息落库</strong>（{@code cv_conversation} 的 {@code message_count} / {@code
   * last_message_at}）。
   *
   * <h3>为什么在这里，而不是在 {@code MessageRepository} 的适配器里"顺手 +1"</h3>
   *
   * <p>消息落库那一处<strong>就是这里</strong>（下方两次 {@code messages.save}），而且本方法本来就在 {@code prepare}
   * 的<strong>同一个事务</strong>里 —— {@code 02 §5.2} 与 {@code #44} 的口径是"写入侧同事务"， 把它塞进 {@code
   * MessageRepositoryAdapter} 只会让"谁说了算"多一个候选（那个类的类注释把这条写死了）。
   *
   * <h3>★★ 一条消息一次调用（而不是"一轮 +2"）</h3>
   *
   * <p>{@code +1} 由"消息真的落库了"这件事驱动：两次 {@code save} 就两次 {@code recordMessage}。 写成"一轮 {@code
   * +2}"是<strong>第二份"一轮等于几条消息"的说法</strong> —— 将来多一条消息（例如重生成的新助手消息） 时它不会报错，只会少记。
   *
   * <h3>★ 为什么 {@code false} 要抛而不是忽略</h3>
   *
   * <p>{@code false} = 那一条 UPDATE 一行都没改 = 会话行不在 —— 而本方法刚刚才建过或读过它。 这是<strong>链路内部的破洞</strong>（与
   * {@link #requireMessage} 同一档），静默忽略的话症状是 "消息在库里、计数停在 0"（{@code #44} 记的正是这个形状）。
   */
  private void recordMessage(Conversation conversation) {
    if (!conversations.recordMessage(conversation.id())) {
      throw new IllegalStateException("会话 " + conversation.id() + " 的消息计数没有落库 —— 它在本轮开始时刚被建过或读过");
    }
  }

  /**
   * ★★ 本轮引用集合的<strong>最高密级</strong>（{@code #27}）—— 会话累计密级的输入。
   *
   * <h3>为什么次序只用 {@link ClassificationLevel#max}</h3>
   *
   * <p>"哪一档更高"只有一处权威：{@link ClassificationLevel} 的<strong>声明顺序</strong>（{@code 13 §2.2}）。 在这里写
   * {@code ordinal()} 比较、或写一个密级次序表，都会多出第二处会漂的口径 —— 而它漂了的症状是 <strong>静默降密</strong>（本项目已实测过一次：PG 的
   * {@code greatest('CONFIDENTIAL','SECRET') = 'SECRET'}， 字典序 ≠ 密级次序）。
   *
   * <h3>★ 三种"没有值"的输入都返回 null（= 水位不动）</h3>
   *
   * <ul>
   *   <li>{@code references == null} —— 这一批引用<strong>从来没到过</strong>（{@code #142} 那种"检索还没回来就收尾"）；
   *   <li>空列表 —— 明确的<strong>零命中</strong>；
   *   <li>元素里出现 {@code null} 密级 —— 今天装不出来（{@code SourceRef} 的构造器非空校验 + 库里那一列非空），
   *       但这里<strong>不抛</strong>：本方法的语义是"能算出多少算多少"，而一条算不出密级的引用不该让整轮落库失败。
   * </ul>
   *
   * <p>★ 语义与 {@code Conversation#raiseClassification(null)} 逐字同源：{@code null} 是"本轮没有引用"，
   * <strong>不是</strong>"本轮引用是公开的"。
   */
  private static ClassificationLevel maxClassificationOf(List<SourceRef> references) {
    if (references == null || references.isEmpty()) {
      return null;
    }
    ClassificationLevel highest = null;
    for (SourceRef ref : references) {
      if (ref != null && ref.classification() != null) {
        highest = highest == null ? ref.classification() : highest.max(ref.classification());
      }
    }
    return highest;
  }

  /** 标题 = 问题前若干字（{@code 02 §2.6}）；截断而不是报错，因为标题不是用户输入的一部分。 */
  private static String titleOf(String question) {
    String trimmed = question == null ? "" : question.strip();
    return trimmed.length() <= TITLE_MAX_CHARS ? trimmed : trimmed.substring(0, TITLE_MAX_CHARS);
  }

  private static BusinessException notFound(String message, String hint) {
    return new BusinessException(ErrorCode.NOT_FOUND, message, hint);
  }
}
