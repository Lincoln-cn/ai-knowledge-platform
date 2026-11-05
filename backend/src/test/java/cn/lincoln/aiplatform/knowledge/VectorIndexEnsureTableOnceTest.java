package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * issue #302 的闸门：建表 DDL（尤其那条 hnsw {@code create index}）每维度只许跑一次。
 *
 * <p><strong>它怎么"能红"</strong>：把 {@code JdbcVectorIndex} 里的 {@code ensuredDimensions}
 * 命中即返回的守卫去掉（回到"每段都跑 ensureTable"），{@code hundredUpsertsRunHnswCreateIndexExactlyOnce} 立刻红 —— 100 次
 * upsert 会让那条 {@code create index … using hnsw} 跑 100 次，断言要求的是 1。
 *
 * <p><strong>为什么数 DDL 而不数耗时</strong>：耗时断言在 CI 上是抖的（73ms × 100 是读数口径，不是稳定断言）； 这里数的是"那条 SQL
 * 被执行了几次"，跟机器快慢无关 —— 这才是 issue #302 判据①的原文口径 （"连续 upsert 100 次，那条 create index 执行次数 = 1"）。
 *
 * <p><strong>测的是生产装配好的 bean</strong>：通过 {@link VectorIndexPort} SPI 拿到真正的 {@code
 * JdbcVectorIndex}（不直接 new、 也不 import internal 类，避免被 Spring Modulith 的模块边界闸门判越界），DDL 计数靠替换成计数型
 * {@link JdbcTemplate}。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
class VectorIndexEnsureTableOnceTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 计数型 JdbcTemplate：只数 DDL，行为与原 JdbcTemplate 完全一致。 */
  static final class DdlCountingJdbcTemplate extends JdbcTemplate {

    final AtomicInteger createTableCount = new AtomicInteger();
    final AtomicInteger createHnswIndexCount = new AtomicInteger();
    final AtomicInteger createOtherIndexCount = new AtomicInteger();

    DdlCountingJdbcTemplate(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public void execute(String sql) throws DataAccessException {
      String trimmed = sql.trim().toLowerCase(Locale.ROOT);
      if (trimmed.startsWith("create table")) {
        createTableCount.incrementAndGet();
      } else if (trimmed.startsWith("create index") && sql.contains("using hnsw")) {
        createHnswIndexCount.incrementAndGet();
      } else if (trimmed.startsWith("create index")) {
        createOtherIndexCount.incrementAndGet();
      }
      super.execute(sql);
    }

    void reset() {
      createTableCount.set(0);
      createHnswIndexCount.set(0);
      createOtherIndexCount.set(0);
    }
  }

  @TestConfiguration
  static class CountingJdbcConfig {

    @Bean
    @Primary
    DdlCountingJdbcTemplate countingJdbcTemplate(DataSource dataSource) {
      return new DdlCountingJdbcTemplate(dataSource);
    }
  }

  @Autowired VectorIndexPort vectorIndex;
  @Autowired DdlCountingJdbcTemplate jdbc;

  /**
   * ★ 闸门本体（issue #302 判据①）：连续 upsert 100 次，那条 hnsw {@code create index} 只执行 1 次。
   *
   * <p>反证：去掉 {@code ensuredDimensions} 守卫 ⇒ 这里是 100 ⇒ 红。
   */
  @Test
  void hundredUpsertsRunHnswCreateIndexExactlyOnce() {
    // 清场 + 归零：别的用例/别的上下文可能已经建过这张表
    jdbc.execute("drop table if exists vec_embedding_384");
    jdbc.reset();
    for (int i = 0; i < 100; i++) {
      vectorIndex.upsert(entry(384));
    }
    assertThat(jdbc.createHnswIndexCount.get())
        .as(
            "issue #302 判据①：100 次 upsert 后那条 create index … hnsw 只能执行 1 次"
                + "（每段一次的写法就是 5517×73ms≈403s 的来源）")
        .isEqualTo(1);
    assertThat(jdbc.createTableCount.get()).as("建表也只跑一次").isEqualTo(1);
    assertThat(jdbc.createOtherIndexCount.get()).as("kb_id 表达式索引也只跑一次").isEqualTo(1);
    assertThat(countRows("vec_embedding_384")).as("100 段都得真的落库").isEqualTo(100);
  }

  /**
   * ★ 能力钉（issue #302 注意项）：表不存在时必须自己建 —— 首次部署的场景。
   *
   * <p>反证：把 {@code ensureTable} 整个删掉 ⇒ 这里抛 {@code 42P01} ⇒ 红。
   */
  @Test
  void firstUpsertCreatesTableAndIndexesWhenAbsent() {
    jdbc.execute("drop table if exists vec_embedding_512");
    jdbc.reset();
    vectorIndex.upsert(entry(512));
    assertThat(jdbc.createTableCount.get()).as("表不存在时第一条写入必须建表（首次部署场景）").isEqualTo(1);
    assertThat(indexNames("vec_embedding_512"))
        .as("表建好后两个索引（hnsw + kb_id）都得在")
        .contains("idx_vec_512_hnsw", "idx_vec_512_kb");
    assertThat(countRows("vec_embedding_512")).isEqualTo(1);
  }

  /**
   * ★ 失效场景钉（issue #302 注意项）：表被<strong>外部</strong>删掉后，下一条写入自愈，而不是抛 42P01。
   *
   * <p>口径见 {@code JdbcVectorIndex} 类注释：进程内按维度缓存 + 撞 42P01 自愈一次。
   *
   * <p>反证：保留缓存但去掉自愈重试 ⇒ 这里抛 {@code DataAccessException} ⇒ 红。
   */
  @Test
  void tableDroppedExternallyIsRecreatedByTheNextUpsert() {
    jdbc.execute("drop table if exists vec_embedding_256");
    vectorIndex.upsert(entry(256)); // 建表 + 标记
    jdbc.execute("drop table vec_embedding_256"); // 模拟外部 DBA 删表
    jdbc.reset();
    vectorIndex.upsert(entry(256)); // 必须自愈，而不是抛 42P01
    assertThat(jdbc.createTableCount.get()).as("自愈重建必须真的跑一次 DDL").isEqualTo(1);
    assertThat(jdbc.createHnswIndexCount.get()).isEqualTo(1);
    assertThat(countRows("vec_embedding_256")).as("重试的这一条得真的落库").isEqualTo(1);
  }

  /**
   * 防"错方向优化"：每<strong>维度</strong>各一次，不是每<strong>进程</strong>一次 —— 全局一次会让第二个维度的表永远不建。
   *
   * <p>反证：把守卫改成全局 AtomicBoolean ⇒ 这里 hnsw 计数是 1 ⇒ 红。
   */
  @Test
  void eachDimensionGetsItsOwnSingleDdlRun() {
    jdbc.execute("drop table if exists vec_embedding_768");
    jdbc.execute("drop table if exists vec_embedding_1024");
    jdbc.reset();
    for (int i = 0; i < 3; i++) {
      vectorIndex.upsert(entry(768));
      vectorIndex.upsert(entry(1024));
    }
    assertThat(jdbc.createHnswIndexCount.get())
        .as("两个维度 ⇒ hnsw create index 各跑一次 = 2（全局一次的错实现只有 1）")
        .isEqualTo(2);
  }

  private static VectorIndexPort.Entry entry(int dimension) {
    float[] vector = new float[dimension];
    java.util.Arrays.fill(vector, 0.1f);
    return new VectorIndexPort.Entry(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "正文", vector);
  }

  private int countRows(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private java.util.List<String> indexNames(String table) {
    // ★ 按名字判而不是数个数：pg_indexes 里主键那条也在（pkey + hnsw + kb = 3），
    //   数个数会把主键算进来、将来加第四条索引也会红 —— 判「这两个在不在」才是这件事的语义。
    return jdbc.queryForList(
        "select indexname from pg_indexes where tablename = ?", String.class, table);
  }
}
