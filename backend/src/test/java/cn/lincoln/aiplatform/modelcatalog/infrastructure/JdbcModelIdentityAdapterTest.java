package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 模型元数据读取的<strong>归因</strong>（{@code #228}）—— 查询失败的原因必须各说各的。
 *
 * <p>★ 这个类的价值全在区分上：它只做一件事（{@code select base_model from ai_model where id = ?}），
 * 而这件事失败有三种完全不同的原因，下一步动作也完全不同：
 *
 * <ul>
 *   <li>查不到这一行 ⇒ 模型真没了 ⇒ 去重新选一个（404）；
 *   <li>查到了但 {@code base_model} 为空 ⇒ 登记不全 ⇒ 去补字段（400）；
 *   <li>查询根本没执行成（连接池耗尽/超时/库不可达）⇒ **数据库侧问题** ⇒ 稍后重试并查日志。
 * </ul>
 *
 * <p>★★ 修复前的形状是 {@code catch (Exception)} 全接成第一种（{@code #228} 的现场： 两份文档报「向量模型不存在：50abc1ea-…」，而该模型
 * {@code enabled=t}/{@code status=LOADED}， 同批另外 57 份用它算向量都成功）。这就是本仓头号病 {@code silent-failure}：
 * 把"基础设施暂时不可用"变成"配置错了"，用户按提示去改配置，而模型明明在。
 */
class JdbcModelIdentityAdapterTest {

  private static final UUID MODEL_ID = UUID.fromString("50abc1ea-486f-4228-9eed-888ea1242137");

  /** ★ 核心断言：连接失败不许被说成"模型不存在"。 */
  @Test
  void connectionFailureIsNotReportedAsMissingModel() {
    JdbcTemplate jdbc =
        failing(
            new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new IllegalStateException("pool exhausted")));

    assertThatThrownBy(() -> new JdbcModelIdentityAdapter(jdbc).baseModelOf(MODEL_ID))
        .as("连接池打满不是「模型被删了」—— 它是数据访问故障，必须原样抛出")
        .isInstanceOf(CannotGetJdbcConnectionException.class)
        .isNotInstanceOf(BusinessException.class)
        .hasMessageNotContaining("向量模型不存在");
  }

  /** 查不到这一行仍然是 404（既有行为不丢）—— 这是唯一该说"模型不存在"的分支。 */
  @Test
  void missingRowIsStillNotFound() {
    JdbcTemplate jdbc = failing(new EmptyResultDataAccessException(1));

    assertThatThrownBy(() -> new JdbcModelIdentityAdapter(jdbc).baseModelOf(MODEL_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("向量模型不存在")
        .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  /** 查到了但没登记基础模型名 ⇒ 400 + 说清要补哪个字段（不是 404，也不是"不存在"）。 */
  @Test
  void blankBaseModelIsBadRequestNotNotFound() {
    JdbcTemplate jdbc = returning("   ");

    assertThatThrownBy(() -> new JdbcModelIdentityAdapter(jdbc).baseModelOf(MODEL_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有登记基础模型名")
        .hasMessageNotContaining("不存在")
        .satisfies(
            e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.BAD_REQUEST));
  }

  /** 正常路径：拿到的就是库里那一列，不加工、不回退。 */
  @Test
  void returnsTheRegisteredBaseModel() {
    JdbcTemplate jdbc = returning("multilingual-e5-small");

    assertThat(new JdbcModelIdentityAdapter(jdbc).baseModelOf(MODEL_ID))
        .isEqualTo("multilingual-e5-small");
  }

  // ────────────────────────── 夹具 ──────────────────────────
  // 用匿名子类而不是 Mockito：这里要验的正是「被调用的那一刻抛什么」，
  // 重写方法比配置 mock 的 varargs 匹配器更直白，也不会因为签名变化而静默匹配失败。

  private static JdbcTemplate failing(RuntimeException boom) {
    return new JdbcTemplate() {
      @Override
      public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        throw boom;
      }
    };
  }

  private static JdbcTemplate returning(String value) {
    return new JdbcTemplate() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return (T) value;
      }
    };
  }
}
