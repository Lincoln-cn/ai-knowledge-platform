/*
 * 业务示例数据的开关（`app.seed.sample-data=true`）—— 见 `db/seed/V900__sample_business_data.sql`
 *
 * ★ 为什么用 Flyway 的 locations 而不是启动时的 `ApplicationRunner`：
 *   `V4__identity_seed_demo_accounts.sql` 的头注释已经把这条口径写过一遍 —— 迁移天然
 *   「只跑一次、顺序固定」，Runner 则要自己写幂等逻辑、执行时机还容易和测试的事务边界打架。
 *   这份 seed 灌的是库/文档/分段/应用，量比账号大，更需要"只跑一次"这件事由 Flyway 保证。
 *
 * ★ 那为什么又**不能**直接放进 `classpath:db/migration`：集成测试与它共用同一套迁移，
 *   默认灌业务数据会让一批「按条数断言」的用例红（不是产品坏了，是"库里有别人的数据"被
 *   断言成了"库里没有别人的数据"）。⇒ 单独一个 location + 一个显式开关，
 *   关着的时候 Flyway 连这个目录都不看，默认部署与全部测试的行为逐字节不变。
 */

package cn.lincoln.aiplatform.platform.seed;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@code classpath:db/seed} 追加到 Flyway 的扫描路径上 —— 只在 {@code app.seed.sample-data=true} 时生效。
 *
 * <p>★ 为什么是「追加」而不是「替换」：{@code spring.flyway.locations} 仍由使用方决定（默认 {@code
 * classpath:db/migration}，也可能被环境变量改过）。这里把已有的值原样带上、只在末尾加一格， 这样"打开示例数据"不会顺手把别人的迁移路径改掉。
 *
 * <p>★ 为什么必须走 {@link FlywayConfigurationCustomizer} 而不是自己 new 一个 {@code Flyway}： Boot 的自动配置此时已经把
 * {@code FlywayProperties} 读进来了，customizer 在它**之后**跑， 所以这里的追加一定赢；自己建 Flyway 则会丢掉 Boot 的 {@code
 * migrate-at-startup}、 {@code baseline}、{@code @ServiceConnection} 那一整套。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.seed", name = "sample-data", havingValue = "true")
class SampleDataFlywayConfiguration {

  /** 示例数据脚本所在的 location（Flyway 的 {@code classpath:} 前缀 + 资源目录）。 */
  static final String SAMPLE_SEED_LOCATION = "classpath:db/seed";

  /** Boot 默认的迁移路径；{@code spring.flyway.locations} 为空时用它兜底。 */
  private static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

  /**
   * 追加示例数据的 location。
   *
   * @param properties Boot 已经绑定好的 Flyway 配置（只读，不修改它）
   * @return 一个在 Boot 配置之后跑的 customizer
   */
  @Bean
  FlywayConfigurationCustomizer sampleDataSeedLocation(FlywayProperties properties) {
    List<String> locations = withSampleSeed(properties.getLocations());
    return configuration -> configuration.locations(locations.toArray(String[]::new));
  }

  /**
   * 在给定 location 列表末尾补上 {@code classpath:db/seed}（已含则不重复加）。
   *
   * @param configured 调用方配置的 location 列表（可为空）
   * @return 追加后的新列表
   */
  static List<String> withSampleSeed(List<String> configured) {
    List<String> locations = new ArrayList<>();
    if (configured == null || configured.isEmpty()) {
      locations.add(DEFAULT_MIGRATION_LOCATION);
    } else {
      locations.addAll(configured);
    }
    if (!locations.contains(SAMPLE_SEED_LOCATION)) {
      locations.add(SAMPLE_SEED_LOCATION);
    }
    return List.copyOf(locations);
  }
}
