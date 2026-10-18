package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.assistant.AppBindingPort;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话列表的<strong>读取用例</strong>（契约 {@code listConversations}：{@code GET /conversations}）。
 *
 * <h3>★★ 它补的是一个"页面在调、后端没有"的洞 —— 而那个洞让 A2 在界面上不成立</h3>
 *
 * <p>真栈闸门的诊断逐字读到（{@code check_reload_keeps_state_e2e.py} 第一次运行）：
 *
 * <pre>
 * [诊断] 会话页 url = …/#/conversation?appId=…
 * [诊断] body 前 200 字 = '…历史会话\n接口不存在：api/v1/conversations\n重试\n新建会话\n开始提问吧…'
 * </pre>
 *
 * <p>⇒ 会话页加载时调 {@code GET /conversations} 拿<strong>历史会话</strong>，而那个端点**不存在** ⇒ 侧栏永远是空的 ⇒
 * <strong>刷新之后没有会话可恢复</strong> ⇒ {@code ROADMAP §2.2} 的 A2「刷新页面不重置」在<strong>界面层面物理上做不到</strong>
 * （服务层那一半是对的 —— {@code ConversationStateSurvivesReloadTest} 已证明）。
 *
 * <p>★ 这正是本仓「三格配对」纪律说的第三格（消费点）：契约有、页面在调、**后端没有**。
 *
 * <h3>可见性口径</h3>
 *
 * <p>会话是<strong>私人对话</strong>（{@code 07 §3.1}）：{@code ownerId = 当前用户}、 默认 {@code
 * PRIVATE}。所以本用例<strong>只返回自己的会话</strong>（{@code listByUser}）， 不按数据范围放大 ——
 * 放大会让"别人的对话"出现在我的侧栏里，那是比"看不到"严重得多的事。
 *
 * <p>★ {@code mine} 这个查询参数因此<strong>暂不改行为</strong>：今天是恒 {@code true} 的语义。 写在这里，免得下一个人以为漏读了一个参数。
 */
@Service
public class ConversationListService {

  /** 每页上限（与会话历史同一个数量级；给上限防止一次把整个账号的会话拉出来）。 */
  private static final int MAX_PAGE_SIZE = 100;

  private static final int DEFAULT_PAGE_SIZE = 20;

  /** 新建会话的占位标题（★ 第一轮提问后会按问题更新，那是既有行为）。 */
  // ★ 占位标题已提升到 Conversation.PLACEHOLDER_TITLE：ChatTurnWriter 也要读它

  private final ConversationRepository conversations;

  private final AppBindingPort appBindings;

  public ConversationListService(ConversationRepository conversations, AppBindingPort appBindings) {
    this.conversations = conversations;
    this.appBindings = appBindings;
  }

  /**
   * 新建一条会话（契约 {@code createConversation}：{@code POST /conversations}，body {@code {appId?}}）。
   *
   * <h3>为什么它必须和列表一起落</h3>
   *
   * <p>★ 契约闸门当场拦下过：`GET /conversations` 一落地，**同路径**的 `POST /conversations` 就必须一并说清 ——
   * 它的规则是「同路径上的方法单边变更会静默漂移」。
   *
   * <p>★ 而这个端点**页面上真的在用**：真栈诊断里会话页渲染的是 `…接口不存在：api/v1/conversations…重试…新建会话…` —— 那个「新建会话」按钮调的就是它。
   *
   * <p>★ 标题由服务端定（契约的 body 只有 {@code appId}）：给一个中性的占位标题， 第一轮提问时会话标题会按问题更新（那是既有行为）。
   *
   * @param userId 当前登录用户（会话的归属人）
   * @param appId 所属应用；{@code null} = 通用问答
   * @return 新会话
   */
  @Transactional
  public ConversationListItem create(UUID userId, UUID appId) {
    // ★★ 幂等：已经有「同应用 + 一条消息都没有」的会话 ⇒ 直接复用它，不再新建。
    //
    //   为什么必须这样（实测症状）：用户每点一次「新建会话」就真建一行空会话，
    //   点 5 次列表里就有 5 个同名的「新会话」（标题都是占位值），
    //   于是用户看到的是「新会话没了」「像刷新了一下」—— 其实是同名项太多，认不出哪个是刚建的。
    //
    //   语义上也更对：用户点「新建会话」想要的是「一个可以开始问的空对话」，
    //   而已经有一个空对话时就该把它打开，而不是再堆一个。
    //
    //   ★ 只复用「最近一条是空的」那种：若最近一条已有消息，说明用户真的在开新话题，
    //     这时新建才对（不能去翻更早的空会话，那会让「新建」跳到很久以前的会话上）。
    Optional<Conversation> reusable =
        conversations
            .findLatestByUserAndAssistant(userId, appId)
            .filter(c -> c.messageCount() == 0);
    if (reusable.isPresent()) {
      return toItem(reusable.get());
    }
    Conversation conversation =
        conversations.save(
            new Conversation(UUID.randomUUID(), userId, appId, Conversation.PLACEHOLDER_TITLE));
    return toItem(conversation);
  }

  /**
   * 列出当前用户的会话（按最近活动倒序）。
   *
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @param appId 只看某个应用的会话；{@code null} = 全部
   * @param keyword 按标题模糊搜索（大小写不敏感）；{@code null}/空白 = 不过滤
   * @param page 页码，{@code 1} 起；★ {@code -1} = 最后一页（与历史消息同一条约定）
   * @param size 页大小
   * @return 分页结果
   */
  @Transactional(readOnly = true)
  public PageResult<ConversationListItem> list(
      UUID userId, UUID appId, String keyword, Integer page, Integer size) {
    int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    List<Conversation> all = new ArrayList<>(conversations.listByUser(userId));
    if (appId != null) {
      all.removeIf(conversation -> !appId.equals(conversation.assistantId()));
    }
    if (keyword != null && !keyword.isBlank()) {
      String needle = keyword.strip().toLowerCase(Locale.ROOT);
      all.removeIf(
          conversation ->
              conversation.title() == null
                  || !conversation.title().toLowerCase(Locale.ROOT).contains(needle));
    }
    // ★★ 这里**刻意不再排序**（我第一版写了一行 `all.sort(...)`，已删）。
    //   原因：`ConversationRepository#listByUser` 背后的查询就叫
    //   `findByUserIdAndDeletedAtIsNullOrderByLastMessageAtDescCreatedAtDesc`
    //   —— **SQL 里已经按最近活动倒序**（活动时间相同再按创建时间倒序）。
    //   ★ 为什么值得留这段注释：我做金丝雀时把 `all.sort(...)` 删掉，用例**没有红**。
    //     那不代表断言无效，而是说明**排序早就在 SQL 里**、Java 那行是冗余的。
    //     若不留这句，下一个人会「补一行排序让代码看起来更完整」—— 那会掩盖真正的排序来源。
    //   ⇒ 排序这条行为的守门人是 `listIsOrderedByRecentActivity`，而它守的是**整条读路径**
    //     （仓储换掉/查询改名，它就会红）。

    long total = all.size();
    int totalPages = (int) Math.max(1, (total + safeSize - 1) / safeSize);
    int effectivePage = page == null ? 1 : page;
    if (effectivePage == -1) {
      // ★ 与历史消息同一条：`-1` = 最后一页（聊天窗口先看最近几条）
      effectivePage = totalPages;
    }
    effectivePage = Math.min(Math.max(1, effectivePage), totalPages);

    int from = Math.min((effectivePage - 1) * safeSize, all.size());
    int to = Math.min(from + safeSize, all.size());
    List<ConversationListItem> items = all.subList(from, to).stream().map(this::toItem).toList();
    // ★ PageResult 的分量顺序是 (list, total, page, size) —— 注意 	otal 在第二位。
    return new PageResult<>(items, total, effectivePage, safeSize);
  }

  /**
   * 契约 {@code Conversation} 的线上形状（只含本批能给的字段）。
   *
   * @param id 会话 id
   * @param title 标题
   * @param appId 所属应用（{@code null} = 通用问答）
   * @param appName 应用名（★ 取不到时给空串 —— 契约里它是必填 {@code string}， 给 {@code null} 会让前端渲染出 {@code
   *     undefined}）
   * @param turnCount 轮数（= 消息条数，与 {@code Conversation#messageCount()} 同义）
   * @param updatedAt 最近更新时间
   * @param ownerId 归属人
   */
  public record ConversationListItem(
      UUID id,
      String title,
      UUID appId,
      String appName,
      int turnCount,
      Instant updatedAt,
      UUID ownerId) {}

  private ConversationListItem toItem(Conversation conversation) {
    UUID appId = conversation.assistantId();
    String appName = "";
    if (appId != null) {
      // ★ 取不到应用名不是错误：应用可能已被删除，而"这条会话还在"是事实。
      //   给空串而不是 null —— 契约里 appName 是必填 string。
      // ★ `AppBindingPort.AppBinding` 只带三种事实（appId / modelId / kbIds），**不含应用名**
      //   ⇒ 这里给空串。★ 这是**已知缺口**，不是省略：契约里 `appName` 是必填 string，
      //   给 null 会让前端渲染出 `undefined`；给空串至少不破坏渲染。
      //   ★ 要补它需要端口多一个字段（或一次批量取名）—— 记在这里，别让下一个人以为是漏写。
      appName = "";
    }
    return new ConversationListItem(
        conversation.id(),
        conversation.title(),
        appId,
        appName,
        conversation.messageCount(),
        conversation.updatedAt(),
        conversation.userId());
  }
}
