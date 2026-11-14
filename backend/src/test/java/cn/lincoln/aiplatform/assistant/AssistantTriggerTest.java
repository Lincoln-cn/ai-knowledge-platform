package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantType;
import cn.lincoln.aiplatform.assistant.domain.TriggerType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 定时触发的三个实体动作（`#295` / 0.7.3）。
 *
 * <p>为什么这些断言放在实体上而不是服务上：三格的**含义**（哪一格该跟着哪一格清、顺延时 missed 从哪来）是领域知识，
 * 服务层只负责"什么时候调哪一个"。放在服务层断言就会变成"再写一遍实现"。
 */
class AssistantTriggerTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final Instant AT_0912 = Instant.parse("2026-11-13T09:12:00Z");

  private Assistant app() {
    return new Assistant(
        UUID.randomUUID(), "定时", "", "", AssistantType.WORKFLOW, null, "", OWNER, OWNER);
  }

  @Test
  @DisplayName("schedule：cron 与下次时刻一起写进去，类型是 CRON")
  void scheduleWritesCronAndNextAlongWithTheType() {
    Assistant app = app();

    app.schedule("0 9 * * 1-5", AT_0912, OWNER);

    assertThat(app.triggerType()).isEqualTo(TriggerType.CRON);
    assertThat(app.triggerCron()).isEqualTo("0 9 * * 1-5");
    assertThat(app.triggerNextAt()).isEqualTo(AT_0912);
  }

  @Test
  @DisplayName("schedule：cron 或下次时刻缺一个就拒绝，不许留下半份配置")
  void scheduleRejectsHalfAConfiguration() {
    Assistant app = app();

    assertThatThrownBy(() -> app.schedule(null, AT_0912, OWNER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> app.schedule("  ", AT_0912, OWNER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> app.schedule("0 9 * * 1-5", null, OWNER))
        .isInstanceOf(IllegalArgumentException.class);

    // 三次拒绝之后仍然"从没配过"（不是"配了一半"）
    assertThat(app.triggerType()).isNull();
    assertThat(app.triggerCron()).isNull();
    assertThat(app.triggerNextAt()).isNull();
  }

  @Test
  @DisplayName("clearTrigger：五格全清 —— 包括上一次跑过的时刻与漏跑次数")
  void clearTriggerWipesTheHistoryOfThePreviousAgreementToo() {
    Assistant app = app();
    app.schedule("0 9 * * 1-5", AT_0912, OWNER);
    app.markTriggerFired(AT_0912, AT_0912.plusSeconds(86_400), 3);

    app.clearTrigger(OWNER);

    // ★ lastAt / missed 也要清：它们描述的是"上一次那份配置跑得怎么样"。
    //   留着的话，重新开一个 cron 会立刻显示"上次是补跑，中间漏了 3 次" —— 而那是上一次的事。
    assertThat(app.triggerType()).isNull();
    assertThat(app.triggerCron()).isNull();
    assertThat(app.triggerNextAt()).isNull();
    assertThat(app.triggerLastAt()).isNull();
    assertThat(app.triggerMissed()).isZero();
  }

  @Test
  @DisplayName("markTriggerFired：记下这一跑的时刻、顺延下次、记下漏了几轮")
  void markTriggerFiredRecordsTheLastRunAndMovesNextForward() {
    Assistant app = app();
    app.schedule("0 9 * * *", AT_0912, OWNER);

    Instant firedAt = AT_0912.plusSeconds(60);
    Instant nextAt = AT_0912.plusSeconds(86_400);
    app.markTriggerFired(firedAt, nextAt, 2);

    assertThat(app.triggerLastAt()).isEqualTo(firedAt);
    assertThat(app.triggerNextAt()).isEqualTo(nextAt);
    assertThat(app.triggerMissed()).isEqualTo(2);
    // 顺延不能让 cron 或类型凭空消失：界面上"已定时"靠的是 cron 那一格还在
    assertThat(app.triggerCron()).isEqualTo("0 9 * * *");
    assertThat(app.triggerType()).isEqualTo(TriggerType.CRON);
  }
}
