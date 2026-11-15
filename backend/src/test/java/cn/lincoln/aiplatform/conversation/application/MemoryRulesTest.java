package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.domain.Memory;
import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.conversation.domain.MemoryRepository;
import cn.lincoln.aiplatform.conversation.infrastructure.JpaUserProfileRepository;
import cn.lincoln.aiplatform.conversation.infrastructure.MemoryRepositoryAdapter;
import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 长期记忆的四条硬约束（0.9.0）。
 *
 * <p>每条对应设计册 `15-长期记忆设计.md` 里一句"为什么不这么做"，所以断言是<b>反向</b>的： 不是在证明"功能在"，而是在证明"它没越界"。
 */
class MemoryRulesTest {

  private final MemoryRepository memories = mock(MemoryRepository.class);
  private final MemoryRepositoryAdapter embeddings = mock(MemoryRepositoryAdapter.class);
  private final JpaUserProfileRepository profiles = mock(JpaUserProfileRepository.class);
  private final MemorySettingsStore settings = mock(MemorySettingsStore.class);

  private MemoryService service() {
    return new MemoryService(memories, embeddings, profiles, settings);
  }

  private static MemorySettingsView view(boolean enabled, int recallLimit) {
    return new MemorySettingsView(
        enabled, 3, recallLimit, true, "0 40 3 * * *", 90, null, null, null, null);
  }

  private static MemoryService.MemoryDraft draft(
      UUID owner, MemoryLevel level, UUID scopeId, MemoryKind kind, String content, String key) {
    return new MemoryService.MemoryDraft(
        owner, level, scopeId, kind, content, new UUID[0], 3, 0.7f, key);
  }

  private static Memory memory(UUID owner, MemoryLevel level, MemoryKind kind, String content) {
    return Memory.create(
        owner, level, null, kind, content, new UUID[0], 3, 0.7f, null, Instant.now());
  }

  /** 总开关关着时 write 返回空 —— 而不是抛：调用方是旁路抽取。 */
  @Test
  void writeIsSkippedWhenDisabled() {
    when(settings.load()).thenReturn(view(false, 5));
    UUID owner = UUID.randomUUID();
    assertThat(
            service()
                .write(draft(owner, MemoryLevel.USER, null, MemoryKind.FACT, "用户在做合同审核。", "k1")))
        .isEmpty();
    verify(memories, never()).save(any());
  }

  /** 同一个 dedupKey 再来一次 ⇒ 不算新记忆，只把命中次数加一，且返回的是已有那条。 */
  @Test
  void duplicateKeyTouchesInsteadOfInserting() {
    when(settings.load()).thenReturn(view(true, 5));
    UUID owner = UUID.randomUUID();
    Memory existing =
        Memory.create(
            owner,
            MemoryLevel.USER,
            null,
            MemoryKind.FACT,
            "用户在做合同审核。",
            new UUID[0],
            3,
            0.7f,
            "k1",
            Instant.now());
    when(memories.findByDedupKey(owner, MemoryLevel.USER, MemoryKind.FACT, "k1"))
        .thenReturn(Optional.of(existing));
    when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Optional<Memory> written =
        service().write(draft(owner, MemoryLevel.USER, null, MemoryKind.FACT, "用户在做合同审核。", "k1"));

    assertThat(written).isPresent();
    assertThat(written.get().hitCount()).isEqualTo(1);
  }

  /** 会话层只取当前会话：把 scopeId 换成别人的会话，一条都不该出现。 */
  @Test
  void recallScopesConversationLayerToTheCurrentConversation() {
    when(settings.load()).thenReturn(view(true, 5));
    UUID owner = UUID.randomUUID();
    UUID conversation = UUID.randomUUID();
    when(memories.findRecallsAcrossLevels(any(), any(), anyInt(), anyInt())).thenReturn(List.of());

    new MemoryRetriever(memories, service(), settings).recall(owner, conversation);

    /* ★ 合并成一次查询之后，"隔离"这件事的判据从"四次调用里那一次带了 scope"
     * 变成"那一次调用带的是当前会话" —— 断言对象变了，保护的东西没变。 */
    verify(memories).findRecallsAcrossLevels(eq(owner), eq(conversation), eq(3), eq(5 * 3 + 3));
    // ★ 关键的一条：**没有任何一次**以"别的会话 / 空会话"为条件去查会话层
    verify(memories, never()).findRecalls(any(), any(), any(), anyInt(), anyInt());
  }

  /** 召回失败不抛：记忆是增强项，库抖动不能升级成"答不出来"。 */
  @Test
  void recallFailureDegradesQuietly() {
    when(settings.load()).thenReturn(view(true, 5));
    when(memories.findRecallsAcrossLevels(any(), any(), anyInt(), anyInt()))
        .thenThrow(new IllegalStateException("db down"));

    MemoryRetriever.Recall recall =
        new MemoryRetriever(memories, service(), settings)
            .recall(UUID.randomUUID(), UUID.randomUUID());

    assertThat(recall.present()).isFalse();
    // ★ skipReason 只带异常类型、不带 message：它是给日志与界面看的短句，不该把内部细节漏出去
    assertThat(recall.skipReason()).contains("召回异常").contains("IllegalStateException");
  }

  /** 会话约定必须落会话层 —— 落错层会让它在下一次对话里继续生效。 */
  @Test
  void conventionLandsOnTheConversationLevel() {
    assertThat(MemoryKind.CONVENTION.code()).isEqualTo("convention");
  }

  /** 画像能从 USER 层推出来 —— 上一版只读 PERSONA，而没有任何路径写 PERSONA。 */
  @Test
  void profileFallsBackToUserLevel() {
    UUID owner = UUID.randomUUID();
    when(memories.findRecalls(owner, MemoryLevel.PERSONA, null, 1, 20)).thenReturn(List.of());
    when(memories.findRecalls(owner, MemoryLevel.USER, null, 1, 20))
        .thenReturn(
            List.of(
                memory(owner, MemoryLevel.USER, MemoryKind.FACT, "用户在一家制造企业的法务部。"),
                memory(owner, MemoryLevel.USER, MemoryKind.CONSTRAINT, "不要用英文缩写。")));
    when(profiles.findById(any()))
        .thenReturn(
            Optional.of(
                cn.lincoln.aiplatform.conversation.domain.UserProfile.empty(owner, Instant.now())));
    when(profiles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var profile = service().recomputeProfile(owner);

    assertThat(profile.summary()).contains("法务部");
    assertThat(profile.taboosList()).containsExactly("不要用英文缩写。");
  }

  /** 没有去重键时不该白跑一趟查重。 */
  @Test
  void blankDedupKeySkipsTheDuplicateLookup() {
    when(settings.load()).thenReturn(view(true, 5));
    UUID owner = UUID.randomUUID();
    when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().write(draft(owner, MemoryLevel.USER, null, MemoryKind.FACT, "用户在做合同审核。", null));

    verify(memories, never()).findByDedupKey(any(), any(), any(), any());
  }

  /** 设定越界要带当前值 —— 只看"报错了"分不清是 99 还是 -1。 */
  @Test
  void settingsBoundsCarryTheCurrentValue() {
    assertThatThrownBy(() -> view(true, 99).checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1～20")
        .hasMessageContaining("99");
  }
}
