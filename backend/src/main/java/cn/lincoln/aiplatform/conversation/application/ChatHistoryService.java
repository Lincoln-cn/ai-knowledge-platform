package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话历史消息的**读取用例**（契约 {@code listMessages}；页面**已在调**它： {@code useChatSession#loadHistory} 的登录面分支）。
 *
 * <h3>契约给的两种取数方式（互斥，实现逐字对齐）</h3>
 *
 * <ul>
 *   <li>{@code page}/{@code size}：常规分页；★ <strong>{@code page=-1} = 最后一页</strong> （聊天窗口先看最近几条）；
 *   <li>{@code around}：以某条消息为中心取窗口；<strong>传了它就忽略 {@code page}</strong>； {@code size}
 *       在这里是<strong>窗口宽度</strong>（中心前后各约 {@code size/2}）；★ <strong>找不到该消息 ⇒ 404</strong>，不是静默回退第一页。
 * </ul>
 *
 * <h3>归属判定：看不见 ⇒ 404（不是 403）</h3>
 *
 * <p>与 {@link ChatStopService} / {@link ChatTurnWriter} 逐字同一条（{@code 07 §3.3} 纪律 2）：
 * "不存在"与"不属于我"走同一个出口、同一段文案 —— 403 等于告诉对方"这个 id 是存在的"。
 *
 * <p>★ 消息侧<strong>不再判一次</strong>可见性：{@code MessageRepository} 的类注释写死了 "消息的可见性完全由所属会话决定 ⇒
 * 收窄发生在取会话那一步"。
 */
@Service
public class ChatHistoryService {

  /** 每页上限（给一个上限，免得一次把整条会话拉出来）。 */
  private static final int MAX_PAGE_SIZE = 100;

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final SuggestionService suggestions;

  public ChatHistoryService(
      ConversationRepository conversations,
      MessageRepository messages,
      SuggestionService suggestions) {
    this.conversations = conversations;
    this.messages = messages;
    this.suggestions = suggestions;
  }

  /**
   * 消息 ⇒ 视图，并**批量**填上推荐追问。
   *
   * <p>★ 为什么要在这里填，而不是让前端在打开会话时自己问： 那样"打开任何一条老会话"都会触发一次模型调用（{@code generate} 只在没有存量时生成）。
   * 历史读是纯读路径，不该有生成副作用。
   *
   * <p>★ 一次 SQL 批量取（{@code storedFor}），不是逐条 —— 一页 20 条逐条查就是 20 次往返。
   *
   * @param page 这一页/窗口里的消息
   * @return 视图列表（顺序与入参一致）
   */
  private java.util.List<ChatMessageView> views(java.util.List<Message> page) {
    java.util.Map<java.util.UUID, java.util.List<String>> stored =
        suggestions.storedFor(page.stream().map(Message::id).toList());
    return page.stream()
        .map(m -> ChatMessageView.of(m, stored.getOrDefault(m.id(), java.util.List.of())))
        .toList();
  }

  /**
   * 读一页 / 一个窗口。
   *
   * @param conversationId 会话 id
   * @param userId 当前登录用户（★ 从 JWT 的 {@code sub} 取，不接受请求体传）
   * @param page 页码，{@code 1} 起；★ {@code -1} = 最后一页（契约）
   * @param size 页大小；{@code around} 档里它是窗口宽度
   * @param around 目标消息 id；非空 ⇒ **忽略 {@code page}**
   * @return 分页结果（{@code page} 回执的是**真正生效**的那一页）
   * @throws BusinessException 会话不存在 / 不属于当前用户（404）· {@code around} 目标不在该会话里（404）
   */
  @Transactional(readOnly = true)
  public PageResult<ChatMessageView> list(
      UUID conversationId, UUID userId, Integer page, Integer size, UUID around) {
    // ── ① 会话：看不见 ⇒ 404（与 ChatStopService 同一条）──
    Conversation conversation =
        conversations
            .findById(conversationId)
            .filter(found -> found.userId().equals(userId))
            .orElseThrow(() -> notFound("会话不存在", "它可能已被删除，或者不在你的可见范围内"));

    int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(1, size), MAX_PAGE_SIZE);

    // ── ② around 档：以目标为锚的窗口（★ 忽略 page）──
    if (around != null) {
      // 窗口宽度：中心前后各约 size/2（★ size=1 时取 1 —— 至少把目标的前后各一条带出来才有"上下文"）
      int radius = Math.max(1, safeSize / 2);
      Optional<MessageRepository.Window> window =
          messages.windowAround(conversation.id(), around, radius);
      if (window.isEmpty()) {
        // ★ 契约逐字："找不到该消息时应报 404，而不是静默回退到第一页"
        throw notFound("消息不存在", "它可能属于另一个会话，或者已被删除");
      }
      MessageRepository.Window found = window.get();
      // ★ 回执的 page 给 1：窗口不是按页码取的（契约里 around 与 page 互斥），
      //   给它一个确定值，而不是把请求里那个**被忽略**的 page 回声出去 —— 那会让调用方以为分页生效了
      // ★★ total 用**会话总数**（不是窗口条数）：前端拿它判断"窗口前还有没有更早的一页"，
      //   给窗口条数会让深链跳进来之后"加载更早"消失（与 mock 逐字对齐）
      return PageResult.of(views(found.messages()), found.total(), 1, safeSize);
    }

    // ── ③ 常规分页 ──
    //   ★ 偏移恒为 (page-1)*size 这一个形状（适配器那侧 `offset / size` 才与 PageRequest 的页码互逆）
    int requested = page == null ? 1 : page;
    // ★ page=-1 要先知道 total 才能折算"最后一页" ⇒ 先取第一页（一次库读，顺带拿到 total）；
    //   若折算出来就是第一页，直接用它（省掉第二次读）
    MessageRepository.Slice firstPage = messages.slice(conversation.id(), 0, safeSize);
    long total = firstPage.total();
    int lastPage = (int) Math.max(1, (total + safeSize - 1) / safeSize);
    // ★ page = -1 ⇒ 最后一页（契约）；越界（0 / 过大）⇒ 夹到合法区间，而不是返回空页 ——
    //   空页会让"聊天窗口打开是空的"看起来像"这条会话没有消息"
    int safePage = requested == -1 ? lastPage : Math.min(Math.max(1, requested), lastPage);
    if (safePage == 1) {
      return PageResult.of(views(firstPage.content()), total, 1, safeSize);
    }
    MessageRepository.Slice slice =
        messages.slice(conversation.id(), (safePage - 1) * safeSize, safeSize);
    return PageResult.of(views(slice.content()), slice.total(), safePage, safeSize);
  }

  private static BusinessException notFound(String message, String hint) {
    return new BusinessException(ErrorCode.NOT_FOUND, message, hint);
  }
}
