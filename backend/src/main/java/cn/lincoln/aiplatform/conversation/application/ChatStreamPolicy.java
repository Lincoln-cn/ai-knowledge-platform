package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;

/**
 * 一次对话**实际生效**的流式策略（三层继承解析之后的结果）。
 *
 * <h3>三层继承（前者覆盖后者）</h3>
 *
 * <pre>
 * 应用 as_assistant.chat_*（可空）  →  通用会话 cv_chat_settings  →  全局 app.chat.*（yaml 兜底）
 * </pre>
 *
 * <p>★ 为什么要有"继承"而不是"每个应用都必须填全"：
 *
 * <ul>
 *   <li>绝大多数应用只用默认值，逼人填 6 个数字等于让配置变成噪声；
 *   <li>平台级想统一调（比如换到慢机器），只改通用会话一处即可，不必逐个应用改。
 * </ul>
 *
 * <h3>★★ 一条硬约束：{@code heartbeat * 3 <= idle}</h3>
 *
 * <p>与 {@link cn.lincoln.aiplatform.conversation.infrastructure.ChatStreamProperties} 同一条理由： 连续丢 2
 * 个心跳仍不该被判吊死。★ 但**这里要再校验一次**，因为三层继承可以让 "单看每层都合法、拼起来却非法"：应用把 idle 设成 30s、通用会话的心跳是 20s ⇒ 20*3=60 >
 * 30。 那种组合如果不在解析时拒绝，症状是"心跳还没发就被判超时"，而日志里只有一句超时。
 *
 * @param streamEnabled 是否流式输出
 * @param idleTimeoutMs 多久没字节写出判吊死（★ SSE 的唯一超时权威）
 * @param heartbeatIntervalMs 心跳间隔
 * @param firstTokenBudgetMs 首字延迟预算（B4）
 * @param maxOutputTokens 单轮最大输出 tokens（可空 = 不限）
 * @param origin 这三个数的来源说明（诊断用，写进 trace / 日志）
 */
public record ChatStreamPolicy(
    boolean streamEnabled,
    long idleTimeoutMs,
    long heartbeatIntervalMs,
    long firstTokenBudgetMs,
    Integer maxOutputTokens,
    String origin) {

  /**
   * 构造即校验（与 {@code ChatStreamProperties} 同一风格：值语义 + 建不出来就是非法）。
   *
   * @throws IllegalArgumentException 数值非法或 heartbeat 与 idle 的关系不成立
   */
  public ChatStreamPolicy {
    if (idleTimeoutMs <= 0) {
      throw new IllegalArgumentException("空闲超时必须为正（毫秒）：" + idleTimeoutMs);
    }
    if (heartbeatIntervalMs <= 0) {
      throw new IllegalArgumentException("心跳间隔必须为正（毫秒）：" + heartbeatIntervalMs);
    }
    if (heartbeatIntervalMs * 3 > idleTimeoutMs) {
      throw new IllegalArgumentException(
          "心跳间隔 × 3 必须 ≤ 空闲超时（连续丢 2 个心跳仍不该被判吊死）—— 实际 "
              + heartbeatIntervalMs
              + " × 3 = "
              + heartbeatIntervalMs * 3
              + " > "
              + idleTimeoutMs
              + "，来源："
              + origin);
    }
    if (firstTokenBudgetMs <= 0) {
      throw new IllegalArgumentException("首字延迟预算必须为正（毫秒）：" + firstTokenBudgetMs);
    }
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
      throw new IllegalArgumentException("单轮最大输出 tokens 必须为正：请留空表示不限制");
    }
  }

  /**
   * 把"非法组合"翻译成**可读的业务异常**（带修复建议）。
   *
   * <p>★ 为什么不在 {@link #ChatStreamPolicy} 的构造器里直接抛 {@code BusinessException}： 那是领域值对象，不该依赖 web
   * 层的错误码语义；转换放在这一层，测试可以直接断言两者。
   *
   * @param invalid 构造时抛出的非法参数异常
   * @return 带 hint 的业务异常（400）
   */
  public static BusinessException asBadRequest(IllegalArgumentException invalid) {
    return new BusinessException(
        ErrorCode.BAD_REQUEST,
        "流式配置不合法：" + invalid.getMessage(),
        "心跳间隔 × 3 必须 ≤ 空闲超时。★ 三层继承会把两层各自的合法值拼成非法组合 —— " + "检查应用级与通用会话的 idle / heartbeat 是否冲突");
  }
}
