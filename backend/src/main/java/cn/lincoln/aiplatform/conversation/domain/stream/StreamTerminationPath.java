package cn.lincoln.aiplatform.conversation.domain.stream;

/**
 * 四条终止路径（SSE 契约 {@code frontend/src/api/stream-types.ts} 的 {@code StreamTerminationPath}）。
 *
 * <p>契约原文：{@code StreamTerminationPath = 'COMPLETE' | 'ABORT' | 'DISCONNECT' | 'IDLE_TIMEOUT'}，
 * 注释里逐条写明了各自的产出：
 *
 * <ul>
 *   <li>{@link #COMPLETE} —— 正常写完 ⇒ {@code done(GENERATED|FAILED)}
 *   <li>{@link #ABORT} —— 用户主动停止 ⇒ {@code done(STOPPED)}
 *   <li>{@link #DISCONNECT} —— 连接断开 / 半途断流 ⇒ {@code error?} + {@code done(FAILED)}
 *   <li>{@link #IDLE_TIMEOUT} —— 闲置超时 ⇒ {@code error} + {@code done(FAILED)}
 * </ul>
 *
 * <h3>★★ 本枚举存在的唯一理由：把"哪条路径产出哪一个终态"收成一处</h3>
 *
 * <p>四条路径分散在四个调用点（正常收尾 / 收到停止信号 / 输出流异常 / 定时器到期）。若每一处各自写一遍 {@code
 * done(什么状态)}，那么"某一条路径写错了状态"不会有任何地方报错 —— 症状是界面把"被停了"显示成"生成完了"，
 * 或者反过来。所以四条路径在这里<strong>各有一个常量</strong>，映射到终态只有 {@link #status()} 一处实现。
 *
 * <p>★ 枚举<strong>声明顺序刻意等于契约里的书写顺序</strong>（COMPLETE / ABORT / DISCONNECT / IDLE_TIMEOUT），
 * 这样"契约里多了一条路径而实现里没加"这件事，可以被"枚举常量数"这类断言直接钉住。
 *
 * <p>★ <strong>与设计册的一处口径差异（本批按契约实现，登记在报告里）</strong>：{@code 03 §1.5}（服务端那一侧）把 "连接断开"也归为 {@code
 * STOPPED}（"同样 flush → STOPPED"），而冻结契约的注释把 {@code DISCONNECT} 与 {@code IDLE_TIMEOUT} 都写成 {@code
 * done(FAILED)}。本批实现契约那一版 —— 契约是前端按它验收的东西（{@code 10 §8.3} "一个字节都不用改"）。差异未在本批单方面修任何一侧，只登记。
 */
public enum StreamTerminationPath {

  /** 正常写完。 */
  COMPLETE(StreamStatus.GENERATED),

  /** 用户主动停止（显式停止接口，{@code 03 §1.5}）。 */
  ABORT(StreamStatus.STOPPED),

  /** 连接断开 / 半途断流。 */
  DISCONNECT(StreamStatus.FAILED),

  /** 闲置超时（前端 60s 一帧未收到即判吊死，{@code stream-types.ts} 契约①）。 */
  IDLE_TIMEOUT(StreamStatus.FAILED);

  private final StreamStatus status;

  StreamTerminationPath(StreamStatus status) {
    this.status = status;
  }

  /**
   * 这条路径对应的终态。
   *
   * <p>★ 这是"四路径 → 三态"的<strong>唯一</strong>映射点，见类注释。
   *
   * @return 终态取值
   */
  public StreamStatus status() {
    return status;
  }

  /**
   * 走这条路径时，{@code done} 之前是否先发一条 {@code error}。
   *
   * <p>契约：{@code DISCONNECT} 的 {@code error} 是<strong>可选</strong>的（"半途断流"时上游可能什么都没说）， {@code
   * IDLE_TIMEOUT} 是<strong>必有</strong>的。本批<strong>两条都发</strong> —— 可选的那条发了不算违约，
   * 不发才会让用户看到一个<strong>没有原因</strong>的"失败"。{@code error} 之后<strong>仍然必须走 {@code done}</strong>：
   * 契约原文"error 不是终态，done 才是"。
   *
   * <p>★ 判据刻意写成"终态是不是 FAILED"，而不是再引入一个 {@code boolean} 常量：后者是同一件事的第二处口径， 一旦有人改了 {@link #status()}
   * 而忘了改那个布尔，就会出现"报了 error 却收在 GENERATED"的静默不一致。
   *
   * @return 是否先发一条 {@code error}
   */
  public boolean errorPrecedesDone() {
    return status == StreamStatus.FAILED;
  }
}
