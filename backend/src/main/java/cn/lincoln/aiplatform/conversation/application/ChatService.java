package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamAssembler;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamEvent;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamMeta;
import cn.lincoln.aiplatform.conversation.domain.stream.StreamTerminationPath;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatStreamPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.model.ModelEgressPort;
import cn.lincoln.aiplatform.platform.retrieval.NoRefStrategy;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalParams;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.platform.retrieval.SourceLabels;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 一轮问答的<strong>编排</strong>（第 17 周 · 0.4.5）—— 把契约要的四件事接成真链路： 取会话 → 建消息 → 检索拿引用 → 组装事件 → 落库 → 终态回写。
 *
 * <h3>★★ 这一层<strong>不</strong>实现任何"形状"</h3>
 *
 * <p>四条时序保证各有<strong>唯一</strong>的载体，本层只驱动它们：
 *
 * <table>
 *   <tr><th>保证</th><th>载体（不许有第二份）</th></tr>
 *   <tr><td>{@code meta → refs → delta*}，且 {@code refs} 先于首个 {@code delta}</td>
 *       <td>{@code StreamAssembler} 的<strong>首元素缓冲</strong>（本层在开流前先检索，正是它的前提）</td></tr>
 *   <tr><td>恰好一次 {@code done}；四路径 → 三态</td>
 *       <td>同一个装配器的 {@code finish} + {@code StreamTerminationPath.status()}</td></tr>
 *   <tr><td>三态状态机 / 累积 / 引用快照</td>
 *       <td>{@code Message}（经 {@link ChatTurnWriter} 的短事务）</td></tr>
 *   <tr><td>心跳是注释帧</td><td>{@link ChatStreamWriter}（wire 形状）</td></tr>
 * </table>
 *
 * <p>★ 所以本层的代码看起来"短"是<strong>有意的</strong>：每多写一句形状判断，就多一处会与上面那四处分叉。
 *
 * <h3>★★ 本层必须自己做的三件事（它们不属于任何单一载体）</h3>
 *
 * <ol>
 *   <li><strong>第一个 delta 之前给出引用</strong>：装配器在"还没收到 references"时<strong>抛异常</strong>（刻意的），
 *       所以检索必须在开流前完成；
 *   <li><strong>落库 flush 的节奏</strong>（{@code 03 §1.4}：500ms 或 200 字先到者）：装配器不管落库， 而"刷新不重置"（{@code
 *       A2}）要求的正是"生成中途库里就有那一段"；
 *   <li><strong>停止 / 超时要能打断上游</strong>：检测到就抛，让模型端口停止吐字 （不抛 ⇒ 用户停了、token 还在烧，而且不报错）。
 * </ol>
 *
 * <h3>★★ 四条终止路径各自怎么被观测到（本层最值钱的一段）</h3>
 *
 * <table>
 *   <tr><th>路径</th><th>观测方式</th><th>终态</th></tr>
 *   <tr><td>{@code COMPLETE}</td><td>模型端口回调 {@code onEnd(COMPLETE)}</td><td>{@code GENERATED}</td></tr>
 *   <tr><td>{@code ABORT}</td><td>★ <strong>显式停止信号</strong>（{@link ChatStopSignal}）——
 *       连接断开<strong>不</strong>算停止</td><td>{@code STOPPED}（<strong>不发</strong> error）</td></tr>
 *   <tr><td>{@code DISCONNECT}</td><td>写连接抛 {@code UncheckedIOException}</td><td>{@code FAILED} + error 帧</td></tr>
 *   <tr><td>{@code IDLE_TIMEOUT}</td><td>心跳线程发现"距最后一次写出 ≥ 闲置超时"</td><td>{@code FAILED} + error 帧</td></tr>
 * </table>
 *
 * <p>★★ <strong>为什么"连接断开"不能当 {@code ABORT}</strong>：契约里 {@code ABORT} 的终态是 {@code STOPPED} 且
 * <strong>不发 error</strong>，而 {@code DISCONNECT} 是 {@code FAILED} + error。而前端这一轮的 {@code abort()}
 * 实现是"掐断 fetch"⇒ 服务端只看得到连接没了，<strong>分不出</strong>"用户点了停止"与"网线掉了"。
 * 若按断开判停止，真正的断网会被显示成"已停止"（用户以为是自己停的，而答案其实没写完）。 所以停止必须是<strong>独立于连接</strong>的显式信号 —— {@link
 * ChatStopSignal} 就是那个入口。
 *
 * <p>★★ <strong>判定顺序写死：显式停止优先于连接断开</strong>（{@code #119} 的落点）。两者的到达顺序不受服务端控制 —— "先停止、再断连"必须收 {@code
 * STOPPED}（不是 {@code FAILED}），而"从没收到停止、只是断连"必须收 {@code FAILED}。 判据落在 {@link
 * Run#decideTerminalPath()} 的第一行（收尾那一刻再读一次停止信号）：那之前， 这个顺序<strong>取决于有没有下一个块</strong>，也就是取决于竞态。
 *
 * <p>★ 停止请求从哪来：契约的 {@code stopMessage} （{@code POST
 * /conversations/:conversationId/messages/:messageId/stop}）—— 见 {@link ChatStopService}。
 *
 * <h3>★ 线程模型（三条线，收尾只有一个出口）</h3>
 *
 * <ul>
 *   <li><strong>收尾线程</strong>（本方法的调用线程）：跑检索、启动心跳线程与模型线程、轮询"完事了吗 / 超时了吗"， 并在唯一的那一刻做终态（发 {@code
 *       error}/{@code done} + 落库）；
 *   <li><strong>模型线程</strong>：调 {@link ChatStreamPort#stream}，逐块喂装配器（写出去 + 按阈值 flush）；
 *   <li><strong>心跳线程</strong>：无业务帧时发注释帧；发现闲置超时就<strong>置标志</strong>（收尾仍由收尾线程做）。 ★
 *       它在<strong>检索之前</strong>就起 （{@code #142} ③）：检索期间也必须有心跳，否则"慢检索"在界面上看起来就是卡死。
 * </ul>
 *
 * <p>★ 收尾只有一个出口：{@link Run#terminalTaken}（{@code compareAndSet}）。三条线都可能先到， 先到的那一条收，其余的什么都不做 ——
 * 这是"恰好一次 done"在多线程下的落法 （装配器保证"一条流一次 done"，但那是在单线程假设下；这里多一道闸）。
 */
@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  /**
   * 收尾线程的轮询间隔（毫秒）—— <strong>不是</strong>心跳间隔。
   *
   * <p>★ 它只决定"最早能多快发现该收尾了"，必须显著小于心跳间隔，否则心跳会晚到 （而"晚到多久"取决于一个与业务无关的轮询粒度）。
   */
  private static final long POLL_INTERVAL_MS = 25L;

  /** 候选过采样倍数 —— 过滤（去重 / 缺展示信息）会砍掉几条，按 1× 取会"少给"引用。 */
  private static final int RETRIEVAL_OVERFETCH = 3;

  /**
   * 零引用且应用选择「拒答」时的固定文案（{@code #185}）。
   *
   * <p>★ 它是<strong>产品承诺</strong>（{@code 02 FR-02-19-6} / {@code 10 §5}）：没有资料支持时，用户必须能区分
   * 「检索不到」与「模型自由发挥」。所以这句话是能力的一部分，不交给模型去编。
   */
  static final String NO_REF_REFUSAL =
      "未在已绑定的知识库中检索到与这个问题相关的资料，因此不作回答。" + "可以补充或调整知识库资料，或把「无引用策略」改为「自由回答」后再试。";

  private final ChatTurnStore turns;
  private final SegmentSearchPort segmentSearch;
  private final AccessScopePort accessScope;
  private final ChatStreamPort chatStream;
  private final ChatStopSignal stopSignal;
  private final ChatStreamProperties properties;

  /**
   * 通用会话设置（初始提示词）的读取口。
   *
   * <p>★ 用 <b>setter 可选注入</b>而不是构造器参数：本类有一个 7 参数的测试构造器被大量用例使用，
   * 加必选构造参数会一次改穿所有测试；而这条依赖在测试环境里**确实可以没有** （没有 cv_chat_settings 那一行时行为等同于"不加提示词"，与改造前逐字一致）。
   */
  private ChatPolicyResolver policyResolver;

  /**
   * @param resolver 解析器（可空 = 不加系统提示词，测试环境即如此）
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setPolicyResolver(ChatPolicyResolver resolver) {
    this.policyResolver = resolver;
  }

  /**
   * 向量化端口（{@code #185} 的 VECTOR / HYBRID 检索）。
   *
   * <p>★ 与 {@link #setPolicyResolver} 同一形状的 <b>setter 可选注入</b>：本类的两个构造器被大量测试依赖，
   * 不改构造器参数列表；而这条依赖在测试环境里<strong>确实可以没有</strong>（不设时 VECTOR 模式按"未装配" 抛 {@link
   * IllegalStateException} ⇒ 零引用，绝不静默退回全文）。
   */
  private EmbeddingPort embeddingPort;

  /**
   * @param embeddingPort 向量化端口（可空 = 未装配向量模型，VECTOR 检索不可用）
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setEmbeddingPort(EmbeddingPort embeddingPort) {
    this.embeddingPort = embeddingPort;
  }

  /**
   * 出站闸门（{@code #72}）—— ★ 它与 {@link AccessScopePort} 上的密级<strong>不是一回事</strong>：
   * 后者回答"我能读哪些"（谁能看），本端口回答"读到的东西允许发到这个模型吗"（能发到哪）。 两者<strong>正交</strong>：一个高密级分段可以合法地被本地模型读，但不得被发去外部
   * API。
   */
  private final ModelEgressPort modelEgress;

  /**
   * 多轮上下文的硬上限（条数，含用户与助手）。
   *
   * <p>★ 20 条 ≈ 10 轮问答，与 ROADMAP §2.2 A6 的「10 轮」对齐。 ★ 窗口口径的**最终定稿在 #65**（按轮数还是 token 预算）；这里是保守实现，
   * 目的只是让"上下文无限长"不把首字延迟拖垮。定稿后应改成按 token 预算。
   */
  private static final int MAX_HISTORY_MESSAGES = 20;

  /**
   * 生产装配用的构造器。
   *
   * <p>★ 显式 {@code @Autowired}：本类有<strong>两个</strong>构造器（下面那个给脱离容器的测试用）， 而"两个构造器且都不标注"时 Spring
   * 的行为是"要么找无参、要么失败"—— 那个失败的报错是 {@code No default constructor found}，与真实原因（"该选哪个"）看起来毫无关系
   * （本批实测踩到过）。
   */
  @Autowired
  public ChatService(
      ChatTurnStore turns,
      SegmentSearchPort segmentSearch,
      AccessScopePort accessScope,
      ChatStreamPort chatStream,
      ChatStopSignal stopSignal,
      ChatStreamProperties properties,
      ModelEgressPort modelEgress) {
    this.turns = turns;
    this.segmentSearch = segmentSearch;
    this.accessScope = accessScope;
    this.chatStream = chatStream;
    this.stopSignal = stopSignal;
    this.properties = properties;
    this.modelEgress = modelEgress;
  }

  /**
   * 用契约默认时序构造（{@link ChatStreamProperties#defaults()}）。
   *
   * <p>★ 给"脱离容器"的测试用：本类的判据里有两条<strong>不需要数据库</strong> （断开路径的两帧顺序、心跳与闲置超时的时序），而它们要的是一个真跑的 {@code
   * ChatService}， 不是一堆 mock。少一个参数就少一处要造的东西。
   *
   * <p>★★ 出站闸门的那一档（{@code #72}）在这里<strong>不能用"恒放行"兜底</strong> —— 那会让"闸门没接"与"闸门放行"读数一模一样（{@code
   * silent-failure} 的形状）。所以本构造器 <strong>要求调用方显式给出</strong> {@link ModelEgressPort}：测试要么给一个明确的
   * "都允许"（那是一次有意的声明），要么给一个具体的上限。
   */
  public ChatService(
      ChatTurnStore turns,
      SegmentSearchPort segmentSearch,
      AccessScopePort accessScope,
      ChatStreamPort chatStream,
      ChatStopSignal stopSignal,
      ModelEgressPort modelEgress) {
    this(
        turns,
        segmentSearch,
        accessScope,
        chatStream,
        stopSignal,
        ChatStreamProperties.defaults(),
        modelEgress);
  }

  /**
   * 一轮问答的入参（契约 {@code ChatStreamOptions} 的服务端那一半）。
   *
   * <p>★ 刻意<strong>不</strong>含 {@code attachmentIds} / {@code thinking} / {@code mode} / {@code
   * targetMessageId}： 契约里有它们，但本批不落（附件 / 重生成 / 继续生成各有自己的批次），收进来不用就是一个"看起来支持了"的假象。
   *
   * @param appId 应用 id（可空 = 通用问答）
   * @param conversationId 会话 id（可空 = 新建）
   * @param question 提问（非空）
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取）
   */
  public record ChatRequest(UUID appId, UUID conversationId, String question, UUID userId) {}

  /**
   * 一轮的启动结果：<strong>落库读数 + 真正要问的问题</strong>。
   *
   * <p>★★ 为什么必须把 {@code question} 一起返回：{@link ChatMode#REGENERATE} 的问题
   * <strong>来自库里</strong>（那一条回答之前最近的用户消息），请求体里的 {@code question}
   * 不表达任何新意图。若调用方仍用请求体那个，重新生成会用一个无关的问题去答 —— 而界面上它看起来只是"重生成了一次"。
   *
   * @param turn 落库读数（{@code REGENERATE} 时 {@code assistantMessageId} 是<strong>被覆盖的那条</strong>）
   * @param question 送进模型的问题（★ 调用方必须用它）
   */
  public record StartedTurn(ChatTurnWriter.PreparedTurn turn, String question) {}

  /**
   * 按<strong>模式</strong>启动一轮（契约 {@code mode} + {@code targetMessageId}）。
   *
   * <p>★ 这是 {@link #startTurn} 的超集：{@code NEW}/{@code null} 走原来那条路， 读数与行为<strong>一个字节都不变</strong>。
   *
   * @param request 一轮的入参
   * @param mode 生成模式（{@code null} ⇒ {@code NEW}）
   * @param targetMessageId 重生成的目标助手消息（{@code REGENERATE} 必填）
   * @return 落库读数 + 真正要问的问题
   * @throws cn.lincoln.aiplatform.platform.domain.BusinessException {@code CONTINUE} 未实现（400）·
   *     {@code REGENERATE} 缺 {@code targetMessageId}（400）· 目标消息看不见（404）
   */
  public StartedTurn startTurn(ChatRequest request, ChatMode mode, UUID targetMessageId) {
    ChatMode effective = mode == null ? ChatMode.NEW : mode;
    if (effective == ChatMode.CONTINUE) {
      // ★★ 显式拒绝，不静默当 NEW：静默降级会**新增一条消息**，与契约的"接着已有内容续写"直接矛盾，
      //   而界面上它看起来完全正常（多出一问一答）。见 ChatMode 的类注释。
      throw new BusinessException(ErrorCode.BAD_REQUEST, "继续生成（CONTINUE）尚未实现", "本版请使用普通提问或重新生成");
    }
    if (effective == ChatMode.NEW) {
      ChatTurnWriter.PreparedTurn turn = startTurn(request);
      return new StartedTurn(turn, request.question().strip());
    }
    if (targetMessageId == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "重新生成必须给出 targetMessageId", "请指明要重新生成哪一条回答");
    }
    ChatTurnWriter.PreparedTurn turn = turns.prepareRegenerate(targetMessageId, request.userId());
    // ★ 问题**从库里取**（那一条回答之前最近的用户消息），不能用请求体里的
    return new StartedTurn(turn, regenerateQuestionOf(turn));
  }

  /**
   * 取"重新生成时要问的问题" —— 由 {@link ChatTurnWriter#prepareRegenerate} 已经确定， 这里只负责把它从落库读数里读出来。
   *
   * <p>★ 为什么要绕一次、而不是让 {@code prepareRegenerate} 直接返回 {@code (turn, question)}： 那个方法的返回值是 {@code
   * PreparedTurn}（既有类型，多处消费），改它会牵动所有调用点； 而本类是唯一需要这句话的地方。★ 代价是**多一次库读**，但那一次读与 {@code
   * prepareRegenerate} 内部的判定走的是同一个方法（{@code preceding}），不会分叉。
   */
  private String regenerateQuestionOf(ChatTurnWriter.PreparedTurn turn) {
    return turns.questionOf(turn.userMessageId());
  }

  /**
   * 事件出口。
   *
   * <p>★ 叫 {@code onEvent} 而不是 {@code write}：生产实现是"写帧 + flush"，测试实现是"记下一条"。
   * 本层只保证"每一条事件都经过这里一次、且顺序就是装配顺序"。
   */
  @FunctionalInterface
  public interface EventSink {

    /**
     * 消费一条事件（含心跳）。
     *
     * @param event 装配器产出的事件（顺序即 wire 顺序）
     * @throws UncheckedIOException 连接已断开 —— {@code DISCONNECT} 唯一的观测方式
     */
    void onEvent(StreamEvent event);
  }

  /**
   * 开始一轮（取会话 / 建消息 / 定模型；看不见 ⇒ 404）。
   *
   * @throws BusinessException 会话或应用看不见（{@code 404}）· 没有可用对话模型（{@code 400}）· 空提问（{@code 400}）
   */
  public ChatTurnWriter.PreparedTurn startTurn(ChatRequest request) {
    if (request.question() == null || request.question().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空", "请输入你想问的内容");
    }
    return turns.prepare(
        request.conversationId(), request.appId(), request.question().strip(), request.userId());
  }

  /**
   * 应用调试的一轮（{@code #184}）—— <strong>不落任何正式会话记录</strong>。
   *
   * <h3>★★ 为什么它必须走同一套编排</h3>
   *
   * <p>{@code BR-02-08}：调试结果只有在"与正式问答同配置"时才可信。所以这里不新写一条问答链，而是：
   *
   * <ol>
   *   <li>由 {@link ChatTurnStore#prepareDebug} 走与正式轮<strong>同一段</strong>应用映射（模型 / 知识库 / 角色 / 检索参数）；
   *   <li>复用同一个 {@link #retrieve}（阈值 / TopN / 无引用策略与线上同源）；
   *   <li>复用同一个 {@link #promptOf} 与 {@link ChatStreamPort}。
   * </ol>
   *
   * <h3>★★ 它<strong>不</strong>写 {@code cv_conversation} / {@code cv_message}</h3>
   *
   * <p>整条路径没有一次 {@code prepare} / {@code flush} / {@code finish}：{@code prepareDebug} 只读应用，
   * 检索是只读，模型端口不碰库。判据见 {@code AssistantDebugHttpTest}：调试前后两张表的行数不变。
   *
   * @param appId 应用 id（看不见 ⇒ 404，与正式轮同一条口径）
   * @param question 调试问题（非空）
   * @return 答案 + 引用 + 耗时 + 用量（{@code tokens = null} 表示上游没有上报，不是 0）
   * @throws BusinessException 应用不存在 / 不可见（404）· 空问题（400）· 上游失败（502）
   */
  public DebugAnswer debugTurn(UUID appId, String question) {
    if (question == null || question.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "调试问题不能为空", "请输入要调试的问题");
    }
    String trimmed = question.strip();
    long startedAt = System.nanoTime();
    ChatTurnWriter.PreparedTurn turn = turns.prepareDebug(appId, trimmed);
    RetrievalParams retrieval = effectiveRetrieval(turn);
    List<SourceRef> refs = retrieve(trimmed, turn.kbIds(), null, turn.modelId(), retrieval);

    if (refuses(refs, retrieval)) {
      log.info("应用 {} 的调试命中零引用且策略为 REFUSE ⇒ 直接回拒答文案", appId);
      return new DebugAnswer(NO_REF_REFUSAL, refs, elapsedMs(startedAt), null);
    }

    String prompt = promptOf(trimmed, refs, systemPromptOrNull(turn));
    DebugCollector collector = new DebugCollector();
    try {
      chatStream.stream(
          turn.modelId(),
          prompt,
          turn.thinkingEnabled(),
          Duration.ofMillis(properties.idleTimeoutMs()),
          collector);
    } catch (RuntimeException failure) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调试生成失败：" + failure.getMessage(),
          "检查模型是否已加载、上游是否可达；本次调试没有写任何会话记录");
    }
    if (collector.failed) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调试生成失败：" + collector.detail,
          "检查模型是否已加载、上游是否可达；本次调试没有写任何会话记录");
    }
    return new DebugAnswer(
        collector.text.toString(), refs, elapsedMs(startedAt), collector.usageTotalOrNull());
  }

  /**
   * 调试结果（{@code POST /assistants/:id/debug} 的响应体）。
   *
   * @param tokens ★ {@code null} = 上游没有上报用量（{@code #142} 之前的生产实现就是这种），<strong>不是 0</strong>
   */
  public record DebugAnswer(String answer, List<SourceRef> refs, long elapsedMs, Integer tokens) {}

  /** 零引用时是否应当拒答 —— <strong>唯一</strong>的判定（流式与调试两条路都读它）。 */
  private static boolean refuses(List<SourceRef> refs, RetrievalParams retrieval) {
    return refs.isEmpty() && retrieval.noRefStrategy() == NoRefStrategy.REFUSE;
  }

  /**
   * 这一轮真正生效的检索参数 —— {@code #185} 的"应用值 vs 平台值"<strong>唯一</strong>选择点。
   *
   * <p>★ 应用有绑定就用应用的（即使值是默认值）；通用问答（应用为 null）才回落平台设置。 不要在三处各判一次（{@code ChatService} / {@code
   * ChatTurnWriter} / 适配器）—— 那正是口径会漂的形状。
   */
  private RetrievalParams effectiveRetrieval(ChatTurnWriter.PreparedTurn turn) {
    if (turn.retrieval() != null) {
      return turn.retrieval();
    }
    if (policyResolver == null) {
      return RetrievalParams.platformDefaults();
    }
    return new RetrievalParams(
        // ★ 平台设置没有"检索模式"这一格（它只属于应用 / 知识库）⇒ 通用问答固定全文。
        RetrievalMode.FULLTEXT,
        policyResolver.retrievalThreshold(),
        policyResolver.retrievalTopN(),
        // ★ 通用问答没有"无引用策略"这一格 ⇒ 保持既有行为（自由回答）。
        NoRefStrategy.FREE_ANSWER);
  }

  /**
   * 把这一轮跑到收尾 —— <strong>阻塞</strong>直到 {@code done} 已经交给 {@code sink}。
   *
   * <p>★ 失败路径（断开 / 超时 / 上游报错）<strong>都是正常结局</strong>（各自有终态），所以本方法不抛； 只有"流还没开始"的问题（会话看不见、没有模型）才从
   * {@link #startTurn} 抛出去。
   *
   * @param turn 落库读数
   * @param question 用户提问（进提示词）
   * @param sink 事件出口（断线时由它抛异常）
   */
  public void streamTurn(ChatTurnWriter.PreparedTurn turn, String question, EventSink sink) {
    streamTurnWithTerminalGate(turn, question, sink, TerminalGate.NONE);
  }

  /**
   * "第二个收尾入口"的把手（生产装配传 {@link TerminalGate#NONE}）。
   *
   * <p>★★ 它<strong>不是</strong>一个测试开关：本方法<strong>不读</strong>任何标志、<strong>不判</strong>任何模式 ——
   * 它只是把"收尾入口"这个已经存在的东西<strong>交出去</strong>。交出去是必须的，因为"恰好一次终态"这道守卫 （{@link
   * Run#terminalTaken}）防的是<strong>第二个调用方</strong>，而在收尾只由 {@code awaitTerminal}
   * 调一次的今天，那个调用方在<strong>当前可达路径上不存在</strong> ⇒ 那道守卫无法被证伪。
   *
   * <p>★ 因此这里是"给链路开一个可控的测试出口"的落点，开的形状是<strong>依赖替换</strong>： 生产装配走的 {@link #streamTurn} 传的是 {@link
   * TerminalGate#NONE}（什么都不做）， 而测试可以传一个"把重入那一刻接住"的实现，在<strong>同一个 {@code Run}</strong> 上再收一次尾 ——
   * 于是被验的是生产里那道 CAS，不是测试自己模拟的一道闸。
   *
   * <p>★ 生产代码里<strong>没有</strong>一行 {@code if (testMode)}：本方法无论谁调，走的都是同一条收尾路径。
   *
   * @param turn 落库读数
   * @param question 用户提问（进提示词）
   * @param sink 事件出口（断线时由它抛异常）
   * @param terminalGate 收尾入口的把手（生产传 {@link TerminalGate#NONE}）
   */
  void streamTurnWithTerminalGate(
      ChatTurnWriter.PreparedTurn turn,
      String question,
      EventSink sink,
      TerminalGate terminalGate) {
    long startedAtNanos = System.nanoTime();
    StreamAssembler assembler =
        new StreamAssembler(
            turn.assistantMessageId().toString(),
            new StreamMeta(
                turn.assistantMessageId().toString(), turn.traceId(), turn.modelLabel()));
    Run run = new Run(assembler, turn.assistantMessageId(), startedAtNanos, sink);
    // ★ 收尾入口在这里交出去（它只指向同一个 Run 的收尾方法，不含任何判据）
    terminalGate.onSecondTerminal(() -> finishTurn(turn, run, run.pathOf(), upstreamFailed(run)));
    // ★ meta 先发：前端拿到 messageId 才谈得上"生成中停止"（契约注释原文）
    assembler.begin();
    run.pump();

    // ★★ 检索必须在**调用线程**上跑，不能放进模型线程（第 17 周修掉的一个静默失效）
    //
    //   `retrieve` 读 `accessScope.currentScope().maxClassification()` 当密级上限，而
    //   `currentScope()` 的两个来源**都是 ThreadLocal**：
    //     · `AccessScopeAdapter.DELEGATED`（委派范围：公开面的冻结密级）
    //     · `SecurityContextHolder`（登录面的 JWT）
    //   把它们挪到模型线程上，两条都读不到 ⇒ `nobody()` ⇒ `ceiling == null` ⇒ `retrieve`
    //   走"取不到身份（fail-closed）：一条都不给"那条分支 ⇒ **引用永远为空**。
    //
    //   ★ 症状极具误导性（本项目头号病）：模型照常回答、界面照常显示答案，**只是引用栏永远空着** ——
    //     看起来像"这个知识库里确实没有相关内容"，而真相是"身份没跟过来"。它不会报任何错。
    //   ★ 为什么放在 meta 之后：`meta` 是"能停止"的前提（前端要它的 messageId），别让一次检索拖慢它。
    //   ★ 为什么放在起线程之前：装配器要求"引用先于第一个 delta"，而这里是**唯一**能读到正确范围的地方。
    //
    // ★★ #142 ③：**心跳线程必须在检索开始之前就起**（此前它在 retrieve 之后才起 ⇒ 检索期间一个字节都不发）。
    //
    //   检索可能很慢（长文档 / 多跳），而那段时间里既没有业务帧、也没有心跳帧 —— 前端的闲置计时一路涨到
    //   阈值，用户看到的是"卡死"，服务端却什么都不知道（这条链路上的检索**不走** RetrievalService 的
    //   5000ms 预算）。所以心跳的覆盖面必须是"这一轮从 meta 发出到 done 为止"，而不是"生成那一段"。
    //
    //   ★ 超时口径**没有**因此改变（这一点值得写清楚，免得下一个人以为这里动过语义）：
    //     闲置超时的时钟是 `lastBusinessFrameAtNanos`，它在 `Run` 构造时就起算 ⇒ **检索耗时早就在这个预算里**
    //     （修之前，检索超过 idleTimeout 的那一轮会在心跳线程起来后的第一圈就被判超时）。本行移动只改变了
    //     "在检索期间就发现超时"还是"检索回来才发现"——两者收尾都是 IDLE_TIMEOUT，都拿不到模型的一个字。
    //     而"检索要不要有自己的预算"是另一件事（`error.reason` 今天仍借"生成超时"那句文案），见交付报告。
    Thread heartbeat = startHeartbeatThread(run);
    RetrievalParams retrieval = effectiveRetrieval(turn);
    List<SourceRef> refs =
        retrieve(question, turn.kbIds(), turn.conversationId(), turn.modelId(), retrieval);
    run.assembler.references(refs);
    run.bindRefs(refs);
    run.pump();

    // ★★ `#185`：无引用策略的**生产消费点**。
    //
    //   此前 `assistant.retrieval_no_ref_strategy` 只在实体 / 视图 / 导出里出现，
    //   问答链路零消费 ⇒ 用户选了「拒答」也照样自由回答（而且不会报错）。
    //
    //   ★ 拒答**不调用模型**：既省一次调用，也让"零引用 ⇒ 固定文案"这条承诺不被模型改写。
    //   ★ 正文仍走 delta（`refs` 已经交进装配器，所以顺序 meta → refs → delta → done 不变）。
    Thread model = null;
    if (refs.isEmpty() && retrieval.noRefStrategy() == NoRefStrategy.REFUSE) {
      log.info(
          "应用 {} 的无引用策略是 REFUSE，本轮零引用 ⇒ 直接回拒答文案，不调用模型（会话 {}）", turn.appId(), turn.conversationId());
      try {
        run.assembler.delta(NO_REF_REFUSAL);
        run.pump();
      } catch (UncheckedIOException disconnected) {
        // ★ 与模型线程同一条处置：写不出去 ⇒ 交给收尾线程按 DISCONNECT 收，不要在这里抛穿
        run.disconnected = true;
        log.info("拒答文案写不出去（连接已断）：{}", disconnected.getMessage());
      }
      run.modelDone.set(true);
    } else {
      model = startModelThread(turn, question, refs, run);
    }
    awaitTerminal(turn, run);
    heartbeat.interrupt();
    if (model != null) {
      model.interrupt();
    }
  }

  /**
   * "第二个收尾入口"的递交点。
   *
   * <p>★ 它存在<strong>只为一件事</strong>：让 {@link Run#terminalTaken}（"终态只取一次"）在今天就可证伪 —— 把"同一个 {@code
   * Run} 上再收一次尾"这件事交给调用方，而生产装配给的 {@link #NONE} 什么都不做。于是"加了这个接口"不改变生产的任何一行行为。
   *
   * <p>★ 刻意<strong>不</strong>做成"可注入的依赖"（那要在生产构造器上多一个参数，而它没有任何生产语义）： 这里多出来的只是一个方法重载 +
   * 一个空实现，判据一个都没改弱。
   */
  public interface TerminalGate {

    /** 生产装配用的空实现（{@code streamTurn} 传的就是它）。 */
    TerminalGate NONE = finishAgain -> {};

    /**
     * 收下"在同一个 {@code Run} 上再收一次尾"的入口。
     *
     * @param finishAgain 第二次收尾 —— ★ 它走的是生产里同一个 {@link #finishTurn}（含那道 CAS 守卫）
     */
    void onSecondTerminal(Runnable finishAgain);
  }

  /** 这一轮的上游是不是以失败收场（收尾判据里与路径正交的那一半）。 */
  private static boolean upstreamFailed(Run run) {
    return run.upstream.get() == ChatStreamPort.End.FAILED;
  }

  // ────────────────────────── 三条线程 ──────────────────────────

  /**
   * 模型线程：把引用交给装配器，再调模型端口逐块喂给它。
   *
   * <p>★ 它<strong>不做终态</strong>：终态只由 {@link #awaitTerminal} 出（唯一出口）。
   *
   * <p>★★ 它<strong>不</strong>跑检索（第 17 周改）：检索读的是 {@code accessScope.currentScope()}
   * 的密级上限，而范围与安全上下文都是 {@code ThreadLocal} —— 在本线程（新建的模型线程）上两者皆空 ⇒ {@code nobody()} ⇒ 引用恒为空。检索由调用方在
   * **能读到范围的那条线程** 上跑完再传进来。
   *
   * @param refs 这一轮的引用（★ 调用方已按正确范围检索过）
   */
  private Thread startModelThread(
      ChatTurnWriter.PreparedTurn turn, String question, List<SourceRef> refs, Run run) {
    // ★★ `#168`：历史在**本方法（调用方线程）**里读好再传进去。
    //   理由与同文件检索那条注释逐字相同：访问范围是 ThreadLocal，新建的模型线程上它是空的。
    //   读历史本身只按 conversationId 取（归属在 prepare 阶段已经判过），不依赖范围。
    List<cn.lincoln.aiplatform.platform.model.ChatMessage> history = List.of();
    // ★ 历史条数上限来自「对话设置」（此前是硬编码常量 MAX_HISTORY_MESSAGES = 20）。
    //   它决定上下文长度、推理成本与跨话题串味风险，应该能在页面上调。
    //   ★ 读不到配置时退回常量默认值（与改造前逐字一致）。
    final int historyLimit =
        policyResolver == null ? MAX_HISTORY_MESSAGES : policyResolver.historyMaxMessages();
    if (turn.multiTurn()) {
      List<cn.lincoln.aiplatform.platform.model.ChatMessage> previous =
          turns.historyBefore(turn.conversationId(), turn.userMessageId(), historyLimit);
      if (!previous.isEmpty()) {
        List<cn.lincoln.aiplatform.platform.model.ChatMessage> withCurrent =
            new java.util.ArrayList<>(previous);
        // ★ 本轮问题带上引用，与单轮那条路的 `promptOf` 逐字同源 —— 两条路不能各拼各的
        // ★ 初始提示词同样要带：否则「第 1 轮有口径、第 2 轮起消失」，模型行为会在中途变
        withCurrent.add(
            new cn.lincoln.aiplatform.platform.model.ChatMessage(
                cn.lincoln.aiplatform.platform.model.ChatMessage.Role.USER,
                promptOf(question, refs, systemPromptOrNull(turn))));
        history = List.copyOf(withCurrent);
      }
    }
    final List<cn.lincoln.aiplatform.platform.model.ChatMessage> messages = history;
    // ★ 每轮一行：这一轮到底带上了几条历史。
    //   ★ 它此前恒为 0 且**没有任何痕迹** ——「多轮不生效」在界面上只表现为
    //     "模型答非所问"，不查这条日志根本看不出是历史没带（这次就是靠它定位的）。
    // ★ 措辞要写清"含本轮"：historyLimit 管的是**历史**条数，而 messages 里还有本轮问题，
    //   直接打两个数会让人以为"上限 2 却带了 3 条"是 bug。
    log.info(
        "本轮带 {} 条消息（= 历史 {} + 本轮问题；历史上限 {}，会话 {}）",
        messages.size(),
        // ★ 第一轮走单轮路径：messages 为空（本轮问题不在里面），减 1 会打出"历史 -1"
        Math.max(0, messages.size() - 1),
        historyLimit,
        turn.conversationId());
    Thread thread =
        new Thread(
            () -> {
              try {
                if (messages.isEmpty()) {
                  chatStream.stream(
                      turn.modelId(),
                      promptOf(question, refs, systemPromptOrNull(turn)),
                      turn.thinkingEnabled(),
                      Duration.ofMillis(properties.idleTimeoutMs()),
                      run);
                } else {
                  chatStream.streamConversation(
                      turn.modelId(),
                      messages,
                      turn.thinkingEnabled(),
                      Duration.ofMillis(properties.idleTimeoutMs()),
                      run);
                }
              } catch (StopUpstream cancel) {
                // 用户停止 / 已判超时：不是错，终态由收尾线程按标志决定
                log.debug("上游已被打断（停止或超时），模型线程退出");
              } catch (UncheckedIOException disconnected) {
                run.disconnected = true;
                run.upstream.set(ChatStreamPort.End.FAILED);
                log.info(
                    "会话 {} 的助手消息 {} 连接断开（{}）",
                    turn.conversationId(),
                    turn.assistantMessageId(),
                    disconnected.getMessage());
              } catch (RuntimeException unexpected) {
                // ★ 上游抛出的任何异常都按"这一路模型调用失败"处置（不是 500）：它有终态（FAILED）
                run.upstream.set(ChatStreamPort.End.FAILED);
                run.lastFailReason = unexpected.getMessage();
                log.warn("模型线程异常（按上游失败收尾）", unexpected);
              } finally {
                run.modelDone.set(true);
              }
            },
            "chat-model-" + turn.assistantMessageId());
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /**
   * 心跳线程：无业务帧时按契约节奏发 {@code : keep-alive} 注释帧；发现闲置超时就<strong>置标志</strong>。
   *
   * <p>★★ 两个判据都用<strong>时间差</strong>：
   *
   * <ul>
   *   <li>心跳：距上次<strong>心跳</strong> ≥ 心跳间隔 —— ★ 不能用"距上次写出"，那样业务帧密集时会攒出一次假心跳；
   *   <li>超时：距上次<strong>写出</strong>（业务帧与心跳都算活动） ≥ 闲置超时 —— 与前端"一帧都没收到即判吊死"同口径。
   * </ul>
   */
  /**
   * 心跳线程：无业务帧时按契约节奏发 {@code : keep-alive} 注释帧；发现闲置超时就<strong>置标志</strong>。
   *
   * <p>★★ <strong>两个判据各自看自己的时钟</strong>（本批实测踩到过，症状极具迷惑性）：
   *
   * <ul>
   *   <li><strong>心跳</strong>看"距上次<strong>心跳</strong>" ≥ 心跳间隔 —— 用"距上次写出"的话， 业务帧密集时会一路推迟心跳；
   *   <li><strong>闲置超时</strong>看"距上次<strong>业务帧</strong>" ≥ 闲置超时 —— ★ <strong>心跳不算活动</strong>。
   * </ul>
   *
   * <p>★★ 为什么"心跳不算活动"是必须的（踩到的正是这一条）：若心跳也刷新超时时钟， 那么"上游一直不吐字、只有心跳在发"这条路径<strong>永远不会超时</strong> ——
   * 心跳每 300ms 刷一次， 而超时窗口是 1200ms，于是那一轮会一直挂着直到上游自己返回。这正是"心跳把超时机制喂死了"，
   * 而且它<strong>看起来完全正常</strong>（连接是活的、心跳在发）。契约把心跳定义为"证明连接仍活着"，
   * 它证明的是<strong>链路</strong>活着，不是<strong>生成</strong>在推进。
   */
  private Thread startHeartbeatThread(Run run) {
    Thread thread =
        new Thread(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted() && !run.modelDone.get()) {
                  Thread.sleep(POLL_INTERVAL_MS);
                  long now = System.nanoTime();
                  if ((now - run.lastBusinessFrameAtNanos) / 1_000_000
                      >= properties.idleTimeoutMs()) {
                    run.timedOut = true;
                    run.upstreamStopped.set(true);
                    return;
                  }
                  if ((now - run.lastHeartbeatAtNanos) / 1_000_000
                      >= properties.heartbeatIntervalMs()) {
                    run.heartbeat();
                  }
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } catch (UncheckedIOException disconnected) {
                // 心跳写不出去 ⇒ 连接没了：交给收尾线程按 DISCONNECT 收（它才能落库）
                log.info("心跳帧写不出去（连接已断）：{}", disconnected.getMessage());
                run.disconnected = true;
                run.upstreamStopped.set(true);
              } catch (RuntimeException unexpected) {
                log.warn("心跳线程异常退出（本轮生成不受影响）", unexpected);
              }
            },
            "chat-heartbeat-" + run.messageId);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /**
   * 收尾线程（= 调用线程）：等"模型完事 / 超时 / 连接断"，然后<strong>唯一一次</strong>收尾。
   *
   * <p>★ 主循环把心跳线程置的 {@code timedOut} 变成真正的收尾 —— 心跳线程只置标志、不动装配器， 于是"谁产出 done"永远只有一个线程。
   */
  private void awaitTerminal(ChatTurnWriter.PreparedTurn turn, Run run) {
    while (!(run.timedOut || run.disconnected || run.modelDone.get())) {
      sleepQuietly();
    }
    if (run.timedOut) {
      run.upstreamStopped.set(true);
    }
    finishTurn(turn, run, run.pathOf(), upstreamFailed(run));
  }

  private static void sleepQuietly() {
    try {
      Thread.sleep(POLL_INTERVAL_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // ────────────────────────── 收尾（唯一出口）──────────────────────────

  /**
   * 终态：先把 {@code error}（失败路径才有）与 {@code done} 交给出口，再把这轮的结果落库。
   *
   * <p>★ <strong>先发后落库</strong>：发不出去（连接已断）时 {@code done} 会抛， 所以落库必须在一个 {@code finally} 里兜住 ——
   * 否则库里那条会停在 {@code GENERATING}（界面永远转圈）。
   */
  private void finishTurn(
      ChatTurnWriter.PreparedTurn turn,
      Run run,
      StreamTerminationPath path,
      boolean upstreamFailed) {
    if (!run.terminalTaken.compareAndSet(false, true)) {
      // 已经有人收过尾（多条线竞争）⇒ 什么都不做。"恰好一次"就落在这一行。
      return;
    }
    long durationMs = elapsedMs(run.startedAtNanos);
    try {
      // ★ error 帧与 done 帧都由装配器产出（它按路径合成 reason/hint）——
      //   本层不自己编文案，否则"界面上看到的原因"与"库里记的原因"会分叉
      run.assembler.finish(path, durationMs);
      run.pump();
    } catch (UncheckedIOException gone) {
      log.info("终态帧写不出去（连接已断）：{} —— 库里仍会落终态", gone.getMessage());
    } finally {
      persistTerminal(turn, run, path, durationMs, upstreamFailed);
    }
  }

  /** 落库终态（状态 + 已生成文本 + 引用快照 + tokens）。 */
  private void persistTerminal(
      ChatTurnWriter.PreparedTurn turn,
      Run run,
      StreamTerminationPath path,
      long durationMs,
      boolean upstreamFailed) {
    StreamEvent.StreamDoneEvent done = run.assembler.done();
    if (done == null) {
      // 理论上到不了（finish 一定产出 done）；真到了说明终态出口被绕过了 —— 留痕而不是静默
      throw new IllegalStateException("收尾时没有 done 事件 —— 终态出口只能有一个（StreamAssembler#finish）");
    }
    turns.finish(
        turn.assistantMessageId(),
        path,
        run.assembler.text(),
        run.persistedChars,
        run.refsBound ? run.refs : null,
        done.tokens(),
        durationMs,
        failReasonFor(path, upstreamFailed ? run.lastFailReason : null),
        failHintFor(path));
    log.info(
        "会话 {} 的助手消息 {} 收尾：{}（正文 {} 字，tokens {}，耗时 {}ms）",
        turn.conversationId(),
        turn.assistantMessageId(),
        done.status(),
        run.assembler.text().length(),
        done.tokens().total(),
        durationMs);
  }

  /** 失败原因：★ 与装配器发出去的 {@code error.reason} 同源（由它按路径合成）。 */
  private static String failReasonFor(StreamTerminationPath path, String upstreamDetail) {
    if (!path.errorPrecedesDone()) {
      return null;
    }
    if (path == StreamTerminationPath.IDLE_TIMEOUT) {
      return "生成超时";
    }
    // DISCONNECT 的两种来源：上游报错（有 detail）与连接断开（写失败，没有 detail）
    return upstreamDetail == null ? "连接中断" : "生成失败：" + upstreamDetail;
  }

  /** 失败提示：与契约的 {@code error.hint} 同一条口径。 */
  private static String failHintFor(StreamTerminationPath path) {
    return switch (path) {
      case COMPLETE, ABORT -> null;
      case IDLE_TIMEOUT -> "可稍后重试；若持续超时请联系管理员";
      case DISCONNECT -> "网络或服务异常，可重试";
    };
  }

  // ────────────────────────── 检索与提示词 ──────────────────────────

  /**
   * 这一轮的引用（★ 在开流前跑完）。
   *
   * <p>三步都是既有的端口调用，本层不自己写 SQL：
   *
   * <ol>
   *   <li>{@link SegmentSearchPort#retrievalScope} —— ★★ <strong>域的解析与装配唯一的那一处</strong>（{@code 13
   *       §11.3} 三支的绑定值）。它里面含可见性判定（应用绑的库可能被停用 / 软删 / 取消可见，所以绑过也还要过一遍）； ★ 装配点只在**这里**拼一次范围：本方法之后，域随
   *       {@link SegmentSearchPort.TextQuery} 走，谁都不许再拼一份；
   *   <li>{@link SegmentSearchPort#fullText} —— 全文那一路。<strong>本批不走向量路</strong>： 它要先把问题向量化（{@code
   *       EmbeddingPort}），而"引用先到"这条保证与向量无关 —— 三模式融合是检索策略那一侧的事（{@code
   *       RetrievalService}），不在这条链上再实现一遍。★ 会话附件在检索侧今天**只**能走这一路（见 {@code
   *       RetrievalService#vectorSearch} 开头那条边界：会话域不参与向量路）；
   *   <li>{@link SegmentSearchPort#load} —— 展示信息（标题 / 正文 / 文档名 / 库名 / 文档 id / 库 id）。
   * </ol>
   *
   * <h3>★★ 会话域的接线（第 17 周 · 判据 1 的落点）</h3>
   *
   * <p>{@code conversationId} 必须从<strong>这一轮的上下文</strong>传进来（{@code ChatTurnWriter.PreparedTurn}
   * 里已有它）。此前本方法用的是"只带共用库域"的重载（{@code new TextQuery(问题, kbIds, limit)} ⇒ {@code
   * RetrievalScope.shared}），于是<strong>会话域那一支恒假</strong> —— 会话里传的附件**永远检索不到**，不报错、也不留痕。
   *
   * <p>★ 个人库那一档仍然关（{@code includePersonal = false}）：它只在隐式档（{@code 13 §11.4}），本批不开。
   *
   * <h3>★★ 引用载荷里的结构性事实（第 17 周补：深链与"来源类型"）</h3>
   *
   * <p>{@code documentId} 与 {@code kbId} 直接取自 {@link SegmentSearchPort.Card} 的**真实值**，
   * 本层<strong>不做任何兜底或填充</strong>（{@code kbName} 那一格是唯一的例外，且它不是兜底 —— 见 {@link #kbNameOf}）：
   *
   * <ul>
   *   <li>{@code documentId} 为空 ⇒ 前端拼不出深链的 query ⇒ 点击<strong>静默 no-op</strong>（{@code #91}
   *       的根因就是装配点恒传 {@code null}）；
   *   <li>{@code kbId} 是前端"知识库引用 vs 会话域引用"那条<strong>结构性</strong>判据的判据本身（缺失 = 会话域）。 ★ 给所有引用都补一个值 =
   *       把那条判据变成恒真 —— 而那正是它要防的误判。会话域本来就是 {@code null}。
   * </ul>
   *
   * <h3>★★ 失败粒度：两类失败必须分开（{@code #123}）</h3>
   *
   * <table>
   *   <tr><th>类别</th><th>判据</th><th>处置</th></tr>
   *   <tr><td><strong>身份 / 范围取不到</strong></td>
   *       <td>{@code retrievalScope} 解析出三支全关 · {@code ceiling == null} · 检索端口抛异常 —— 即<strong>可见性无法判定</strong></td>
   *       <td>★ <strong>整批 fail-closed</strong>：一条都不给（现状语义，一个字没动）</td></tr>
   *   <tr><td><strong>单条数据坏</strong></td>
   *       <td>{@link SourceRef} 的必填字段（{@code title} / {@code docName} / {@code kbName}）为空 ——
   *           ★ {@code kb_segment.title} 在库里<strong>可空</strong>（{@code V15}），所以这条路径今天就会被真实数据触发，
   *           不是理论问题。★ {@code kbName} 那一格只对<strong>库域</strong>的引用可能是空的：会话域的引用由
   *           {@link #kbNameOf} 按约定补上（它的 {@code kb_id} 为空 ⇒ 那是"没有库行"，不是坏数据）</td>
   *       <td>★ 先做<strong>读取侧兜底</strong>（{@code title} 为空 ⇒ 用 {@code docName} 顶上，见 {@link #titleOf}）；
   *           兜不住（{@code docName} / {@code kbName} 也为空）⇒ <strong>跳过这一条 + 计数</strong>，其余照常返回；
   *           一条 WARN 带计数（不许静默）</td></tr>
   * </table>
   *
   * <p>★ 这两类曾经被<strong>一个 try/catch 包住整批</strong>（{@code #123} 的现象）：于是一条 {@code title} 为空的分段把
   * <strong>整轮</strong>引用降级成"零命中"，而"零命中"在本项目是<strong>合法状态</strong>（库里真的没有）⇒ 两者在读数上不可区分。
   * 那是"作用域选错了"，不是"实现写错了"。
   *
   * <p>★★ <strong>"范围为空"这条早退的两种形状必须能分开读</strong>（第 17 周补的留痕）：它此前一条日志都没有，
   * 于是"用户确实没有任何可见库"（合法）与"身份没跟过来（{@code nobody()}）"（异常，第 17 周那个 P0）在读数上完全一样。
   * 现在两条都留痕，且<strong>文案可分</strong>；★ 返回值仍然一个字没改。
   *
   * <p>★ 两类失败都<strong>不让这一轮打死</strong>（{@code 10 §3.2.3}"部分成功是常态"），但都不静默：整批那条记一条 warn 并把引用给成空列表
   * （{@code references(List.of())} 是明确的"零命中"，与"检索没跑"在装配器里是两件事），逐条那条记一条带计数的 warn。
   *
   * <p>★ 跳过一条时<strong>不递增</strong> {@code index}：{@code [N]} 必须从 1 连续递增，跳过的空位若占了号，
   * 正文里就会出现一个指向不存在引用的 {@code [N]}。
   */
  private List<SourceRef> retrieve(
      String question,
      Collection<UUID> requestedKbIds,
      UUID conversationId,
      UUID modelId,
      RetrievalParams retrieval) {
    // ★★ 召回开关（通用问答）：未启用 ⇒ 一条都不检索，直接返回空。
    //
    //   为什么要有这一条：`requestedKbIds` 为空时会走 `RetrievalScope.shared` 支
    //   （= 用户可见的**全部**知识库），于是通用问答在**静默做全库检索**。
    //   实测症状：问「编写一个 java 的 hello world」，却把 Vue 文档 / Gutenberg 电子书
    //   以 0.25 分塞进 prompt，模型照着复述成「创建一个 Vue 单文件组件…」。
    //
    //   ★ 只在"调用方没指定库"（通用问答 / 应用未绑库）时看这个开关：
    //     应用显式绑了库，那是用户自己配的范围，不该被通用问答的开关关掉。
    if ((requestedKbIds == null || requestedKbIds.isEmpty())
        && policyResolver != null
        && !policyResolver.retrievalEnabled()) {
      log.info("通用问答未启用召回（app.retrieval 的 retrieval_enabled=false）⇒ 本轮零引用");
      return List.of();
    }
    // ★ 配置了范围且调用方没指定 ⇒ 用配置的范围（否则仍是"全部可见库"）
    Collection<UUID> effectiveKbIds = requestedKbIds;
    if ((effectiveKbIds == null || effectiveKbIds.isEmpty())
        && policyResolver != null
        && policyResolver.retrievalKbIds() != null
        && !policyResolver.retrievalKbIds().isEmpty()) {
      effectiveKbIds = policyResolver.retrievalKbIds();
    }
    // ★★ `#185`：应用级参数优先于平台设置 —— 这一处是**唯一**的"谁赢"判定。
    //   通用问答（app == null）的 retrieval 是 null ⇒ 回落平台设置；应用有绑定 ⇒ 用它自己的。
    //   ★ 模式三路都已接线（VECTOR / FULLTEXT / HYBRID）：VECTOR 走向量检索，HYBRID 两路 RRF 融合。
    int retrievalLimit = retrieval.topN();
    double threshold = retrieval.threshold();

    List<UUID> ordered;
    // ★ 出站上限在 try 之外声明：try 内取到、组装循环里用（catch 一律 return，故定值分析成立）
    ClassificationLevel egressCeiling;
    Map<UUID, Double> scoreById;
    Map<UUID, SegmentSearchPort.Card> cards;
    try {
      // ── 第一类失败的作用域：身份 / 范围 / 命中集合（★ 取不到 ⇒ 整批 fail-closed，一条都不给）──
      // ★★ 域在**一处**解析（第 17 周 · #31 判据 1 的接线点）：★ 传 `false` 是有意的 —— 个人库只在隐式档
      //   （13 §11.4），本批不开；而 `conversationId` 必须跟着这一轮走，否则会话附件**永远检索不到**
      //   （旧的 `new TextQuery(问题, kbIds, limit)` 走的是 RetrievalScope.shared ⇒ 会话域那一支恒假）。
      // ★ 为什么用 retrievalScope 而不是 visibleKbIds：三支的绑定值来自三个不同的地方，其中 ownerId
      //   只能从当前范围取 —— 让装配点自己拼等于把"用谁的身份检索会话附件"变成一个可以填错的入参。
      RetrievalScope scope = segmentSearch.retrievalScope(effectiveKbIds, conversationId, false);
      if (scope.isEmpty()) {
        // ★★ 这一条早退的留痕是补上的（第 17 周）：它此前**一条日志都没有** ⇒ 与"库里确实没有相关内容"
        //   在读数上完全一样。而它的成因有两个形状，处置相同、含义完全不同：
        //     ① **范围为空** —— 用户确实没有任何可见库（合法状态）；
        //     ② **身份为空** —— currentScope() 是 nobody()（me 为 null）⇒ 可见性**无法判定**（异常形状）。
        //   ★ 第 17 周那个 P0（检索跑在模型线程上 ⇒ 范围 ThreadLocal 读不到 ⇒ 引用永远为空）走的就是 ②。
        //   ★ 返回值语义一个字都没改：两种形状都是"整批 fail-closed、一条引用都不给"。
        //   ★ 现在"三支全关"才走这里：会话里有附件、却没绑任何库，是一种**合法且能命中**的情形（不是零引用）。
        UUID me = accessScope.currentScope().me();
        if (me == null) {
          log.warn(
              "检索范围为空，且当前范围取不到身份（currentScope() 是 nobody()，me 为 null）："
                  + "本轮按 fail-closed 零引用继续 —— 这是异常形状，不是『用户确实没有可见库』");
        } else {
          log.info(
              "本轮没有可检索的知识库范围（用户 {} 没有任何可见库，请求范围 {} 条）⇒ 零引用继续（合法状态）",
              me,
              requestedKbIds == null ? 0 : requestedKbIds.size());
        }
        return List.of();
      }
      ClassificationLevel ceiling = accessScope.currentScope().maxClassification();
      if (ceiling == null) {
        // 取不到身份（fail-closed）：一条都不给，而不是"给全部"。★ 必须留痕 —— 它与"零命中"在返回值上不可分
        log.warn("取不到身份（密级上限为 null），本轮按 fail-closed 处置：一条引用都不给");
        return List.of();
      }
      // ★★ 出站闸门（#72）：**读得到**不等于**发得出**。
      //   上面那条是"我能读多密"（身份侧的 ceiling）；这一条是"这个模型允许处理多密"（出口侧）。
      //   ★ 两者必须分开取：一个 SECRET 分段可以合法地被本地模型读，但不得被发去外部 API。
      //   ★ 取不到（null）按 fail-closed 处置 —— 与身份那条同一口径：认不出来就一条都不给，
      //     绝不把 null 读成"没有限制"（那正是 silent-failure 的形状）。
      egressCeiling = modelEgress.ceilingOf(modelId);
      if (egressCeiling == null) {
        log.warn(
            "取不到模型 {} 的出站密级上限（#72），本轮按 fail-closed 处置：一条引用都不给。"
                + "★ 这是异常形状（模型不存在/供应商取值不合法），不是『没有限制』",
            modelId);
        return List.of();
      }
      List<SegmentSearchPort.Scored> hits;
      int fetchLimit = retrievalLimit * RETRIEVAL_OVERFETCH;
      switch (retrieval.mode()) {
        case VECTOR -> {
          hits = vectorHits(question, scope, fetchLimit);
        }
        case HYBRID -> {
          // ★ 两路并跑：全文那一路仍是主干，向量那一路只作补充。
          //   向量路失败（RuntimeException）⇒ log.warn 后回退全文那一路，不许把整轮打死；
          //   全文路失败仍走外层 catch ⇒ 零引用（VECTOR 模式的失败同理）。
          List<SegmentSearchPort.Scored> fullTextHits =
              segmentSearch.fullText(new SegmentSearchPort.TextQuery(question, scope, fetchLimit));
          List<SegmentSearchPort.Scored> vectorHitsList;
          try {
            vectorHitsList = vectorHits(question, scope, fetchLimit);
          } catch (RuntimeException vectorFailure) {
            log.warn("混合检索的向量一路失败，本轮只用全文那一路继续：{}", vectorFailure.getMessage());
            vectorHitsList = List.of();
          }
          hits = vectorHitsList.isEmpty() ? fullTextHits : fuseHybrid(fullTextHits, vectorHitsList);
        }
        case FULLTEXT -> {
          hits =
              segmentSearch.fullText(new SegmentSearchPort.TextQuery(question, scope, fetchLimit));
        }
        default -> throw new IllegalArgumentException("未知的检索模式：" + retrieval.mode());
      }
      if (hits.isEmpty()) {
        return List.of();
      }
      ordered = new ArrayList<>(retrievalLimit);
      scoreById = new LinkedHashMap<>();
      for (SegmentSearchPort.Scored hit : hits) {
        if (ordered.size() >= retrievalLimit) {
          break;
        }
        // ★★ 准确度阈值：低于下限的命中直接丢弃。
        //
        //   实测那个答非所问的案例里，5 条命中**全是 0.25 分** —— 那是"沾一点边就算命中"的分数，
        //   而模型会把它们当事实复述。阈值是这里唯一的拦截点：它必须在**进 prompt 之前**生效，
        //   否则"低分引用"仍会污染上下文（界面上的引用列表反而不是主要危害）。
        if (hit.score() < threshold) {
          continue;
        }
        // ★ 去重（同一分段可能被多个词元命中）
        if (!scoreById.containsKey(hit.segmentId())) {
          ordered.add(hit.segmentId());
          scoreById.put(hit.segmentId(), hit.score());
        }
      }
      if (ordered.isEmpty()) {
        return List.of();
      }
      cards = new LinkedHashMap<>();
      for (SegmentSearchPort.Card card : segmentSearch.load(ordered)) {
        cards.put(card.segmentId(), card);
      }
    } catch (RuntimeException failure) {
      log.warn("检索失败，本轮以'零引用'继续：{}", failure.getMessage());
      return List.of();
    }

    // ── 第二类失败的作用域：逐条组装（★ 单条装不成 ⇒ 跳过这一条 + 计数，其余照常返回）──
    List<SourceRef> refs = new ArrayList<>(ordered.size());
    int skipped = 0;
    String firstSkipReason = null;
    // ★★ 出站闸门挡掉的条数（#72）—— **单独计数**，不许并进 `skipped`：
    //   "一条脏数据装不成"与"密级超出目标模型上限"是两件完全不同的事，读数混在一起就无法归因。
    int egressBlocked = 0;
    int index = 1;
    for (UUID id : ordered) {
      SegmentSearchPort.Card card = cards.get(id);
      if (card == null) {
        // 判过可见却取不到展示信息：只可能是并发删除。跳过而不是塞空壳 —— 空壳会让 [N] 编号与实际内容错位
        skipped++;
        if (firstSkipReason == null) {
          firstSkipReason = "分段已被并发删除（取不到展示信息）";
        }
        continue;
      }
      // ★★ 出站闸门（#72）：这一条的密级高过**目标模型**允许处理的上限 ⇒ 它不得进入本次上下文。
      //   ★ 必须挡在 `refs.add` **之前**：这样 `[N]` 编号仍然逐条连续（不在编号里留空洞），
      //     而"送出的 payload 里不含这一条"这件事才是可断言的（#72 判据 1 要的是断言 payload，不是结果看起来没泄漏）。
      //   ★ 与身份侧的 ceiling 是两道**独立**的过滤：先"我能读吗"，再"发得出吗"。
      if (!card.classification().within(egressCeiling)) {
        egressBlocked++;
        continue;
      }
      try {
        refs.add(
            new SourceRef(
                index,
                titleOf(card),
                card.docName(),
                // ★★ 会话域**没有库行** ⇒ 左连接下 kb_name 是 NULL，而契约把 kbName 当必填 ⇒ 不给这条约定，
                //   会话附件的引用会被"逐条跳过"整批丢掉（有 WARN、界面一条都没有）。产品口径见 SourceLabels。
                kbNameOf(card),
                scoreById.getOrDefault(id, 0.0),
                index,
                card.content(),
                id.toString(),
                // ★★ 深链的两个分量取自**真实的行**（第 17 周修掉 #91 的根因）：此前这里恒传 null ⇒
                //   前端 Index.vue 拼不出 query ⇒ 点了没反应也不报错（静默 no-op）。
                card.documentId() == null ? null : card.documentId().toString(),
                // ★★ kbId 是前端"知识库引用 vs 会话域引用"那条结构性判据的判据本身（缺失 = 会话域）：
                //   此前恒传 null ⇒ 真栈上连库引用也没有它 ⇒ 每条库引用都被读成「来源：」。
                //   ★ 会话域那一行本来就是 null（没有库行），所以这里**不许**兜底 —— 兜了就抹掉了判据。
                card.kbId() == null ? null : card.kbId().toString(),
                // ★★ 密级（#27）：它是"会话累计密级"的唯一来源 —— 引用集合自带它之后，
                //   "本轮引用集合的最高密级"才到得了会话落库那一刻（写入口见 ChatTurnWriter#finish）。
                //   ★ 这里**不许**兜底：库里那一列是 V22 的非空列（真实的行必有值），
                //   兜底成 PUBLIC 会让会话水位算低 = 静默降密。
                card.classification()));
        // ★ 只有真的装成了一条才占号（见方法注释末条）
        index++;
      } catch (RuntimeException singleItemFailed) {
        // ★ 可见性判定没问题，只是这一条装不成（title/docName/kbName 为空等）⇒ 摘掉这一条
        skipped++;
        if (firstSkipReason == null) {
          firstSkipReason = singleItemFailed.getMessage();
        }
      }
    }
    if (skipped > 0) {
      // ★ 不许静默：降级必须留痕，且带着条数（"一条脏数据"与"整轮没引用"必须能分开）
      log.warn("本轮跳过 {} 条引用（其余 {} 条照常返回）：{}", skipped, refs.size(), firstSkipReason);
    }
    if (egressBlocked > 0) {
      // ★★ 出站闸门（#72）的留痕：**必须单独一条**，不许并进上面那条 `skipped`。
      //   ★ 为什么：这两件事的处置完全不同 —— "装不成"要修数据，而"密级超出模型上限"是**闸门在正常工作**。
      //     读数混在一起，就分不出"检索变差了"与"数据被按策略拦住了"。
      //   ★ 边界如实标注：现在只有日志、**没有审计留痕**（#72 判据 4 要的是 谁/哪个应用/最高密级/目标模型），
      //     那一格尚未落地，别把这条日志读成"审计已做"。
      log.warn(
          "本轮有 {} 条引用因密级高于目标模型的出站上限被拦下（#72）：" + "模型 {} · 出口上限 {} ⇒ 这些分段未进入送往模型的上下文；其余 {} 条照常返回",
          egressBlocked,
          modelId,
          egressCeiling,
          refs.size());
    }
    log.info("本轮检索到 {} 条引用（上限 {}）", refs.size(), retrievalLimit);
    return refs;
  }

  /**
   * 向量检索一路（{@code #185}）：把问题按知识库各自的向量模型分组向量化，再逐组向量检索、RRF 融合。
   *
   * @param question 用户问题
   * @param scope 检索范围（会话附件部分不参与向量检索，见方法内说明）
   * @param limit 每组的召回上限
   * @return 按融合顺序排列的命中（display score = 该分段在各模型下的最大相似度）
   */
  private List<SegmentSearchPort.Scored> vectorHits(
      String question, RetrievalScope scope, int limit) {
    if (embeddingPort == null) {
      // ★ 不静默退回全文：模式是 VECTOR 就必须走向量，缺依赖要让外层 catch 走零引用并留日志。
      throw new IllegalStateException("检索模式为 VECTOR，但 EmbeddingPort 未装配");
    }
    if (scope.hasConversationScope()) {
      // ★ 问答链路的 conversationId 每轮都有值（不代表有附件）。向量查询只能按知识库的模型/维度表走，
      //   把 CONVERSATION 分段混进来会拿错模型；这里显式收窄并在下面留痕，不静默。
      //   混合模式的会话附件仍由全文一路覆盖；纯向量模式只检索知识库 —— 界面说明与这里同口径。
      log.info("向量检索只查知识库：本轮会话附件不参与向量一路");
    }
    RetrievalScope vectorScope = scope.withoutConversation();
    Map<UUID, UUID> modelByKb = segmentSearch.embeddingModelByKb(vectorScope.kbIdsWithModelRows());
    if (modelByKb.isEmpty()) {
      throw new IllegalStateException("这些知识库没有可用的向量模型");
    }
    // 按向量模型分组：同一模型的库共用一次向量化 + 一次检索。
    Map<UUID, List<UUID>> kbIdsByModel = new LinkedHashMap<>();
    modelByKb.forEach(
        (kbId, modelId) -> kbIdsByModel.computeIfAbsent(modelId, k -> new ArrayList<>()).add(kbId));
    List<List<SegmentSearchPort.Scored>> rankedPerModel = new ArrayList<>(kbIdsByModel.size());
    for (Map.Entry<UUID, List<UUID>> group : kbIdsByModel.entrySet()) {
      UUID modelId = group.getKey();
      // ★ 按模型分组隔离失败：一个向量模型没加载不该让其它模型的库也一起零引用。
      //   失败组 log.warn 后跳过；全部失败才抛，由外层统一落成"零引用 + 可读日志"。
      try {
        List<float[]> embedded = embeddingPort.embed(modelId, List.of(question));
        if (embedded == null || embedded.isEmpty()) {
          throw new IllegalStateException("向量模型未返回问题的向量：" + modelId);
        }
        float[] vector = embedded.getFirst();
        if (vector == null || vector.length == 0) {
          throw new IllegalStateException("向量模型返回了空向量：" + modelId);
        }
        rankedPerModel.add(
            segmentSearch.vectorSearch(
                new SegmentSearchPort.VectorQuery(
                    vector.length, vector, vectorScope.withinShared(group.getValue()), limit)));
      } catch (RuntimeException groupFailure) {
        log.warn("向量模型 {} 的分组检索失败，跳过该组：{}", modelId, groupFailure.getMessage());
      }
    }
    if (rankedPerModel.isEmpty()) {
      throw new IllegalStateException("所有向量模型分组都检索失败");
    }
    if (rankedPerModel.size() == 1) {
      // 单组保持该组顺序。
      return rankedPerModel.getFirst();
    }
    return fuseHybrid(rankedPerModel);
  }

  /**
   * 两路（或多组）命中的本地 RRF 融合（{@code #185}）。
   *
   * <p>★ 刻意<b>不</b> import {@code retrieval} 模块的 {@code Rrf}：那是平台内部参考实现，问答链路与它 不该产生模块耦合。公式：{@code
   * score(d) = Σ 1 / (k + rank_i(d))}，k=60，rank 从 1 起。
   *
   * <p>★ 融合后的 {@code score} 取该分段在各路中的<strong>最大原始分</strong>（用于阈值判定与界面展示）， 顺序按 RRF 分数。
   */
  private static List<SegmentSearchPort.Scored> fuseHybrid(
      List<SegmentSearchPort.Scored> routeA, List<SegmentSearchPort.Scored> routeB) {
    return fuseHybrid(List.of(routeA, routeB));
  }

  private static List<SegmentSearchPort.Scored> fuseHybrid(
      List<List<SegmentSearchPort.Scored>> routes) {
    final int k = 60;
    Map<UUID, Double> rrfScore = new LinkedHashMap<>();
    Map<UUID, Double> displayScore = new LinkedHashMap<>();
    for (List<SegmentSearchPort.Scored> route : routes) {
      int rank = 1;
      for (SegmentSearchPort.Scored hit : route) {
        rrfScore.merge(hit.segmentId(), 1.0 / (k + rank), Double::sum);
        displayScore.merge(hit.segmentId(), hit.score(), Math::max);
        rank++;
      }
    }
    List<SegmentSearchPort.Scored> fused = new ArrayList<>(rrfScore.size());
    rrfScore.entrySet().stream()
        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
        .forEach(
            e -> fused.add(new SegmentSearchPort.Scored(e.getKey(), displayScore.get(e.getKey()))));
    return fused;
  }

  /**
   * 引用的标题：★ <strong>读取侧兜底</strong> —— {@code title} 为空时用 {@code docName} 顶上（第 17 周）。
   *
   * <h3>为什么不许把"title 为空"直接当成坏数据摘掉</h3>
   *
   * <p>库里 {@code kb_segment.title} <strong>可空</strong>（{@code V15} 就是这么建的，那是有意的数据侧自由：
   * 无标题的段落是真实存在的），而契约 {@link SourceRef} 把 {@code title} 当<strong>必填</strong>。 两者的差额此前靠"逐条跳过"兜着 ⇒
   * 一条**本可以展示**的引用被丢掉：用户看到的是"库里有、引用栏没有"， 而它与"库里确实没有相关内容"在读数上不可区分（本项目头号病）。
   *
   * <p>★ 兜底值取 {@code docName} 而不是占位串（"无标题" / 分段 id）：文档名是**库里真实存在的字**， 界面上的「知识库：制度库 / 文档名」那一行本来就显示它
   * —— 用占位串会让引用栏出现一个用户在任何地方都找不到的名字。
   *
   * <p>★ 第二层防御仍在：{@code docName} 也为空时这里返回的仍是空 ⇒ {@link SourceRef} 的构造器抛， 逐条那条 {@code catch}
   * 把它摘掉并计数（见 {@link #retrieve} 的失败粒度表）。
   */
  private static String titleOf(SegmentSearchPort.Card card) {
    String title = card.title();
    return title == null || title.isBlank() ? card.docName() : title;
  }

  /**
   * 引用的来源名：★★ <strong>会话域那一格由约定常量给出</strong>（{@link SourceLabels#CONVERSATION_ATTACHMENT}）。
   *
   * <h3>为什么"补这一格"是必须的，而它又不是兜底</h3>
   *
   * <p>会话临时的分段<strong>没有 {@code kb_knowledge_base} 行</strong> ⇒ 左连接下 {@code kb_name} 是 {@code
   * NULL}； 而契约 {@code SourceRef} 把 {@code kbName} 当<strong>必填</strong>（与 {@code title} / {@code
   * docName} 同一档）。 不给这一格，{@link #retrieve} 的"逐条跳过"会把<strong>整批</strong>会话附件引用摘掉：日志里一条 WARN 带计数，
   * 而界面上<strong>一条都没有</strong> —— 它与"这个会话确实没有相关附件"在读数上完全一样（本项目头号病）。
   *
   * <p>★ 它<strong>不是</strong>"给缺字段补个默认值"那种兜底：兜底会掩盖数据坏味，而这里补的是
   * <strong>一个已知合法类别的显示名</strong>（会话域的引用按产品口径就该叫这个）。两条纪律把它与兜底分开：
   *
   * <ol>
   *   <li>判据取 {@link SegmentSearchPort.Card#kbId()}（<strong>结构性</strong>事实：没有库行）， 而不是 {@code
   *       kbName} 为空 —— 一个<strong>库域</strong>的引用若库名为空，那是数据坏了， 仍然走"逐条跳过 + 计数 WARN"那条路（{@code #123}
   *       的失败粒度一个字不改）；
   *   <li>值来自 {@link SourceLabels}（唯一一处），不在本方法里写字面量 —— 否则前端那句"值归后端"就有两处真相。
   * </ol>
   *
   * <p>★ 前端只用 {@code kbId} 是否为 {@code null} 判"是不是会话域"，与这里的判据同源。
   */
  private static String kbNameOf(SegmentSearchPort.Card card) {
    if (card.kbId() == null) {
      return SourceLabels.CONVERSATION_ATTACHMENT;
    }
    return card.kbName();
  }

  /**
   * 提示词（{@code 03} 机制一的最小形态）：问题 + 检索到的片段。
   *
   * <p>★ 本批<strong>不</strong>做提示词模板渲染（{@code as_assistant_prompt} 的绑定与模板引擎属另一批）： 这里给一个确定形状，让"引用编号
   * {@code [N]} 与 {@code refs} 的顺序"有唯一依据。
   */
  /**
   * 本轮要用的初始提示词。
   *
   * <p>★ 读失败一律降级为 {@code null}（不加前缀）而不是让整轮问答失败： 它是"锦上添花"的口径，不该比"能不能回答"优先级更高。
   *
   * @return 提示词；{@code null} = 不加
   */
  private String systemPromptOrNull(ChatTurnWriter.PreparedTurn turn) {
    // ★★ 应用会话只读应用自己的 rolePrompt，**不回落**到平台通用提示词（#183）。
    //   空角色 = 不加前缀，而不是"借用通用问答的口径"——两者混用会让应用的回答被平台口径污染。
    if (turn.appId() != null) {
      String role = turn.rolePrompt();
      return role == null || role.isBlank() ? null : role.strip();
    }
    if (policyResolver == null) {
      return null;
    }
    try {
      return policyResolver.systemPrompt();
    } catch (RuntimeException failure) {
      log.warn("读通用会话设置失败，本轮不加初始提示词（对话行为与未配置时一致）", failure);
      return null;
    }
  }

  private static String promptOf(String question, List<SourceRef> refs, String systemPrompt) {
    // ★ 初始提示词拼在**问题之前**，而不是作为一条 system 消息：
    //   `ChatMessage.Role` 只有 USER/ASSISTANT（它的 javadoc 明确写「system 由提示词承担」），
    //   所以"系统口径"在这个工程里本来就住在 prompt 文本里。
    String prefix =
        (systemPrompt == null || systemPrompt.isBlank()) ? "" : systemPrompt.strip() + "\n\n";
    if (refs.isEmpty()) {
      return prefix + question;
    }
    StringBuilder prompt = new StringBuilder(prefix).append(question).append("\n\n参考资料：\n");
    for (SourceRef ref : refs) {
      prompt
          .append('[')
          .append(ref.index())
          .append("] ")
          .append(ref.title())
          .append('\n')
          .append(ref.full() == null ? "" : ref.full())
          .append('\n');
    }
    return prompt.toString();
  }

  private static long elapsedMs(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000;
  }

  /**
   * 调试用的回调收集器（{@code #184}）：把模型吐出的块拼成整段答案，并汇总它上报的用量。
   *
   * <p>★ 与流式那条路的区别只有一处：这里没有装配器、没有心跳、没有落库 —— 调试是"一次阻塞调用换一段文本"。 而 {@code onUsage} 仍然按端口语义收下：上游不报就不调 ⇒
   * {@code tokens} 是 {@code null}（不是 0）。
   */
  private static final class DebugCollector implements ChatStreamPort.Callback {

    private final StringBuilder text = new StringBuilder();

    private int promptTokens;

    private int completionTokens;

    /** 有没有任何一次 {@code onUsage} —— 区分"没报"与"报了 0"。 */
    private boolean usageReported;

    private boolean failed;

    private String detail;

    @Override
    public void onChunk(String chunk) {
      if (chunk != null && !chunk.isEmpty()) {
        text.append(chunk);
      }
    }

    @Override
    public void onUsage(int promptTokens, int completionTokens) {
      this.usageReported = true;
      this.promptTokens += promptTokens;
      this.completionTokens += completionTokens;
    }

    @Override
    public void onEnd(ChatStreamPort.End end, String detail) {
      if (end == ChatStreamPort.End.FAILED) {
        this.failed = true;
        this.detail = detail;
      }
    }

    private Integer usageTotalOrNull() {
      return usageReported ? promptTokens + completionTokens : null;
    }
  }

  // ────────────────────────── 这一轮的可变状态 ──────────────────────────

  /**
   * 一轮问答的可变状态（同时是模型端口的 {@link ChatStreamPort.Callback}）。
   *
   * <p>★ 为什么收成一个对象而不是散在局部变量里：三条线程<strong>在回调里</strong>改这些值， 而 lambda 只能捕获 effectively-final 的局部变量。★
   * 它<strong>不</strong>承担任何"形状"判断： 状态机与顺序仍在 {@code Message} 与装配器里。
   */
  private final class Run implements ChatStreamPort.Callback {

    private final StreamAssembler assembler;
    private final UUID messageId;
    private final long startedAtNanos;

    /** 本轮的出口 —— 由 {@code streamTurn} 在启动任何线程之前给出，之后只读。 */
    private final EventSink sink;

    /** ★ 写连接与心跳线程都会写出 ⇒ 全部过这把锁（装配器刻意不做线程安全）。 */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** ★ 中断上游的唯一机制（用户停止 / 已判超时 / 连接已断）。 */
    private final AtomicBoolean upstreamStopped = new AtomicBoolean(false);

    /** ★ 终态的唯一出口（多条线竞争，先到者收）。 */
    private final AtomicBoolean terminalTaken = new AtomicBoolean(false);

    /**
     * 终态判定的<strong>快照</strong>（{@link #pathOf()} 只判一次，之后谁来读都是同一个答案）。
     *
     * <p>★ 为什么必须快照：判定本体里有一个<strong>一次性</strong>动作（读走停止信号，{@code consume}）。 判两次就会有第二次读到"已经没有信号了"⇒
     * 同一个 {@code Run} 上出现两个终态答案 （第二次收尾由 {@link #terminalTaken} 挡住，但"答案会漂"这件事本身就该被消灭）。
     */
    private final AtomicReference<StreamTerminationPath> terminalPath = new AtomicReference<>();

    private final AtomicReference<ChatStreamPort.End> upstream =
        new AtomicReference<>(ChatStreamPort.End.COMPLETE);

    private final AtomicBoolean modelDone = new AtomicBoolean(false);

    /** 已交给出口的事件游标（装配器保留全部历史，绝不能重发）。 */
    private int emitted;

    /** 已落库的字符数（增量 flush 的基准）。 */
    private int persistedChars;

    /**
     * 最后一次<strong>业务帧</strong>写出的时间 —— ★ 心跳<strong>不</strong>刷新它。
     *
     * <p>★★ 这一条是被一次实测逼出来的：把心跳也算作活动的话，超时窗口永远被心跳喂满，
     * 于是"上游一直不吐字"这条路径<strong>再也不会超时</strong>（而它看起来完全正常：连接活着、心跳在发）。
     */
    private long lastBusinessFrameAtNanos;

    /** 最后一次心跳写出的时间（心跳间隔看它，与上面那个时钟分开）。 */
    private long lastHeartbeatAtNanos;

    private long lastFlushAtNanos;
    private int flushBaseChars;
    private boolean refsBound;
    private List<SourceRef> refs = List.of();
    private volatile boolean cancelledByStop;
    private volatile boolean timedOut;
    private volatile boolean disconnected;
    private volatile String lastFailReason;

    private Run(StreamAssembler assembler, UUID messageId, long startedAtNanos, EventSink sink) {
      this.assembler = assembler;
      this.messageId = messageId;
      this.startedAtNanos = startedAtNanos;
      this.sink = sink;
      this.lastBusinessFrameAtNanos = startedAtNanos;
      this.lastHeartbeatAtNanos = startedAtNanos;
      this.lastFlushAtNanos = startedAtNanos;
      // ★★ 一轮开始就作废这条消息 id 上的**残留信号**（#143 后半）：收尾那一刻的 consume 漏掉了一种时序 ——
      //   停止请求在"收尾路径最后一次 consume"之后才置上信号（用户点停止、生成恰好同一毫秒写完）
      //   ⇒ 那个 id 会带着上一轮的停止活到下一轮。这一步把它变成显式机制，于是
      //   "这一轮会不会被停"只看这一轮自己的请求（#143 判据：同 id 二次生成不受前一次停止影响）。
      stopSignal.begin(messageId);
    }

    /**
     * 这一轮该收在哪条路径上（★ 四路径只在这里判一次；判定顺序见下）。
     *
     * <h3>★★ 判定顺序是<strong>写死</strong>的，而它正是 {@code #119} 的落点</h3>
     *
     * <ol>
     *   <li><strong>显式停止</strong>（{@link ChatStopSignal}）—— ★ 最高优先：用户点了停止就是停止，
     *       此后连接断没断<strong>不改变</strong>这个事实（终态 {@code STOPPED}，不是 {@code FAILED}）；
     *   <li>闲置超时；
     *   <li>连接断开 / 上游失败。
     * </ol>
     *
     * <p>★★ 为什么不能只靠 {@link #onChunk} 里那个检查点：它<strong>要等下一个块</strong>。
     * 而"用户停止"与"连接断掉"之间经常一个块都没有（甚至从头到尾一个块都没有）⇒ {@code cancelledByStop} 一直是 {@code false}，而 {@code
     * disconnected} 已经是 {@code true} ⇒ 终态落到 {@code FAILED}，
     * <strong>结果取决于竞态</strong>。那正是"先停止、再断连"这条路今天的病：两个判据谁也不看谁。
     *
     * <p>★ 所以这里在收尾那一刻<strong>再读一次</strong>停止信号（{@link ChatStopSignal#consume}，读到即清）：
     * 显式停止压过断开。反之——从头到尾<strong>没收到过</strong>停止、只是连接断了——落 {@code DISCONNECT} （{@code
     * FAILED}），这一个字节没改。
     *
     * <p>★ 结论按 {@link #terminalPath} 快照：本方法可能被读多次（第二个收尾入口），答案必须只有一个。
     */
    private StreamTerminationPath pathOf() {
      StreamTerminationPath decided = terminalPath.get();
      if (decided != null) {
        return decided;
      }
      StreamTerminationPath computed = decideTerminalPath();
      if (terminalPath.compareAndSet(null, computed)) {
        return computed;
      }
      return terminalPath.get();
    }

    /**
     * 终态判定的本体 —— <strong>顺序即上面那张表的顺序</strong>（只在 {@link #pathOf()} 里被调一次）。
     *
     * <p>★ 第一行那个 {@code consume} 是"显式停止优先"的<strong>唯一</strong>实现点，也是本批新增的那一步： 去掉它（或让它排在 {@code
     * disconnected} 之后）⇒ 先停止再断连就会收成 {@code FAILED}， 也就是 {@code #119} 里的竞态。这一条由真栈用例 {@code
     * ConversationStopEndpointTest#stopThenDisconnectStillEndsAsStopped} 钉住。
     */
    private StreamTerminationPath decideTerminalPath() {
      // ★★ ① 显式停止优先于一切（读到即清 —— 留着会让下一轮同名消息一上来就被停）
      boolean stopRequested = stopSignal.consume(messageId);
      if (cancelledByStop || stopRequested) {
        return StreamTerminationPath.ABORT;
      }
      if (timedOut) {
        return StreamTerminationPath.IDLE_TIMEOUT;
      }
      if (disconnected || upstream.get() == ChatStreamPort.End.FAILED) {
        return StreamTerminationPath.DISCONNECT;
      }
      return StreamTerminationPath.COMPLETE;
    }

    /**
     * 取锁并把"还没交出去"的事件按顺序写出去（★ 这是<strong>业务帧</strong>的写法）。
     *
     * <p>★ 只有真的写出过业务帧才刷新 {@link #lastBusinessFrameAtNanos}：心跳走 {@link
     * #heartbeat()}，<strong>不</strong>碰这个时钟（见它的注释）。
     */
    private void pump() {
      writeLock.lock();
      try {
        if (pumpLocked()) {
          lastBusinessFrameAtNanos = System.nanoTime();
        }
      } finally {
        writeLock.unlock();
      }
    }

    /**
     * 调用方<strong>已经持锁</strong>时的写法。
     *
     * @return 这次是否真的写出了<strong>新的业务帧</strong>（用来决定要不要刷新超时时钟）
     */
    private boolean pumpLocked() {
      List<StreamEvent> events = assembler.events();
      boolean wrote = events.size() > emitted;
      for (int i = emitted; i < events.size(); i++) {
        sink.onEvent(events.get(i));
      }
      emitted = events.size();
      return wrote;
    }

    /**
     * 发一次心跳注释帧（在锁内）。
     *
     * <p>★★ 它<strong>只</strong>刷新心跳时钟：心跳不是"生成在推进"的证据， 拿它去刷新超时时钟会让"上游一直不吐字"永远不超时（见 {@link
     * #lastBusinessFrameAtNanos}）。
     */
    private void heartbeat() {
      writeLock.lock();
      try {
        assembler.heartbeat();
        pumpLocked();
        lastHeartbeatAtNanos = System.nanoTime();
      } finally {
        writeLock.unlock();
      }
    }

    /** 引用快照：★ 只在"真的拿到过一批引用"时绑（{@code null} 与 {@code []} 是两件事）。 */
    private void bindRefs(List<SourceRef> retrieved) {
      this.refs = retrieved;
      this.refsBound = true;
    }

    // ── ChatStreamPort.Callback ──

    @Override
    public void onChunk(String chunk) {
      // ★ ① 停止信号：显式停止优先（用户点了停止就是停止，与连接状态无关）
      if (stopSignal.isRequested(messageId)) {
        log.info("检测到停止信号：消息 {} 收尾为 ABORT（已生成 {} 字）", messageId, assembler.text().length());
        cancelledByStop = true;
        upstreamStopped.set(true);
        throw new StopUpstream();
      }
      // ★ ② 已经决定收尾（超时 / 连接断了）⇒ 打断上游，别再吐字
      if (upstreamStopped.get()) {
        throw new StopUpstream();
      }
      assembler.delta(chunk);
      pump();
      flushIfDue();
    }

    @Override
    public void onEnd(ChatStreamPort.End end, String detail) {
      upstream.set(end);
      if (end == ChatStreamPort.End.FAILED) {
        lastFailReason = detail;
      }
    }

    /**
     * ★★ {@code #142} ④ 的<strong>喂入点</strong>：把模型端口上报的用量交给装配器累加，再由 {@code done.tokens} 与落库两条路径读出去。
     *
     * <h3>★★ 为什么这一行必须有（去掉它会发生什么）</h3>
     *
     * <p>去掉它，{@code StreamAssembler#addUsage} 在 {@code backend/src/main} 就<strong>零调用点</strong>， 而
     * {@code done.tokens} 恒为 {@code TokenUsage.zero()} —— 界面上、库里、成本口径上全是 0，
     * 并且<strong>不报任何错</strong>（这正是这一批要收掉的那条静默失效）。判据落在真栈用例 {@code ChatStreamUsageHttpTest}：它读 HTTP 上
     * {@code done} 帧里的三字段与库里那两列，逐字等于假模型上报的两个分量。
     *
     * <p>★ 分量折成领域值对象只在这一处（{@code TokenUsage} 属于 {@code conversation.domain}， 而 SPI 在 {@code
     * platform} —— 让 SPI 引用它会形成模块环，所以端口只传两个 int，见 {@link ChatStreamPort.Callback#onUsage}）。
     *
     * <p>★ 终态之后再上报会被装配器拒绝（{@code requireNotSettled}）而不是被静默丢掉： "一条流终态之后账就封了"这条口径由 {@code
     * StreamTokensTest#usageAfterDoneIsRefused} 与 {@code Message} 的状态机各自钉住一半。
     */
    @Override
    public void onUsage(int promptTokens, int completionTokens) {
      assembler.addUsage(new TokenUsage(promptTokens, completionTokens));
    }

    /**
     * 按 {@code 03 §1.4} 的节奏落库：<strong>500ms 或 200 字，先到者</strong>。
     *
     * <p>★ flush 之外的正文仍在装配器的内存里，收尾时会一次补齐（见 {@code ChatTurnWriter#finish}）—— 所以"flush
     * 得少"只影响生成中途的可读性，不会丢内容。
     */
    private void flushIfDue() {
      String text = assembler.text();
      long now = System.nanoTime();
      boolean byChars = text.length() - flushBaseChars >= properties.flushChars();
      boolean byTime = (now - lastFlushAtNanos) / 1_000_000 >= properties.flushIntervalMs();
      if (!byChars && !byTime) {
        return;
      }
      persistedChars = turns.flush(messageId, text, persistedChars);
      flushBaseChars = text.length();
      lastFlushAtNanos = now;
    }
  }

  /** 打断上游（停止 / 超时 / 连接已断）—— ★ 本层与模型端口之间唯一的"取消"机制。 */
  private static final class StopUpstream extends RuntimeException {
    private StopUpstream() {
      super("上游已被调用方打断（用户停止 / 闲置超时 / 连接已断）", null, false, false);
    }
  }
}
