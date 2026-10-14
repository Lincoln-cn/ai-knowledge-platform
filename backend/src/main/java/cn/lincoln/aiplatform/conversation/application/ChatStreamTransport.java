package cn.lincoln.aiplatform.conversation.application;

/**
 * 问答流的传输层<strong>常量</strong>（第 17 周 · 0.4.5）—— 心跳、闲置超时、落库 flush 的节奏。
 *
 * <h3>★★ 三处数值，各有出处，都不是"随手定的"</h3>
 *
 * <table>
 *   <tr><th>常量</th><th>值</th><th>出处</th></tr>
 *   <tr><td>{@link #HEARTBEAT_INTERVAL_MS}</td><td>20 000</td>
 *       <td>冻结契约 {@code frontend/src/api/stream-types.ts} 的 {@code HEARTBEAT_INTERVAL_MS}</td></tr>
 *   <tr><td>{@link #IDLE_TIMEOUT_MS}</td><td>60 000</td>
 *       <td>同上，{@code IDLE_TIMEOUT_MS}；{@code 03 §1.5} 把它定为一条<strong>终止路径</strong></td></tr>
 *   <tr><td>{@link #FLUSH_INTERVAL_MS} / {@link #FLUSH_CHARS}</td><td>500 / 200</td>
 *       <td>{@code 03 §1.4}：生成过程中的周期性 flush —— <strong>500ms 或 200 字，先到者</strong></td></tr>
 * </table>
 *
 * <h3>★ 为什么是常量 + 可覆盖的属性（{@link ChatStreamProperties}）而不是只有属性</h3>
 *
 * <p>两条数值<strong>关系</strong>是契约的硬约束（心跳 × 3 ≤ 闲置超时），它必须能被一条<strong>不依赖运行时配置</strong>的断言钉住：
 * 若只写成属性默认值，那条断言就变成"读默认值再比大小"——而默认值是可以被覆盖的，断言钉住的东西会随配置漂移。 所以：
 *
 * <ul>
 *   <li>契约值在这里是<strong>常量</strong>（本类），断言直接读它 ⇒ 任何一次"顺手把心跳改大一点"都会让那条断言红；
 *   <li>{@link ChatStreamProperties} 的默认值<strong>引用</strong>这些常量，测试可以整体覆盖成小值（压缩时间）， 但覆盖时仍然要过那条关系检查。
 * </ul>
 */
public final class ChatStreamTransport {

  /** 心跳间隔（毫秒）—— 契约 {@code HEARTBEAT_INTERVAL_MS}。无业务帧时按它发 {@code : keep-alive}。 */
  public static final long HEARTBEAT_INTERVAL_MS = 20_000L;

  /** 闲置超时（毫秒）—— 契约 {@code IDLE_TIMEOUT_MS}（两侧同值），也是服务端的 {@code IDLE_TIMEOUT} 收尾点。 */
  public static final long IDLE_TIMEOUT_MS = 60_000L;

  /** 落库 flush 的最长间隔（毫秒）—— {@code 03 §1.4} 的 500ms。 */
  public static final long FLUSH_INTERVAL_MS = 500L;

  /** 落库 flush 的字符阈值 —— {@code 03 §1.4} 的 200 字。 */
  public static final int FLUSH_CHARS = 200;

  private ChatStreamTransport() {}
}
