package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.application.ConversationListService;
import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ★ #187 的会话生命周期判据：取一条 / 改名 / 软删。
 *
 * <p>它守的是三件"看起来只是 UI 小事"的事：
 *
 * <ol>
 *   <li><b>别人的会话不能读</b> —— 会话是私人对话（{@code 07 §3.1}），按 id 猜出来也不行；
 *   <li><b>改名真的落库</b> —— 不能只改返回值里的那个对象；
 *   <li><b>删除是软删且立刻从列表消失</b> —— 消息与引用快照必须留着（审计证据），列表查询本来就过滤 {@code deleted_at}。
 * </ol>
 */
class ConversationLifecycleServiceTest {

  private FakeConversations conversations;
  private ConversationListService service;

  @BeforeEach
  void setUp() {
    conversations = new FakeConversations();
    service = new ConversationListService(conversations, null);
  }

  @Test
  void getReturnsOnlyMyOwnConversation() {
    UUID owner = UUID.randomUUID();
    UUID id = seed(owner, "我的会话");

    assertThat(service.get(id, owner).title()).isEqualTo("我的会话");
    assertThatThrownBy(() -> service.get(id, UUID.randomUUID()))
        .as("★ 别人的会话按 id 猜也不给；且用 404 不泄露 id 是否存在")
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code())
        .isEqualTo(ErrorCode.NOT_FOUND);
  }

  @Test
  void renamePersistsAndRejectsBlankTitle() {
    UUID owner = UUID.randomUUID();
    UUID id = seed(owner, "旧标题");

    assertThat(service.rename(id, owner, "  合同审查  ").title()).isEqualTo("合同审查");
    assertThat(conversations.findById(id).orElseThrow().title())
        .as("★ 改名必须落库；只改返回值会在刷新后变回去")
        .isEqualTo("合同审查");
    assertThatThrownBy(() -> service.rename(id, owner, "   "))
        .as("★ 空白标题会让侧栏出现一条认不出来的会话")
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).code())
        .isEqualTo(ErrorCode.BAD_REQUEST);
  }

  @Test
  void deleteIsSoftAndHidesTheConversationImmediately() {
    UUID owner = UUID.randomUUID();
    UUID id = seed(owner, "待删会话");

    service.delete(id, owner);

    assertThat(conversations.findById(id).orElseThrow().deletedAt())
        .as("★ 软删：行还在、消息与引用快照还在，只写删除时间")
        .isNotNull();
    assertThat(service.list(owner, null, null, null, 1, 20).list()).as("★ 删完立刻从侧栏消失").isEmpty();
    assertThatThrownBy(() -> service.get(id, owner))
        .as("★ 直接按 id 读也读不到")
        .isInstanceOf(BusinessException.class);
  }

  private UUID seed(UUID owner, String title) {
    UUID id = UUID.randomUUID();
    conversations.save(new Conversation(id, owner, null, title));
    return id;
  }

  /** 只实现本用例需要的读/写；水位类方法不参与生命周期。 */
  private static final class FakeConversations implements ConversationRepository {

    private final Map<UUID, Conversation> rows = new LinkedHashMap<>();

    @Override
    public Conversation save(Conversation conversation) {
      rows.put(conversation.id(), conversation);
      return conversation;
    }

    @Override
    public boolean raiseClassification(UUID id, ClassificationLevel refsMax) {
      return false;
    }

    @Override
    public boolean recordMessage(UUID id) {
      return false;
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<Conversation> listByUser(UUID userId) {
      return rows.values().stream()
          .filter(c -> c.userId().equals(userId))
          .filter(c -> c.deletedAt() == null)
          .toList();
    }

    @Override
    public Optional<Conversation> findLatestByUserAndAssistant(UUID userId, UUID assistantId) {
      return listByUser(userId).stream()
          .filter(c -> java.util.Objects.equals(c.assistantId(), assistantId))
          .findFirst();
    }
  }
}
