package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.conversation.infrastructure.MemorySettingsStore;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 做梦时间必须<strong>在运行期真的生效</strong>（{@code #6}）。
 *
 * <h3>它守的是哪一种退化</h3>
 *
 * <p>改动之前，页面上有一个「做梦时间」输入框，库里有 {@code cv_memory_setting.dream_cron}， 但真正决定什么时候跑的是
 * {@code @Scheduled(cron = "${app.memory.dream-cron:…}")} —— 它在注册 bean
 * 时求值。于是"改了保存"这个动作，在界面上成功、在库里成功、在日志里没有任何异常， 而<strong>计划从来没变</strong>。这正是 {@code
 * silent-failure}：没有一条读数会红。
 *
 * <p>★ 所以本类断的不是"cron 字符串存进去了"，而是"库里那个时刻<strong>驱动了执行</strong>"。 用 {@code *​/1 * * * * *}（每秒）而不是等
 * 03:40 —— 判据要能在一秒级的测试里被证伪。
 */
class MemoryDreamSchedulerTest {

  private final MemoryDreamJob job = mock(MemoryDreamJob.class);
  private final MemorySettingsStore settings = mock(MemorySettingsStore.class);
  private final MemoryDreamScheduler scheduler = new MemoryDreamScheduler(job, settings);

  @AfterEach
  void tearDown() {
    scheduler.stop();
  }

  private static MemorySettingsView view(boolean enabled, String cron) {
    return new MemorySettingsView(enabled, 3, 5, true, cron, 90, null, null, null, null);
  }

  /** 判据：库里的 cron 表达式真的驱动了任务的执行（不是"注册了"就算）。 */
  @Test
  void databaseCronDrivesExecution() throws Exception {
    when(settings.load()).thenReturn(view(true, "*/1 * * * * *"));

    scheduler.start();

    assertThat(scheduler.activeCron()).as("注册的应该是库里的那一份").isEqualTo("*/1 * * * * *");
    assertThat(scheduler.nextRunAt()).as("nextRunAt 必须给得出下一次").isNotNull();

    Thread.sleep(2_500);
    verify(job, atLeastOnce()).dream();
  }

  /** 判据：总开关关着时既不注册、也不执行（关掉之后不该还在后台写 note）。 */
  @Test
  void disabledSettingsDoNotSchedule() throws Exception {
    when(settings.load()).thenReturn(view(false, "*/1 * * * * *"));

    scheduler.start();
    Thread.sleep(1_200);

    assertThat(scheduler.activeCron()).isNull();
    assertThat(scheduler.nextRunAt()).isNull();
    verify(job, never()).dream();
  }

  /** 判据：改了 cron 之后，新的下一次触发时刻按新表达式算（不再看旧的那一份）。 */
  @Test
  void changedCronReschedulesToTheNewExpression() {
    when(settings.load()).thenReturn(view(true, "0 40 3 * * *"));
    scheduler.start();
    assertThat(scheduler.nextRunAt().atZone(ZoneId.systemDefault()).getHour())
        .as("旧计划应该落在 03 点")
        .isEqualTo(3);

    when(settings.load()).thenReturn(view(true, "0 0 4 * * *"));
    scheduler.reschedule();

    assertThat(scheduler.activeCron()).isEqualTo("0 0 4 * * *");
    assertThat(scheduler.nextRunAt().atZone(ZoneId.systemDefault()).getHour())
        .as("新计划必须落在 04 点（旧计划没被取消的话这里还是 3）")
        .isEqualTo(4);
  }

  /** 判据：手工改库写坏 cron 时保留旧计划 —— 一条坏配置不该把记忆维护整个停掉。 */
  @Test
  void illegalCronKeepsThePreviousPlan() {
    when(settings.load()).thenReturn(view(true, "0 40 3 * * *"));
    scheduler.start();

    when(settings.load()).thenReturn(view(true, "这不是 cron"));
    scheduler.reschedule();

    assertThat(scheduler.activeCron()).as("旧计划必须还在").isEqualTo("0 40 3 * * *");
  }

  /** 判据：保存路径自己就把坏 cron 拦下来，不用等到调度器才发现。 */
  @Test
  void checkedRejectsIllegalCron() {
    assertThatThrownBy(() -> view(true, "这不是 cron").checked())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("做梦时间看不懂");
  }
}
