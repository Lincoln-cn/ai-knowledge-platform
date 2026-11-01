package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.FileRef;
import cn.lincoln.aiplatform.conversation.domain.FileRefRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把请求体里的 {@code attachmentIds} 变成真正可检索的会话域文档（{@code #187} 判据 4）。
 *
 * <h3>★ 它接的是哪一段</h3>
 *
 * <p>{@code POST /api/v1/conversations/chat} 的 {@code ChatStreamBody.attachmentIds} 自 {code #187}
 * 第一批起就是 "收下但<strong>不消费</strong>"（形状兼容）。本类补上消费侧：问答那一轮开始后、组装流之前，把每个附件 id 解析成 字节（{@code
 * FileStorePort#read}）并按会话域登记一条 {@code kb_document} + {@code kb_segment}。
 *
 * <p>★ <strong>为什么放在控制器层、而不是塞进 {@code ChatService.ChatRequest}</strong>：{@code ChatRequest} 刻意
 * 不含附件（见它的 javadoc），而 {@code ChatService} 的两个构造器被既有测试与在途改动盯着 —— 往它身上加依赖
 * 会把"附件登记"这件<u>与对话生成无关</u>的事绑进对话服务。会话 id 取自这一轮的 {@code ChatTurnWriter.PreparedTurn}（{@code 13
 * §11.4.1}：域解析唯一入口那条纪律的同一思路，不从请求体再取一次）。
 *
 * <h3>★ 密级口径（口径 ②）</h3>
 *
 * <p>{@code 13 §11.5} ①：附件密级<strong>不许低于会话当前密级</strong>。这里取 {@link
 * Conversation#maxClassification()} 的<u>当前值</u>作为附件密级 —— 即"取等号"：
 *
 * <ul>
 *   <li>满足下限（等于下限，不会低于）；
 *   <li><strong>不</strong>触发 {@code §11.5} ②的"附件更高 ⇒ 会话抬升"：本批没有"选择附件密级"的输入面（请求体里 只有 {@code
 *       attachmentIds}，没有逐件的密级），凭空取一个更高值等于替用户决定；
 *   <li>与既有写侧一致：会话密级的抬升只有 {@code ConversationRepository#raiseClassification} 一处 （{@code #108} /
 *       {@code §11.7} 判据 ⑥）—— 本类不碰它。
 * </ul>
 *
 * <p>★ 未验证的边界（如实记在 {@code #187} 报告里）：如果将来请求体带上"每件附件的密级"，这里要改成取 {@code max(会话当前密级, 该件密级)} 并在更高时调
 * {@code raiseClassification}。今天没有那个输入，所以不预支。
 */
@Service
public class AttachmentRegistrar {

  private static final Logger log = LoggerFactory.getLogger(AttachmentRegistrar.class);

  private final FileRefRepository files;
  private final FileStorePort store;
  private final ConversationRepository conversations;
  private final ConversationAttachmentPort attachments;

  public AttachmentRegistrar(
      FileRefRepository files,
      FileStorePort store,
      ConversationRepository conversations,
      ConversationAttachmentPort attachments) {
    this.files = files;
    this.store = store;
    this.conversations = conversations;
    this.attachments = attachments;
  }

  /**
   * 把这一轮的附件登记进会话域（幂等；已有同名登记则复用）。
   *
   * <p>★ 空清单 / {@code null} ⇒ <strong>什么都不做</strong>：绝大多数问答没有附件，这条路径必须零成本（不查库、 不建文档）。
   *
   * <p>★ 失败口径：id 不是 UUID ⇒ 400（请求体坏了，说清楚是哪个 id）；id 查不到 或 <strong>不属于提问者</strong> ⇒
   * 404（两种情况答案一样，{@code 08 §4.0}：不泄漏存在性）。
   *
   * <p>★ 为什么"别人的附件"是 404 而不是 403：403 等于告诉调用方"这个 id 存在、只是不是你的" —— 那是存在性侧信道。
   *
   * @param conversationId 本次问答所属会话（来自 {@code PreparedTurn}，不是请求体）
   * @param ownerId 提问者（附件 owner 与它不同 ⇒ 404）
   * @param rawAttachmentIds 请求体里的 {@code attachmentIds}（可能含 null / 空白，逐条清洗）
   * @return 实际登记（或复用）的文档 id 清单，按入参顺序 —— 给日志与测试用
   */
  public List<UUID> register(UUID conversationId, UUID ownerId, List<String> rawAttachmentIds) {
    if (conversationId == null || ownerId == null) {
      // ★ 不能静默：调用点（ChatController）保证这两个非空；这条只是防"以后有人从别的入口调"
      throw new BusinessException(ErrorCode.BAD_REQUEST, "会话与提问者必填", "附件登记需要知道它属于哪个会话、谁传的");
    }
    List<UUID> ids = parse(rawAttachmentIds);
    if (ids.isEmpty()) {
      return List.of();
    }

    Conversation conversation =
        conversations
            .findById(conversationId)
            // ★ 会话不存在 / 不属于提问者：同一个答案（404）。prepare 刚建过，走到这里意味着请求体指向了别人的会话
            .filter(found -> ownerId.equals(found.userId()))
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "会话不存在",
                        "确认会话 id 属于你 —— 附件只登记到你自己的会话里（13 §11.3 第三支）"));

    ClassificationLevel classification = conversation.maxClassification();
    if (classification == null) {
      // ★ 库列非空（V32），null 只可能是实体没被完整加载 —— 与其猜一个密级，不如说清现状
      throw new BusinessException(
          ErrorCode.NOT_FOUND, "会话密级不可读", "会话缺少 max_classification，无法确定附件密级下限（13 §11.5 ①）");
    }

    List<UUID> documentIds = new ArrayList<>(ids.size());
    for (UUID attachmentId : ids) {
      FileRef file =
          files
              .findById(attachmentId)
              .filter(found -> ownerId.equals(found.ownerId()))
              .orElseThrow(
                  () ->
                      new BusinessException(
                          ErrorCode.NOT_FOUND,
                          "附件不存在：" + attachmentId,
                          "确认这个 id 来自 POST /api/v1/files 的返回，且是你自己上传的"));

      byte[] content = store.read(file.storageKey());
      documentIds.add(
          attachments.register(
              conversationId, ownerId, attachmentId, file.name(), content, classification));
    }
    log.info("会话 {} 的问答带 {} 个附件，已登记为会话域文档 {}", conversationId, ids.size(), documentIds);
    return documentIds;
  }

  /**
   * 把 {@code List<String>} 洗成 {@link UUID} 清单（去 null / 去空白 / 去重）。
   *
   * <p>★ 去重是必要的：同一个 id 传两遍会走两遍幂等查 —— 结果一样但白花两次"读文件 + 逐条日志"， 而且日志会让人以为传了两份。
   */
  private static List<UUID> parse(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>(raw.size());
    for (String value : raw) {
      if (value == null || value.isBlank()) {
        continue;
      }
      UUID id;
      try {
        id = UUID.fromString(value.trim());
      } catch (IllegalArgumentException e) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "附件 id 不是合法 UUID：" + value, "attachmentIds 里放的是上传接口返回的 id");
      }
      if (!ids.contains(id)) {
        ids.add(id);
      }
    }
    return ids;
  }
}
