package cn.lincoln.aiplatform.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * {@link EmbeddingRunner#isResourceExhaustion} 的边界 —— {@code #227} 处置 ③「资源不足 vs 文档坏了」。
 *
 * <p>★ 这条判据的意义：两者的**重试策略相反**。资源不足 ⇒ 等一会儿再试就好；文档坏了 ⇒ 重试一百次
 * 也一样。把它们写成同一句话，用户会一直重试一个永远好不了的东西（或者放弃一个其实没问题的文件）。
 */
class EmbeddingFailureClassificationTest {

  @Test
  @DisplayName("★ 连接池打满（CannotGetJdbcConnectionException）归为资源不足")
  void connectionPoolExhaustionIsResourceFailure() {
    RuntimeException error =
        new CannotGetJdbcConnectionException(
            "Failed to obtain JDBC Connection", new java.sql.SQLException("pool timeout"));
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isTrue();
  }

  @Test
  @DisplayName("★ 嵌套在 cause 里也算（真实链路里它常被包一层）")
  void nestedCauseIsStillDetected() {
    RuntimeException error =
        new IllegalStateException(
            "批写入失败", new DataAccessResourceFailureException("connection is not available"));
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isTrue();
  }

  @Test
  @DisplayName("★ 数据本身有问题（唯一约束冲突）不算资源不足 —— 那类重试一百次也一样")
  void dataIntegrityViolationIsNotResourceFailure() {
    RuntimeException error = new DataIntegrityViolationException("duplicate key value");
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isFalse();
  }

  @Test
  @DisplayName("普通业务异常不算资源不足")
  void plainRuntimeExceptionIsNotResourceFailure() {
    assertThat(EmbeddingRunner.isResourceExhaustion(new IllegalArgumentException("段数不一致")))
        .isFalse();
  }

  // ===== #303：把 #227 判据修复钉进仓库内测试（此前只有 .build/ 里的探针脚本，CI 不跑）=====

  @Test
  @DisplayName("★ #227 修复①：SQLTransientConnectionException 本身就是资源不足（Hikari 抛的根）")
  void sqlTransientConnectionExceptionIsResourceFailure() {
    assertThat(
            EmbeddingRunner.isResourceExhaustion(
                new java.sql.SQLTransientConnectionException("connection is not available")))
        .isTrue();
  }

  @Test
  @DisplayName("★ #227 修复②：Hibernate 的固定措辞「Could not open JPA EntityManager for transaction」⇒ true")
  void hibernateEntityManagerWordingIsResourceFailure() {
    // #303 现场：上传 500 / 解析失败的外层就是这句（Hibernate PersistenceException 的固定 message）
    RuntimeException error =
        new PersistenceException("Could not open JPA EntityManager for transaction");
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isTrue();
  }

  @Test
  @DisplayName("★ #227 修复③：Hikari 的固定措辞「Connection is not available, request timed out」⇒ true")
  void hikariTimeoutWordingIsResourceFailure() {
    // 只有措辞命中、类型不在判据里 —— 单独钉住措辞这一根判据
    RuntimeException error =
        new IllegalStateException(
            "批写入失败",
            new RuntimeException(
                "Connection is not available, request timed out after 32310ms (total=10, active=10,"
                    + " idle=0, waiting=53)"));
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isTrue();
  }

  @Test
  @DisplayName("★ #303 现场真实根因链（PersistenceException ← SQLTransientConnectionException）⇒ true")
  void realWorldCauseChainIsResourceFailure() {
    RuntimeException error =
        new IllegalStateException(
            "批写入失败",
            new PersistenceException(
                "Could not open JPA EntityManager for transaction",
                new java.sql.SQLTransientConnectionException(
                    "Connection is not available, request timed out after 32310ms (total=10,"
                        + " active=10, idle=0, waiting=53)")));
    assertThat(EmbeddingRunner.isResourceExhaustion(error)).isTrue();
  }
}
