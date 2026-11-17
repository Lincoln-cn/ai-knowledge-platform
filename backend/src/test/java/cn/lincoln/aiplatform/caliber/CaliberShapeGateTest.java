package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第三道读源码闸门的「闸门自身失效」档（`#332` 判据 1/2/3）。
 *
 * <p>★ 为什么这条单独存在：另外两道闸门（{@code frontend/verify_permissions.py}、{@code
 * backend/tools/check_pending_endpoints.py}）能用<strong>退出码</strong>分档（{@code 3} = 闸门自身失效 / {@code 1}
 * = 检查不通过）。{@code CaliberShape} 是测试代码，没有退出码 —— 它的等价物是 {@link
 * CaliberShape.GateBrokenException}：类型名与消息前缀 {@code 闸门自身失效} 就是这一档的标识。
 *
 * <p>★ 判据 3 的"把锚点行故意改掉 ⇒ 必须以闸门失效退出"在 Java 侧就是读一个不存在的锚点： 必须抛 {@code
 * GateBrokenException}，<strong>而不是</strong> {@code AssertionError} （后者维护者会去查产品）也不是悄悄返回空串。
 */
class CaliberShapeGateTest {

  @Test
  @DisplayName("判据 · 锚点读不到时报「闸门自身失效」这一档，而不是断言失败")
  void unreadableAnchorIsAGateFailure() {
    CaliberShape.selfCheckUnreadableAnchorFailsAsGateBroken();
  }

  @Test
  @DisplayName("判据 · 闸门真的读得到锚点（否则上一条会靠「永远读不到」假绿）")
  void theAnchorIsActuallyReachable() {
    // ★ 这一条不是重复劳动：上一条断言"读不到 ⇒ 抛 GateBrokenException"，
    //   但如果目录永远读不到，它也会通过 —— 而那正是最糟的假绿（闸门恒亮）。
    //   所以必须同时证明"正常跑法下读得到"。
    assertThat(CaliberShape.migrationSourceRoot())
        .as("迁移源码目录必须真的能定位到（否则全部形状断言恒绿）")
        .isDirectory()
        .satisfies(root -> assertThat(root.resolve("platform")).isDirectory());
  }
}
