package cn.lincoln.aiplatform.assistant.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 发布前校验的结果（契约 {@code PublishCheckResult}）—— 第 15 周判据①。
 *
 * <p>★ 形状是 <strong>{@code passed} + 逐项清单</strong>，不是"一个 boolean + 一句话"： 界面上要能说清"哪一项没过、为什么不
 * 过、怎么改"，否则用户只看到"不能发布"而不知道该改什么。 校验项来自 {@code 02 §273-277} 的不变量：
 *
 * <table>
 *   <caption>不变量与校验项</caption>
 *   <tr><th>key</th><th>不变量</th><th>判什么</th></tr>
 *   <tr><td>{@code MODEL_LOADED}</td><td>AS-I2</td><td>绑定的模型状态必须是"已加载成功"</td></tr>
 *   <tr><td>{@code KB_ENABLED}</td><td>AS-I3</td><td>绑定的知识库必须存在且处于启用状态</td></tr>
 *   <tr><td>{@code HAS_OUTPUT}</td><td>AS-I4</td><td>至少要有一项输出（提示词或知识库）</td></tr>
 *   <tr><td>{@code RETRIEVAL_RANGE}</td><td>AS-I5</td><td>检索参数在合法区间内</td></tr>
 * </table>
 *
 * @param passed 全部项都通过才为 true（★ 界面的"发布"按钮按它禁用，而服务端仍然独立判一次 —— 前端禁用只是体验）
 */
public record PublishCheckResult(boolean passed, List<Item> items) {

  /**
   * 一项校验。
   *
   * @param key 机器可判的键（前端按它做图标/跳转；不要用 label 去判）
   * @param label 人读的项名
   * @param passed 这一项过没过
   * @param detail ★ 不通过时的<strong>可读原因 + 怎么改</strong>；通过时为 null（字段不出现）
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Item(String key, String label, boolean passed, String detail) {

    public static Item pass(String key, String label) {
      return new Item(key, label, true, null);
    }

    public static Item fail(String key, String label, String detail) {
      return new Item(key, label, false, detail);
    }
  }

  public static PublishCheckResult of(List<Item> items) {
    return new PublishCheckResult(items.stream().allMatch(Item::passed), items);
  }

  /** 只把没过的项读成一句人话（错误响应与日志共用，避免两处各写一遍）。 */
  public String failureSummary() {
    return items.stream()
        .filter(item -> !item.passed())
        .map(item -> item.label() + "：" + item.detail())
        .reduce((a, b) -> a + "；" + b)
        .orElse("");
  }
}
