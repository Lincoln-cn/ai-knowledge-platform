package cn.lincoln.aiplatform.conversation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 问答流的传输层时序（第 17 周 · 0.4.5）—— 心跳间隔、闲置超时，以及落库 flush 的节奏。
 *
 * <h3>★★ 契约里那两条数值关系是<strong>硬约束</strong>，所以它们在代码里</h3>
 *
 * <p>冻结契约 {@code frontend/src/api/stream-types.ts} 逐字写着：
 *
 * <pre>
 *   HEARTBEAT_INTERVAL_MS = 20_000   // 心跳间隔
 *   IDLE_TIMEOUT_MS       = 60_000   // 前端闲置超时（一帧都没收到即判吊死）
 *   ⇒ HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS（连续丢 2 个心跳仍不会被误判）
 * </pre>
 *
 * <p>★ 前端那 60s 是<strong>它自己的</strong>定时器；本类管的是<strong>服务端这一侧</strong>的同名两件事：
 * 服务端也要有自己的"多久没写出东西就判这一轮生成完了/吊死了"（{@code 03 §1.5} 的 {@code IDLE_TIMEOUT} 是一条<strong>终止路径</strong>，
 * 不是只有前端才有的概念）。两侧取同一个数，是为了让"用户看到的超时"与"服务端收尾的时间"对得上 —— 差太多会出现 "界面已经报超时、后端还在往库里写"。
 *
 * <p>★★ 那条数值关系<strong>刻意落在这里的一个不可绕过的检查上</strong>：只要有人把心跳调到"可能被自己的闲置超时误判"的值，
 * 应用<strong>起不来</strong>（而不是线上偶发地掐断长回答）。 症状对比写清楚：漏了这条检查 ⇒ 表现是<strong>偶发</strong>的
 * "长回答被截断"，而那会被当成"模型慢"。
 *
 * <p>★ 为什么默认值来自契约而不是各写一遍：{@code stream-types.ts} 是<strong>冻结</strong>的那一份 （{@code 10 §8.3}「SSE
 * 一个字节都不用改」）。本类的默认值直接引用 {@link ChatStreamTransport#HEARTBEAT_INTERVAL_MS} / {@link
 * ChatStreamTransport#IDLE_TIMEOUT_MS}，于是"改了一侧忘了另一侧"不可能发生。
 *
 * <h3>★ 为什么允许被覆盖（而默认值等于契约值）</h3>
 *
 * <p>心跳 20s / 超时 60s 在人工盯着界面时是对的，但在自动化测试里"等 20 秒看一个注释帧"不可接受。
 * 覆盖是给测试与联调用的（{@code @TestPropertySource}），<strong>不是</strong>给生产随意调参 ——
 * 生产要改这两个数，等于同时要改契约里的两个常量，那是一次有意的契约变更。
 *
 * @param heartbeatIntervalMs 心跳间隔（毫秒）。无业务帧时按它发 {@code : keep-alive} 注释帧
 * @param idleTimeoutMs 闲置超时（毫秒）。连续此时长一个字节都没写出去 ⇒ 按 {@code IDLE_TIMEOUT} 收尾
 * @param flushIntervalMs 落库 flush 的最长间隔（毫秒，{@code 03 §1.4}：500ms）
 * @param flushChars 落库 flush 的字符阈值（{@code 03 §1.4}：200 字）
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatStreamProperties(
    @DefaultValue("20000") long heartbeatIntervalMs,
    @DefaultValue("60000") long idleTimeoutMs,
    @DefaultValue("500") long flushIntervalMs,
    @DefaultValue("200") int flushChars) {

  /**
   * 构造即校验（不是 {@code @PostConstruct}）：值的合法性属于<strong>这个类型</strong>， 于是"直接 {@code new} 一个"的测试与"由 Boot
   * 绑定"的生产走的是同一条检查。
   *
   * @throws IllegalArgumentException 任一数值不合法（见类注释：那条关系是契约硬约束）
   */
  public ChatStreamProperties {
    if (heartbeatIntervalMs <= 0) {
      throw new IllegalArgumentException("心跳间隔必须为正：" + heartbeatIntervalMs);
    }
    if (idleTimeoutMs <= 0) {
      throw new IllegalArgumentException("闲置超时必须为正：" + idleTimeoutMs);
    }
    // ★★ 契约硬约束：连续丢 2 个心跳仍不会被（自己这一侧的）闲置超时误判
    if (heartbeatIntervalMs * 3 > idleTimeoutMs) {
      throw new IllegalArgumentException(
          "心跳间隔 × 3 必须不大于闲置超时（契约 stream-types.ts："
              + "HEARTBEAT_INTERVAL_MS × 3 ≤ IDLE_TIMEOUT_MS；连续丢 2 个心跳仍不该被判吊死）—— 实际 "
              + heartbeatIntervalMs
              + " × 3 = "
              + heartbeatIntervalMs * 3
              + " > "
              + idleTimeoutMs
              + "。这条不满足时，症状是长回答被自己掐断，而它看起来像'模型慢'");
    }
    if (flushIntervalMs <= 0) {
      throw new IllegalArgumentException("flush 间隔必须为正：" + flushIntervalMs);
    }
    if (flushChars <= 0) {
      throw new IllegalArgumentException("flush 字符阈值必须为正：" + flushChars);
    }
  }

  /** 契约 + {@code 03 §1.4} 的默认值（生产口径）。 */
  public static ChatStreamProperties defaults() {
    return new ChatStreamProperties(
        ChatStreamTransport.HEARTBEAT_INTERVAL_MS,
        ChatStreamTransport.IDLE_TIMEOUT_MS,
        ChatStreamTransport.FLUSH_INTERVAL_MS,
        ChatStreamTransport.FLUSH_CHARS);
  }
}
