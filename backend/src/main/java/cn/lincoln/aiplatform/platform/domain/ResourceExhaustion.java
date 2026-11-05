package cn.lincoln.aiplatform.platform.domain;

import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 这个异常是不是「资源不足」（连接池打满、库不可达），而不是「这份业务数据有问题」？
 *
 * <p>★ 为什么判据住在 platform 而不是某个应用模块：三处消费方共用**同一条**判据 —— 向量化收尾（{@code
 * EmbeddingRunner.isResourceExhaustion}，{@code #227}）、解析收尾（{@code DocumentService.failParse}，{@code
 * #303}）、HTTP 兜底（{@code GlobalExceptionHandler}，上传直接 500 那一路）。判据一旦分叉，同一个异常会在 三个地方得到两种说法 —— 那正是
 * {@code #303} 的病根：资源不足被说成「请确认文件可正常打开」。
 *
 * <p>★ 判据放窄：只认 Spring 的 {@link DataAccessResourceFailureException}（Hikari 等连接池拿不到连接时抛的 {@code
 * CannotGetJdbcConnectionException} 是它的子类）、JDBC 的 {@link SQLTransientConnectionException}（Hikari
 * 等不到连接时真正抛的就是它），以及 **被 Hibernate/JPA 包了一层后的固定措辞**。别把任何 {@code DataAccessException} 都算进来 ——
 * 唯一约束冲突、SQL 写错也是 DataAccessException，那类靠重试好不了，归成"资源不足"会让用户无限重试。
 *
 * <p>★ 三句措辞是按 {@code #227} / {@code #303} 的反证读数补的（2026-11-05）：闸门关掉、70 份一起跑时 40 份 EMBEDDING_FAILED 里
 * <b>25 份</b>报的是 「Could not open JPA EntityManager for transaction」—— Hibernate 把 Hikari 的超时包成了
 * {@code org.hibernate.exception.JDBCConnectionException}（再上一层是 {@code
 * jakarta.persistence.PersistenceException}），两种都不是 Spring 的异常 ⇒
 * 旧判据漏认，用户看到的那句话里没有任何"连接池"字样。测试的取证方法就是看这条链：
 *
 * <ul>
 *   <li>{@code "Connection is not available, request timed out after …"} —— HikariCP 的固定文案（超时那一层）
 *   <li>{@code "Unable to acquire JDBC Connection"} —— Hibernate {@code JDBCConnectionException}
 *       的固定文案
 *   <li>{@code "Could not open JPA EntityManager for transaction"} —— Hibernate {@code
 *       PersistenceException} 的固定文案
 * </ul>
 *
 * <p><b>不 import Hibernate 的类型</b>：platform 域不该依赖 ORM 实现，而这三句措辞是 Hibernate 与 Hikari 各自的固定文案（改动它会同时让
 * {@code EmbeddingFailureClassificationTest} 报红，正好提醒复核）。
 *
 * <p>包级之外公开：{@code GlobalExceptionHandler}（platform.web）与 knowledge 的应用层都要调。
 *
 * @see cn.lincoln.aiplatform.knowledge.application.EmbeddingRunner
 */
public final class ResourceExhaustion {

  private ResourceExhaustion() {}

  /**
   * 沿 cause 链判断：这条异常链的根因是不是「资源不足」。
   *
   * <p>★ 沿链找而不是只看最外层：真实链路里 Hikari 的超时会被包三层（{@code PersistenceException → JDBCConnectionException →
   * SQLTransientConnectionException}），最外层那句话里常常什么线索都没有。
   *
   * @param error 异常
   * @return 是否资源不足
   */
  public static boolean isResourceExhaustion(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof DataAccessResourceFailureException
          || current instanceof SQLTransientConnectionException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null
          && (message.contains("Connection is not available")
              || message.contains("Unable to acquire JDBC Connection")
              || message.contains("Could not open JPA EntityManager for transaction"))) {
        return true;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return false;
  }
}
