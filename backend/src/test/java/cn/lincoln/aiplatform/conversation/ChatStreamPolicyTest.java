package cn.lincoln.aiplatform.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.application.ChatStreamPolicy;
import org.junit.jupiter.api.Test;

/**
 * ★★ 流式策略的判据（`#181` → 「对话设置」）。
 *
 * <h3>为什么这条判据必须先存在</h3>
 *
 * <p>这套配置要落成「应用 > 通用会话 > 全局兜底」三层继承。继承有一个**只有继承才有**的坑： <b>两层各自都合法、拼起来非法</b>。
 *
 * <pre>
 *   通用会话：heartbeat = 20s，idle = 60s      → 20×3 = 60 ≤ 60  ✔ 合法
 *   应用：    idle = 25s（只想让这个应用更早收手）→ 25 > 0        ✔ 合法
 *   拼起来：  heartbeat 20s（继承）+ idle 25s   → 20×3 = 60 > 25  ✘ 心跳还没发就被判超时
 * </pre>
 *
 * <p>若不在解析时拒绝，症状是：**心跳还没发出去就被判吊死**，而日志里只有一句「生成超时」。 那正是本仓最贵的一类失败（silent-failure）。
 *
 * <p>★ 金丝雀：把 {@code heartbeatIntervalMs * 3 > idleTimeoutMs} 那段删掉 ⇒ 第二条必红。
 */
class ChatStreamPolicyTest {

  private static ChatStreamPolicy policy(long idle, long heartbeat) {
    return new ChatStreamPolicy(true, idle, heartbeat, 10_000, null, "测试");
  }

  @Test
  void validCombinationIsAccepted() {
    assertThatCode(() -> policy(60_000, 20_000)).doesNotThrowAnyException();
    // 边界：heartbeat×3 正好等于 idle 是合法的（连续丢 2 个心跳仍不该误判）
    assertThatCode(() -> policy(60_000, 20_000)).doesNotThrowAnyException();
  }

  @Test
  void heartbeatTooFastForIdleIsRejected() {
    assertThatThrownBy(() -> policy(25_000, 20_000))
        .as(
            "★★ 心跳 20s × 3 = 60s > idle 25s ⇒ 心跳还没发就被判吊死。"
                + "这正是「两层各自合法、拼起来非法」的形状 —— 必须在解析时拒绝，不能等到跑起来")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("心跳间隔 × 3");
  }

  @Test
  void nonPositiveValuesAreRejected() {
    assertThatThrownBy(() -> policy(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy(60_000, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChatStreamPolicy(true, 60_000, 20_000, 0, null, "测试"))
        .as("首字延迟预算必须为正")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void maxOutputTokensIsOptionalButPositiveWhenPresent() {
    assertThatCode(() -> new ChatStreamPolicy(true, 60_000, 20_000, 10_000, null, "测试"))
        .as("空 = 不限制（交给模型自身）")
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new ChatStreamPolicy(true, 60_000, 20_000, 10_000, 0, "测试"))
        .as("0 不是「不限制」—— 那会生成 0 个 token")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最大输出");
  }

  @Test
  void illegalCombinationBecomesAReadableBadRequest() {
    IllegalArgumentException invalid =
        (IllegalArgumentException)
            org.assertj.core.api.Assertions.catchThrowable(() -> policy(25_000, 20_000));

    var error = ChatStreamPolicy.asBadRequest(invalid);

    assertThat(error.code()).isEqualTo(cn.lincoln.aiplatform.platform.domain.ErrorCode.BAD_REQUEST);
    assertThat(error.getMessage()).as("★ 报错要能看出是哪两个数冲突（只说『配置不合法』等于让用户去猜）").contains("心跳间隔 × 3");
    assertThat(error.hint()).as("★ hint 要指向「三层继承」这个真正的原因，否则用户只会去改自己那一层的数").contains("三层继承");
  }
}
