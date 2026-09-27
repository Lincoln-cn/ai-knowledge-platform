package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 迁移管线与数据库地基（{@code ROADMAP §4.7.3} 第 1 周测试项 ⑤）。
 *
 * <p>★ 起<strong>真的 PostgreSQL</strong>，不是 H2、也不 mock 数据库（{@code 09 §4.1 ⑤}）。
 * 理由：这个项目几乎全部的复杂度都在数据库上（向量、JSON 路径、全文、事务事件、行锁）， 用 H2 测它们等于没测。而在第 1 周就把真库接上，代价只是一个注解。
 *
 * <p>★ 镜像用 {@code pgvector/pgvector:pg16} 而不是官方 {@code postgres}： V1 迁移里的 {@code CREATE EXTENSION
 * vector} 依赖它。这条断言的作用是—— 当有人"顺手"把镜像换成官方版时，构建会红，而不是等到 M2 建向量表时才发现。
 */
@SpringBootTest
@Testcontainers
class MigrationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");

  @Autowired DataSource dataSource;

  @Test
  void V1迁移已执行且标记成功() throws Exception {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select success from flyway_schema_history where version = '1' order by"
                    + " installed_rank limit 1")) {

      assertThat(rs.next()).as("flyway_schema_history 里没有 V1 记录 ⇒ 迁移根本没跑").isTrue();
      assertThat(rs.getBoolean("success")).as("V1 执行失败").isTrue();
    }
  }

  @Test
  void pgvector扩展已安装() throws Exception {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("select extversion from pg_extension where extname = 'vector'")) {

      assertThat(rs.next()).as("vector 扩展不存在 ⇒ 镜像不是 pgvector（见 docker-compose.yml 的说明）").isTrue();
      assertThat(rs.getString("extversion")).isNotBlank();
    }
  }

  /**
   * ★ 应用真的能被 Spring 装起来 —— 这一条比它看起来重要。
   *
   * <p>它要同时满足：Security 责任链装配合法、Flyway 与 Hibernate 的 {@code validate} 都过、 Spring AI 的 ChatClient
   * 单例建成。任何一处配错，这个测试类会直接起不来， 而不是在某个测试方法里给出一个含糊的失败。
   */
  @Test
  void 应用上下文可启动() {
    assertThat(dataSource).isNotNull();
  }
}
