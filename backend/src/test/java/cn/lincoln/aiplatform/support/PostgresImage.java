package cn.lincoln.aiplatform.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试用的 PostgreSQL 容器 —— ★ <strong>全工程唯一一处</strong>定义"用哪张镜像、怎么声明它"。
 *
 * <h3>为什么是自制镜像而不是 {@code pgvector/pgvector:pg16}</h3>
 *
 * <p>设计册 {@code 09 §10.2 #3} 定的中文全文检索是 {@code to_tsvector('chinese_zh', …)}， 而 {@code chinese_zh} 的
 * parser 只能是 <strong>zhparser</strong> —— 用 {@code simple} 配置会把<strong>整段中文当成一个
 * token</strong>（症状是"全文检索一条都搜不到"，而向量路照常）。 实测 {@code pgvector/pgvector:pg16} 的 {@code
 * pg_available_extensions} 里没有 zhparser， PGDG / Debian 也没有现成包 ⇒ 只能自制（{@code deploy/pg/Dockerfile}）。
 *
 * <p>而它<strong>仍然是 pgvector 镜像</strong>：那份 Dockerfile 两段式都拿 {@code pgvector/pgvector:pg16} 当 base ⇒
 * {@code vector} 原封不动。这一点由 {@link cn.lincoln.aiplatform.MigrationIntegrationTest} 与 {@code
 * deploy/verify_image_pg.py} 各自断言一次（测试侧 + 镜像侧）。
 *
 * <h3>★ 为什么要收成一处</h3>
 *
 * <p>换镜像之前，镜像名字符串在 <strong>19 个测试类</strong>里各写了一遍。 那种形状的危险不在于"多打几个字"，而在于：<strong>改镜像时只要漏掉一个文件，
 * 就会有一批测试仍然跑在旧镜像上，而且它们照样绿</strong> —— 正是本项目最贵的 "看起来在做、实际没生效"。
 *
 * <h3>为什么需要 {@code asCompatibleSubstituteFor("postgres")}</h3>
 *
 * <p>Testcontainers 看到镜像名里既不是 {@code postgres} 也不是 {@code pgvector} 时， 会**拒绝**把它当 PG 用（报 "Failed to
 * verify that image … is a compatible substitute for 'postgres'"）。这是一句需要理由的声明，理由如下、也只在这个文件里说一次：
 *
 * <ul>
 *   <li>它<strong>就是</strong> {@code postgres:16} 的衍生 —— 同一个 base、同一套 {@code pg_config} 路径、同一个 PG
 *       版本（实测 16.15）；
 *   <li>我们往里加的是 <strong>PG 扩展</strong>（zhparser + 本来就有的 vector）， 不是改 PG 行为；
 *   <li>★ <strong>声明兼容等于关掉 Testcontainers 这道自检</strong>，所以真正的把关必须 落在别处 —— 那是 {@code
 *       MigrationIntegrationTest.镜像同时带着pgvector与zhparser}： 它读 {@code pg_extension}
 *       断言两个扩展都在（换错镜像会红）。
 * </ul>
 *
 * <h3>跑之前必须先把镜像准备好（没有回退，这是刻意的）</h3>
 *
 * <pre>
 *   docker build -f deploy/pg/Dockerfile -t ai-knowledge-platform-pg:dev .
 *   python deploy/verify_image_pg.py          # 镜像自身的自检（11 项）
 * </pre>
 *
 * <p>镜像不在时 Testcontainers 会**明确报错**（拉不到这个 tag），不会静默降级到别的镜像 —— 这比"给个回退镜像"安全：回退会让 zhparser
 * 相关的东西在本地绿、在 CI 红，或者反过来。
 */
public final class PostgresImage {

  /** 与 {@code docker-compose.yml} 用的是同一个 tag（开发/测试/交付三处同一张镜像）。 */
  public static final String NAME = "ai-knowledge-platform-pg:dev";

  /**
   * 起一个 PG 测试容器。
   *
   * <p>★ 用工厂方法而不是让每个测试类自己 {@code new}：{@code asCompatibleSubstituteFor} 那句声明必须与镜像名同行出现，否则第 20
   * 个测试类很容易只抄镜像名、漏掉声明 （或者反过来抄错）。调用方只需要 {@code @Container @ServiceConnection} + 一个字段。
   */
  public static PostgreSQLContainer container() {
    // ★ 用裸类型：本版 Testcontainers（1.21.3）的 `org.testcontainers.postgresql.PostgreSQLContainer`
    //   不接受类型参数（`PostgreSQLContainer<?>` 会编译不过）；而原有 19 处调用点声明的也是裸类型，
    //   保持一致、改动最小。
    return new PostgreSQLContainer(
        DockerImageName.parse(NAME).asCompatibleSubstituteFor("postgres"));
  }

  private PostgresImage() {}
}
