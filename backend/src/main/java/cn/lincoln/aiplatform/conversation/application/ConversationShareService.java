package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.ConversationShare;
import cn.lincoln.aiplatform.conversation.domain.ConversationShareRepository;
import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话分享：生成 / 列出 / 撤销三条管理动作，外加公开面的解析。
 *
 * <h3>★★ 为什么归属判定回 <strong>404</strong> 而不是 403</h3>
 *
 * <p>"别人的会话"与"不存在的会话"对调用者必须是同一个答案：403 会说"这个 id 存在，只是不给你"， 而会话 id 是内部分配的 UUID，不应该 通过错误码被枚举出来。这就是
 * {@code requireOwned} 全文只有一种失败形状的原因。
 *
 * <h3>★★ 密级在创建时冻结，读取时重新过滤</h3>
 *
 * <p>{@code maxClassification} 记的是<strong>创建者当时</strong>的会话密级水位；读取时拿它当上限，并对每条消息的引用再过滤一遍。 所以"链接生成 20
 * 分钟后，原会话又引用了更密的资料"不会顺带泄露 —— 冻结的是上限，不是结果快照。
 */
@Service
public class ConversationShareService {

  private static final Set<String> SCOPES = Set.of("APP", "CONVERSATION", "MESSAGE");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConversationRepository conversations;
  private final MessageRepository messages;
  private final ConversationShareRepository shares;
  private final AuditLogPort audit;

  public ConversationShareService(
      ConversationRepository conversations,
      MessageRepository messages,
      ConversationShareRepository shares,
      AuditLogPort audit) {
    this.conversations = conversations;
    this.messages = messages;
    this.shares = shares;
    this.audit = audit;
  }

  @Transactional
  public ConversationShareView create(
      UUID conversationId, String rawScope, UUID messageId, Instant expiresAt, UUID actorId) {
    Conversation conversation = requireOwned(conversationId, actorId);
    String scope = parseScope(rawScope);
    if ("MESSAGE".equals(scope)) {
      if (messageId == null) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "消息链接必须指定一条消息",
            "scope=MESSAGE 时 messageId 不能为空；会话链接用 scope=CONVERSATION");
      }
      Message message =
          messages
              .findById(messageId)
              .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在", "它可能已被删除"));
      if (!conversationId.equals(message.conversationId())) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "消息不属于这个会话", "messageId 与路径里的会话 id 不匹配；不要拿另一会话的消息来生成链接");
      }
    } else if (messageId != null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "只有消息链接才需要 messageId",
          "会话链接的 messageId 必须为空；要分享单条消息请用 scope=MESSAGE");
    }
    if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "有效期必须在将来", "expiresAt 已过期；要长期有效请不传这个字段");
    }
    Instant now = Instant.now();
    ConversationShare share =
        shares.save(
            new ConversationShare(
                UUID.randomUUID(),
                newToken(),
                conversationId,
                messageId,
                scope,
                conversation.maxClassification(),
                expiresAt,
                actorId,
                now));
    // ★ 分享是最该留痕的一类动作：它把一份内部内容变成"有链接就能看"。
    //   明细只放令牌前 8 位 —— 完整令牌是访问凭证，抄进审计表等于给"能读审计的人"发入口。
    audit.record(
        AuditAction.CONVERSATION_SHARE,
        "CONVERSATION",
        conversationId.toString(),
        actorId,
        Map.of(
            "scope",
            scope,
            "tokenPrefix",
            prefixOf(share.token()),
            "messageId",
            messageId == null ? "" : messageId.toString(),
            "maxClassification",
            String.valueOf(share.maxClassification()),
            "expiresAt",
            expiresAt == null ? "" : expiresAt.toString()));
    return ConversationShareView.from(share);
  }

  @Transactional(readOnly = true)
  public List<ConversationShareView> list(UUID conversationId, UUID actorId) {
    requireOwned(conversationId, actorId);
    return shares.listByConversation(conversationId).stream()
        .map(ConversationShareView::from)
        .toList();
  }

  @Transactional
  public ConversationShareView revoke(UUID conversationId, String token, UUID actorId) {
    requireOwned(conversationId, actorId);
    ConversationShare share =
        shares
            .findByToken(token)
            .filter(found -> conversationId.equals(found.conversationId()))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "分享链接不存在", "它可能已被删除，或不属于这个会话"));
    share.revoke(Instant.now());
    ConversationShare revoked = shares.save(share);
    audit.record(
        AuditAction.CONVERSATION_UNSHARE,
        "CONVERSATION",
        conversationId.toString(),
        actorId,
        Map.of("scope", revoked.scope(), "tokenPrefix", prefixOf(revoked.token())));
    return ConversationShareView.from(revoked);
  }

  /**
   * 审计里只留令牌前 8 位。
   *
   * <p>八个字符足够把"这条审计记录"与"界面上那条链接"对上号（人复盘时手里有链接）， 又不足以单独构造出一个可用的公开地址。
   */
  private static String prefixOf(String token) {
    if (token == null) {
      return "";
    }
    return token.length() <= 8 ? token : token.substring(0, 8);
  }

  /**
   * 公开面读取：令牌本身就是正当性证明（存在 / 未撤销 / 未过期）。
   *
   * <p>失败文案分开写："不存在"与"已撤销 / 已过期"对管理员是不同的事，但对匿名访问者不必暴露更多 —— 这里仍分开， 是因为分享对话框会把 404 的 {@code message}
   * 原样显示给链接生成者自己看。
   */
  @Transactional(readOnly = true)
  public PublicConversationShareView resolvePublic(String token) {
    String normalized = token == null ? "" : token.strip();
    ConversationShare share =
        shares
            .findByToken(normalized)
            .orElseThrow(
                () ->
                    new BusinessException(ErrorCode.NOT_FOUND, "分享链接不存在", "检查链接是否完整，或让分享者重新生成一条"));
    if (share.revokedAt() != null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "分享链接已撤销", "分享者收回了这条链接；需要的话请对方重新生成");
    }
    if (share.expiresAt() != null && !share.expiresAt().isAfter(Instant.now())) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "分享链接已过期", "有效期到了；需要的话请对方重新生成一条");
    }
    Conversation conversation =
        conversations
            .findById(share.conversationId())
            .filter(found -> found.deletedAt() == null)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在", "原会话可能已被删除"));
    List<Message> visible;
    if ("MESSAGE".equals(share.scope()) && share.messageId() != null) {
      visible =
          messages.findById(share.messageId()).stream()
              .filter(message -> share.conversationId().equals(message.conversationId()))
              .toList();
    } else {
      visible = messages.listByConversation(share.conversationId());
    }
    return new PublicConversationShareView(
        conversation.title(),
        conversation.createdAt(),
        share.maxClassification(),
        share.expiresAt(),
        visible.stream().map(ChatMessageView::of).toList());
  }

  private Conversation requireOwned(UUID conversationId, UUID actorId) {
    Conversation conversation =
        conversations
            .findById(conversationId)
            .filter(found -> found.deletedAt() == null)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在", "它可能已被删除，或不在你的会话列表里"));
    if (actorId == null || !actorId.equals(conversation.userId())) {
      // 不区分"别人的"与"不存在"：会话 id 不应该被错误码枚举出来。
      throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在", "它可能已被删除，或不在你的会话列表里");
    }
    return conversation;
  }

  private static String parseScope(String raw) {
    String scope = raw == null ? "" : raw.strip().toUpperCase(java.util.Locale.ROOT);
    if (!SCOPES.contains(scope)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "分享范围只支持 APP / CONVERSATION / MESSAGE",
          "传了不认识的范围；会话链接用 CONVERSATION，单条消息用 MESSAGE");
    }
    return scope;
  }

  private static String newToken() {
    byte[] bytes = new byte[12];
    RANDOM.nextBytes(bytes);
    return "shr_" + HexFormat.of().formatHex(bytes);
  }
}
