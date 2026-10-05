package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
 * <p>★ 镜像用 {@code ai-knowledge-platform-pg:dev}（自制，见 {@link
 * cn.lincoln.aiplatform.support.PostgresImage}）而不是官方 {@code postgres}： V1 迁移里的 {@code CREATE
 * EXTENSION vector} 依赖它。而自制镜像比 pgvector 官方镜像 <strong>还多一条要求</strong> —— 它必须带着 {@code zhparser}（`09
 * §10.2 #3` 的中文全文检索靠它）。 这条断言的作用是——当有人"顺手"把镜像换回去时，构建会红， 而不是等到中文全文检索"一条都搜不到"（那是**静默**的）才发现。
 */
@SpringBootTest
@Testcontainers
class MigrationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

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
  void 镜像同时带着pgvector与zhparser() throws Exception {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select extname from pg_extension order by extname")) {

      List<String> installed = new ArrayList<>();
      while (rs.next()) {
        installed.add(rs.getString("extname"));
      }

      // ★ 两条分开断言、各自给可读原因：它们坏掉的**症状完全不同**，
      //   合并成一句"扩展不全"会让排查从一个方向变成两个方向。
      assertThat(installed)
          .as("vector 扩展不存在 ⇒ 镜像不是 pgvector 系（见 docker-compose.yml 的说明）")
          .contains("vector");
      assertThat(installed)
          .as(
              "zhparser 不存在 ⇒ 中文全文检索会静默失效（`simple` 把整段中文当成一个 token）；"
                  + "镜像要用 deploy/pg/Dockerfile 建的那张，见 support/PostgresImage")
          .contains("zhparser");
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
