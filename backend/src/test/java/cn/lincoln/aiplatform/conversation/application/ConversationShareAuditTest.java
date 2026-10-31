package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.Conversation;
import cn.lincoln.aiplatform.conversation.domain.ConversationRepository;
import cn.lincoln.aiplatform.conversation.domain.ConversationShare;
import cn.lincoln.aiplatform.conversation.domain.ConversationShareRepository;
import cn.lincoln.aiplatform.conversation.domain.MessageRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 分享链接的**两端**都要留痕（`#258`：第五、六类动作）。
 *
 * <p>为什么分享值得单独一类审计：它把一份内部内容变成「有链接就能看」。这条动作的风险不在数据里， 而在**边界**上 ——
 * 所以「谁生成的、能看整个会话还是一句话、有效期多久、什么时候撤销的」必须事后能回答。
 *
 * <p>★ 这一条用 Mockito 而不是 Testcontainers：判据关心的是「调用点在哪、写进去什么」，不是 SQL。 起容器要 60 秒，而这里三条断言 0.1 秒就能验 ——
 * 判据越便宜，越有人愿意在改动后重跑。
 */
class ConversationShareAuditTest {

  private final ConversationRepository conversations = mock(ConversationRepository.class);
  private final MessageRepository messages = mock(MessageRepository.class);
  private final ConversationShareRepository shares = mock(ConversationShareRepository.class);
  private final RecordingAudit audit = new RecordingAudit();

  private ConversationShareService service;

  private final UUID conversationId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new ConversationShareService(conversations, messages, shares, audit);
    when(shares.save(any(ConversationShare.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void creatingAShareIsAuditedWithOnlyATokenPrefix() {
    stubOwnedConversation();
    service.create(conversationId, "CONVERSATION", null, null, actorId);

    assertThat(audit.records).as("★ 生成链接必须留痕 —— 它是「这份内容开始可以外发」的那一刻").hasSize(1);
    Recorded record = audit.records.get(0);
    assertThat(record.action()).isEqualTo(AuditAction.CONVERSATION_SHARE);
    assertThat(record.resourceType()).isEqualTo("CONVERSATION");
    assertThat(record.resourceId()).isEqualTo(conversationId.toString());
    assertThat(record.actorId()).isEqualTo(actorId);
    assertThat(record.detail()).containsEntry("scope", "CONVERSATION");

    String tokenPrefix = String.valueOf(record.detail().get("tokenPrefix"));
    assertThat(tokenPrefix).as("★ 审计里只放令牌前 8 位：完整令牌是访问凭证，抄进审计表等于给能读审计的人发入口").hasSize(8);

    ArgumentCaptor<ConversationShare> saved = ArgumentCaptor.forClass(ConversationShare.class);
    verify(shares).save(saved.capture());
    assertThat(saved.getValue().token())
        .as("★ 库里那条分享记录的令牌必须是完整的 —— 截断只发生在审计里，不许截真的")
        .hasSizeGreaterThan(8)
        .startsWith(tokenPrefix);
  }

  @Test
  void revokingAShareIsAuditedToo() {
    stubOwnedConversation();
    String token = "abcdef0123456789";
    when(shares.findByToken(token)).thenReturn(Optional.of(newShare(token, "CONVERSATION")));

    service.revoke(conversationId, token, actorId);

    assertThat(audit.records).as("★ 撤销也要留痕：链接的取消同样是一次边界变化").hasSize(1);
    Recorded record = audit.records.get(0);
    assertThat(record.action()).isEqualTo(AuditAction.CONVERSATION_UNSHARE);
    assertThat(record.detail()).containsEntry("tokenPrefix", "abcdef01");
    assertThat(record.detail()).containsEntry("scope", "CONVERSATION");
  }

  @Test
  void aRejectedShareRequestLeavesNoAuditTrail() {
    // 不是自己的会话 ⇒ requireOwned 抛 404（403 会暴露「这个 id 存在」）
    when(conversations.findById(conversationId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(conversationId, "CONVERSATION", null, null, actorId))
        .isInstanceOf(BusinessException.class);

    assertThat(audit.records).as("★ 没发生的动作不许留痕：审计里的每一条都必须是「确实做过的事」，否则复盘会被假记录误导").isEmpty();
    verify(shares, never()).save(any());
    verify(shares, never()).findByToken(anyString());
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private void stubOwnedConversation() {
    Conversation conversation = mock(Conversation.class);
    when(conversation.userId()).thenReturn(actorId);
    when(conversation.deletedAt()).thenReturn(null);
    when(conversation.maxClassification()).thenReturn(ClassificationLevel.INTERNAL);
    when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
  }

  private ConversationShare newShare(String token, String scope) {
    return new ConversationShare(
        UUID.randomUUID(),
        token,
        conversationId,
        null,
        scope,
        ClassificationLevel.INTERNAL,
        null,
        actorId,
        Instant.now());
  }

  private record Recorded(
      AuditAction action,
      String resourceType,
      String resourceId,
      UUID actorId,
      Map<String, Object> detail) {}

  /** 只记不写：判据只需要看「写了什么」，不需要数据库。 */
  private static final class RecordingAudit implements AuditLogPort {
    private final List<Recorded> records = new ArrayList<>();

    @Override
    public void record(
        AuditAction action,
        String resourceType,
        String resourceId,
        UUID actorId,
        Map<String, Object> detail) {
      records.add(new Recorded(action, resourceType, resourceId, actorId, detail));
    }
  }
}
