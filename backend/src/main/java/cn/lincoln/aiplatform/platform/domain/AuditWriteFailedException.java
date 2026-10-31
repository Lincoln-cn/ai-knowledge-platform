package cn.lincoln.aiplatform.platform.domain;

/**
 * 审计写不进去（`#257` 判据 ④）。
 *
 * <h3>为什么单独一个异常类型，而不是沿用 {@code IllegalStateException}</h3>
 *
 * <p>审计失败是<b>服务端问题</b>：调用方重试不会成功，改参数也不会。而本工程的全局异常处理把 {@code IllegalStateException} / {@code
 * IllegalArgumentException} 统一映射成 <b>400</b>（"是你传错了"）——用在状态机违规上很对，
 * 用在审计失败上会把运维问题伪装成用户操作问题，正是本工程反复记录的"错误归类错误"（同 `#228` 把连接池打满说成「向量模型不存在」）。
 *
 * <p>⇒ 单独一个类型，由全局处理器映射成 <b>500</b>，并带上「已回滚、别重试」的提示。
 *
 * <p>★ 它<b>必须</b>被抛出而不是被吞掉：审计与业务同事务，吞掉就等于"变更发生了却没有记录"（见 {@link AuditLogPort#record} 的纪律）。
 */
public class AuditWriteFailedException extends RuntimeException {

  public AuditWriteFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
