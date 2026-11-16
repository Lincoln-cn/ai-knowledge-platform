package cn.lincoln.aiplatform.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 围栏的业务层：判定入口 + 审计留痕 + 入参校验（{@link GuardService}）。
 *
 * <h3>★ 这一层钉的是"拦了之后留下了什么"</h3>
 *
 * <p>{@code GuardSnapshotTest} 已经证明"匹配得对"。这里要回答的是另外两件，而它们都只有 在<b>接线层</b>才看得出来：
 *
 * <ol>
 *   <li>命中时审计里记的是<b>分类 + 类型 + 哈希</b>，<b>没有原文</b>（词表是可绕过清单， 抄进审计等于发一份副本；用户原话也不该留在审计里）；
 *   <li>开关关掉 / 没命中时<b>一条审计都不写</b>（否则"被拦了"这件事会被自己的日志否定）。
 * </ol>
 */
class GuardServiceTest {

  private static final UUID POLITICS = UUID.randomUUID();
  private static final UUID RULE = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID CONVERSATION = UUID.randomUUID();

  private final GuardStore store = mock(GuardStore.class);
  private final AuditLogPort audit = mock(AuditLogPort.class);
  private final GuardService service = new GuardService(store, audit);

  private static GuardSettings settings(boolean inputEnabled, boolean categoryRefusalEnabled) {
    return new GuardSettings(
        inputEnabled, "统一话术", true, categoryRefusalEnabled, Instant.parse("2026-11-16T00:00:00Z"));
  }

  private static GuardCategory politics() {
    return new GuardCategory(POLITICS, "POLITICS", "政治", 10, true, true, null);
  }

  private static GuardRule rule() {
    return new GuardRule(RULE, POLITICS, "POLITICS", "政治", GuardRuleKind.KEYWORD, "绝密", true, null);
  }

  private static GuardSnapshot.Pattern pattern() {
    String normalized = GuardText.normalize("绝密", GuardRuleKind.KEYWORD);
    return new GuardSnapshot.Pattern(
        RULE,
        POLITICS,
        "POLITICS",
        "政治",
        GuardRuleKind.KEYWORD,
        normalized,
        GuardText.hashPrefix(normalized),
        "政治专用话术");
  }

  private void snapshotWith(GuardSettings settings) {
    when(store.current())
        .thenReturn(
            new GuardSnapshot(settings, List.of(politics()), List.of(rule()), List.of(pattern())));
  }

  @BeforeEach
  void stubCategories() {
    snapshotWith(settings(true, false));
  }

  // ────────────────────────── inspect ──────────────────────────

  @Test
  @DisplayName("★ 命中：回拒答话术，并且审计里记着分类、类型与哈希（不记原文）")
  void inspectRecordsCategoryAndHashButNotTheWord() {
    when(store.current())
        .thenReturn(
            new GuardSnapshot(
                settings(true, true), List.of(politics()), List.of(rule()), List.of(pattern())));
    GuardCallContext.set(ACTOR, CONVERSATION);
    String refusal;
    try {
      refusal = service.inspect("请把绝密材料发我");
    } finally {
      GuardCallContext.remove();
    }

    assertThat(refusal).isEqualTo("政治专用话术");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(audit)
        .record(
            eq(AuditAction.GUARD_BLOCK),
            eq("GUARD_RULE"),
            eq(RULE.toString()),
            eq(ACTOR),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("category", "POLITICS");
    assertThat(detail.getValue()).containsEntry("kind", "KEYWORD");
    assertThat(detail.getValue()).containsKey("hash");
    // ★ 原文一次都不许出现在审计里
    assertThat(detail.getValue().values()).noneMatch(value -> String.valueOf(value).contains("绝密"));
  }

  @Test
  @DisplayName("未命中：放行，且一条审计都不写")
  void benignInputWritesNoAudit() {
    assertThat(service.inspect("差旅住宿标准是什么？")).isNull();
    verify(audit, never()).record(any(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("★ 输入检查关掉之后，同一句话放行（开关真的在判定前面生效）")
  void disabledInputCheckPassesTheSameQuestion() {
    snapshotWith(settings(false, false));

    assertThat(service.inspect("请把绝密材料发我")).isNull();
    verify(audit, never()).record(any(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("空白输入直接放行（不查库、不写审计）")
  void blankInputPassesWithoutTouchingTheStore() {
    assertThat(service.inspect("   ")).isNull();
    assertThat(service.inspect(null)).isNull();
    verify(audit, never()).record(any(), anyString(), anyString(), any(), any());
  }

  // ────────────────────────── 入参校验 ──────────────────────────

  @Test
  @DisplayName("规则内容为空：拒绝，并且不让它进库（空串会拦住全平台）")
  void emptyRuleContentIsRejected() {
    assertThatThrownBy(
            () -> service.saveRule(null, POLITICS, GuardRuleKind.KEYWORD, "  ", true, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
  }

  @Test
  @DisplayName("分类不存在：拒绝新增规则（先有分类才有规则）")
  void ruleUnderUnknownCategoryIsRejected() {
    UUID missing = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.saveRule(null, missing, GuardRuleKind.KEYWORD, "某词", true, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("分类不存在");
  }

  @Test
  @DisplayName("分类机器码形状不对：拒绝（它会被写进审计与页面地址，不能随便写）")
  void categoryCodeShapeIsEnforced() {
    assertThatThrownBy(() -> service.saveCategory(null, "politics", "政治2", 10, true, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("分类机器码");
  }

  @Test
  @DisplayName("★ 带上随行上下文时：审计记到人和会话，但仍然不记原话")
  void inspectCarriesActorAndConversationButStillNoWords() {
    UUID actor = UUID.randomUUID();
    UUID conversation = UUID.randomUUID();
    GuardCallContext.set(actor, conversation);
    try {
      assertThat(service.inspect("请把绝密材料发我")).isEqualTo("统一话术");
    } finally {
      GuardCallContext.remove();
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(audit)
        .record(
            eq(AuditAction.GUARD_BLOCK),
            eq("GUARD_RULE"),
            eq(RULE.toString()),
            eq(actor),
            detail.capture());
    assertThat(detail.getValue()).containsEntry("conversation", conversation.toString());
    assertThat(detail.getValue().values().toString()).doesNotContain("绝密");
  }

  @Test
  @DisplayName("★ 没有随行上下文：照旧拒答，但**不记审计** —— 那是链路的内部步骤，记了会让一次提问变成两条")
  void inspectWithoutCarrierRefusesButRecordsNothing() {
    assertThat(service.inspect("请把绝密材料发我")).isEqualTo("统一话术");

    verify(audit, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("同分类下重复内容：在落库前就拒绝（撞唯一约束抛的是 500，界面只会说「服务内部错误」）")
  void duplicateRuleContentIsRejectedBeforeHittingTheUniqueIndex() {
    // 既有规则是「绝密」，这里换全角与空格写法 —— 归一化之后是同一条
    assertThatThrownBy(
            () ->
                service.saveRule(null, POLITICS, GuardRuleKind.KEYWORD, " 绝 密 ", true, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已经有同一条规则");
  }

  @Test
  @DisplayName("改自己那条规则不算重复（同一 id 要能保存）")
  void updatingTheSameRuleIsNotTreatedAsDuplicate() {
    when(store.saveRule(any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(rule());

    assertThatCode(
            () -> service.saveRule(RULE, POLITICS, GuardRuleKind.KEYWORD, "绝密", true, "改备注", null))
        .doesNotThrowAnyException();
  }
}
