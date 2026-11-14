package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.application.NodeTypeCatalog;
import cn.lincoln.aiplatform.assistant.application.WorkflowRunRecorder;
import cn.lincoln.aiplatform.assistant.application.WorkflowTriggerSweeper;
import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 调度器扫一轮的行为（{@code #295} / 0.7.3）。
 *
 * <p>★ 这一条<strong>能红</strong>：把 {@code claimTrigger} 的返回值忽略掉（抢不到也跑）， {@code
 * onlyOneRunnerWinsTheClaim} 会红；把 {@code missedPeriods} 写死成 0， {@code missedPeriodsAreRecorded} 会红。
 *
 * <p>★ 用 Mock 仓储而不是真库：这一条要断的是<strong>决策</strong>（该不该跑、错过了几轮）， 而"到期"这个谓词是 SQL 的事，由 {@code V99} 的索引与
 * {@code JpaAssistantRepository} 的查询负责 —— 两者分开测，才不会出现"用真库测决策、决策逻辑却藏在 SQL 里"的错位。
 */
class WorkflowTriggerSweeperTest {

  /**
   * 固定的"现在"：2026-11-16（周一）10:00。
   *
   * <p>★ 不用 {@code Instant.now()}：判"错过了几轮"是一条<strong>确定性</strong>的逻辑，若测试里的 now
   * 每次不同，断言只能写成范围，而范围断言在真出偏差时照样绿。
   */
  private static final Instant NOW = at(2026, 11, 16, 10, 0);

  private static Instant at(int y, int m, int d, int h, int min) {
    return LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant();
  }

  private AssistantRepository assistants;
  private WorkflowVersionRepository versions;
  private WorkflowRunRecorder recorder;
  private WorkflowTriggerSweeper sweeper;

  @BeforeEach
  void setUp() {
    assistants = mock(AssistantRepository.class);
    versions = mock(WorkflowVersionRepository.class);
    recorder = mock(WorkflowRunRecorder.class);
    sweeper =
        new WorkflowTriggerSweeper(assistants, versions, mock(NodeTypeCatalog.class), recorder);
  }

  private static Assistant scheduled(String cron, Instant nextAt) {
    UUID id = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    Assistant assistant =
        new Assistant(id, "定时", "", "", AssistantType.WORKFLOW, null, "", owner, owner);
    assistant.schedule(cron, nextAt, owner);
    return assistant;
  }

  @Test
  @DisplayName("到期且抢到 ⇒ 跑一次，来源标 CRON")
  void dueAndClaimedRunsOnce() {
    Assistant assistant = scheduled("0 9 * * *", NOW.minusSeconds(60));
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(true);
    when(versions.history(any())).thenReturn(List.of());

    assertThat(sweeper.sweepAt(NOW)).isEqualTo(1);
    // 没有已发布版本 ⇒ 不该"跑"，但必须留下失败记录（没人盯着的路径，静默是最大的问题）
    verify(recorder).recordFailure(any(), anyString(), anyString(), any(), eq("CRON"));
    verify(recorder, never()).run(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("抢不到（别的实例先改了）⇒ 这一次什么都不做")
  void onlyOneRunnerWinsTheClaim() {
    Assistant assistant = scheduled("0 9 * * *", NOW.minusSeconds(60));
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(false);

    assertThat(sweeper.sweepAt(NOW)).isZero();
    verify(recorder, never()).run(any(), any(), any(), any(), any());
    verify(recorder, never()).recordFailure(any(), anyString(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("周日该跑没跑，到周一才发现 ⇒ 只跑一次，记下漏了 1 轮")
  void missedPeriodsAreRecorded() {
    // 周日 09:00 该跑（这一轮现在补）；到周一 10:00 才发现 ⇒ 周一 09:00 那班也漏了，中间只隔一班。
    Instant expected = at(2026, 11, 15, 9, 0);
    Assistant assistant = scheduled("0 9 * * *", expected);
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(true);
    when(versions.history(any())).thenReturn(List.of());

    sweeper.sweepAt(NOW);

    ArgumentCaptor<Integer> missed = ArgumentCaptor.forClass(Integer.class);
    verify(assistants).claimTrigger(any(), eq(expected), any(), any(), missed.capture());
    assertThat(missed.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("停机三天 ⇒ 每天都漏了一次，逐次数得 3（不是用平均周期做除法）")
  void threeDaysDownCountsEachDay() {
    // 周五 09:00 该跑；到周一 10:00 才发现 ⇒ 周六、周日、周一 09:00 三班全漏。
    Instant expected = at(2026, 11, 13, 9, 0);
    Assistant assistant = scheduled("0 9 * * *", expected);
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(true);
    when(versions.history(any())).thenReturn(List.of());

    sweeper.sweepAt(NOW);

    ArgumentCaptor<Integer> missed = ArgumentCaptor.forClass(Integer.class);
    verify(assistants).claimTrigger(any(), eq(expected), any(), any(), missed.capture());
    assertThat(missed.getValue()).isEqualTo(3);
  }

  @Test
  @DisplayName("准时（迟到几秒）不算错过任何一轮")
  void onTimeIsNotMissed() {
    Instant expected = at(2026, 11, 16, 9, 0);
    Assistant assistant = scheduled("0 9 * * *", expected);
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(true);
    when(versions.history(any())).thenReturn(List.of());

    // 09:00 该跑，10:00 才扫到 —— 迟到一小时，但下一班是次日 09:00，中间没有别的班可漏
    sweeper.sweepAt(NOW);

    ArgumentCaptor<Integer> missed = ArgumentCaptor.forClass(Integer.class);
    verify(assistants).claimTrigger(any(), eq(expected), any(), any(), missed.capture());
    assertThat(missed.getValue()).isZero();
  }

  @Test
  @DisplayName("半配置（有 cron 没下次时刻）跳过，不抢也不跑")
  void halfConfiguredIsSkipped() {
    Assistant assistant = scheduled("0 9 * * *", NOW.minusSeconds(60));
    // 模拟"有人绕过实体直接写库"：把 next_at 清空
    org.springframework.test.util.ReflectionTestUtils.setField(assistant, "triggerNextAt", null);
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));

    assertThat(sweeper.sweepAt(NOW)).isZero();
    verify(assistants, never()).claimTrigger(any(), any(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("已发布版本存在且图能编译 ⇒ 真的跑，来源 CRON")
  void publishedVersionIsExecuted() {
    Assistant assistant = scheduled("0 9 * * *", NOW.minusSeconds(60));
    when(assistants.findDueTriggers(any(), anyInt())).thenReturn(List.of(assistant));
    when(assistants.claimTrigger(any(), any(), any(), any(), anyInt())).thenReturn(true);
    when(versions.history(any()))
        .thenReturn(
            List.of(
                version(
                    """
                    {"nodes":[{"id":"s","type":"START_NODE","data":{}}],"edges":[]}
                    """)));

    sweeper.sweepAt(NOW);

    // 图里只有 START 节点：编译能过，然后交给 recorder 跑（节点执行器的装配不在这条测试的范围里）
    verify(recorder).run(any(), any(), anyString(), eq(null), eq("CRON"));
  }

  private static WorkflowVersion version(String graph) {
    UUID id = UUID.randomUUID();
    UUID appId = UUID.randomUUID();
    return new WorkflowVersion(id, appId, 1, graph, "", UUID.randomUUID(), Instant.now());
  }
}
