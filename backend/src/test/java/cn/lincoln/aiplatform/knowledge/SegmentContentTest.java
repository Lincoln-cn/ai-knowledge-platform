package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.knowledge.domain.SegmentContent;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * {@code SEG-I1} 金丝雀（第 7 周 · 0.2.2 批 1）—— 超长 chunk <strong>必须被拒</strong>，不是静默截断。
 *
 * <p>★ 这条不变量要解决的是一个真实事故形态（{@code 02 §2.3}）： 向量模型上下文约 1K token，超出部分被<strong>静默截断</strong> —— 界面允许切到
 * 4096 字符，运营在完全不知情的情况下切出大量"只被向量看到前半段"的分段， 召回变差却<strong>无处提示</strong>。
 *
 * <p>处理方式（{@code 02 §2.3}）：<strong>值对象构造即校验</strong>，超限拒绝并给可执行建议。 同一个物理限制，原来是隐性损失，现在是显性规则。
 *
 * <p>★ <strong>金丝雀判据</strong>：把上限调小也要红 —— 上限不是硬编码常量，而是随向量模型变化的配置（{@code 02}）。 若有人把校验改成"超过 N
 * 才拒"的固定数，或改成静默截断，下面的用例会立刻红。
 */
class SegmentContentTest {

  // ────────────────────────── 1 · 超长必须拒绝 + 可读原因 ──────────────────────────

  /**
   * ★ 拒绝时给<strong>可读原因</strong>（{@code DOC-I2} 同族纪律）： 说清"多少字 / 上限多少 / 怎么办"，而不是抛一个
   * NullPointerException 或截断后默默返回。
   */
  @Test
  void 超长内容构造即拒绝_并给出可读原因() {
    String longText = "长".repeat(2100);
    int maxChars = 2000;

    assertThatThrownBy(() -> SegmentContent.of(longText, maxChars))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.code()).isEqualTo(ErrorCode.SEGMENT_TOO_LONG);
              assertThat(be.getMessage())
                  .as("可读原因必须说清实际长度与上限（02 §2.3 示例文案）")
                  .contains("2100")
                  .contains("2000");
              assertThat(be.hint()).as("hint 是一等字段（08 §4.1）：用户看完 message 要知道下一步做什么").isNotBlank();
            });
  }

  /** ★ 反例：<strong>不许静默截断</strong>。 若实现偷懒改成"截到上限再返回"， 这里会得到一个 2000 字的实例而不是异常 —— 测试立刻红。 */
  @Test
  void 超长内容绝不静默截断() {
    String longText = "截".repeat(3000);

    assertThatThrownBy(() -> SegmentContent.of(longText, 1000))
        .as("静默截断 = 用户以为整段都进了向量（02 §2.3 的事故形态）")
        .isInstanceOf(BusinessException.class);
  }

  // ────────────────────────── 2 · 金丝雀：上限调小也要红 ──────────────────────────

  /**
   * ★ <strong>金丝雀</strong>：把上限调小到 10 字，50 字内容必须仍被拒。
   *
   * <p>它证明的是「校验真的对着<strong>传入的</strong>上限比长度」： 若有人把 {@code maxChars} 写死成 4096、或校验方向写反（{@code >} 写成
   * {@code <}）、 或直接跳过校验， 这条用例会红 —— 而上面 2000 字的用例可能仍然绿（因为 2100 > 4096 也不成立）。
   */
  @Test
  void 金丝雀_把上限调小超长内容仍必须被拒() {
    int tinyLimit = 10;
    // 用 repeat 构造而不是手数汉字 —— 手数字数是最容易看走眼的夹具错误
    String text = "内容".repeat(6); // 12 字 > 10

    assertThat(text.length()).as("夹具前提：内容必须超过小上限").isGreaterThan(tinyLimit);

    assertThatThrownBy(() -> SegmentContent.of(text, tinyLimit))
        .as("上限是参数不是常量 —— 调小上限后校验必须照样起效")
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.SEGMENT_TOO_LONG));
  }

  /** ★ 边界：恰好等于上限是<strong>合法</strong>的（{@code ≤} 而不是 {@code <}）。 写反不等号是最常见的手滑，边界用例专抓它。 */
  @Test
  void 恰好等于上限是合法的() {
    String text = "正".repeat(100);

    SegmentContent content = SegmentContent.of(text, 100);

    assertThat(content.text()).hasSize(100);
    assertThat(content.length()).isEqualTo(100);
  }

  // ────────────────────────── 3 · 空内容与非法上限 ──────────────────────────

  @Test
  void 空内容拒绝() {
    assertThatThrownBy(() -> SegmentContent.of("", 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
    assertThatThrownBy(() -> SegmentContent.of(null, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 非法上限拒绝() {
    assertThatThrownBy(() -> SegmentContent.of("正文", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SegmentContent.of("正文", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★ {@code SEG-I1} 的另一半：<strong>content 与 searchText 要分开存</strong>（{@code 12 §4.2}）。 前缀会挤占向量预算 ⇒
   * 约束对象是 {@code searchText} 的长度（{@code SEG-I5}）。 这里钉住：searchText 形态是"前缀 + 正文"，长度受同一个上限约束。
   */
  @Test
  void searchText含前缀时长度也要受限() {
    String prefix = "制度·采购部·差旅规定｜第三章";
    String body = "正".repeat(1000);
    int maxChars = 100;

    assertThatThrownBy(() -> SegmentContent.ofSearchText(prefix, body, maxChars))
        .as("前缀挤占预算（12 §10.2 风险 1）⇒ SEG-I5 约束的是 searchText 而不是 content")
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.SEGMENT_TOO_LONG));
  }
}
