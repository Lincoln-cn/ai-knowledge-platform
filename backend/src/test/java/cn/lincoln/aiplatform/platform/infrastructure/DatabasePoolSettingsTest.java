package cn.lincoln.aiplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 连接池上限必须是<strong>声明出来的</strong>配置，而不是库的默认值（{@code #227} 处置建议 4）。
 *
 * <p>★ 为什么值得一条测试：启动自检里那条「向量化并发 &gt; 池上限」的判据，比较的是 {@code
 * spring.datasource.hikari.maximum-pool-size}。这个键不写，Spring 会安静地退回 Hikari 的默认 10 ——
 * 于是自检拿一个<strong>没有任何人声明过</strong>的数字去做判断，而部署方以为池是按自己的意图配的。 这正是本项目反复吃亏的形状：<em>读数看着合理，来源却不是配置</em>。
 *
 * <p>★ 这条测试读的是<strong>仓库里的 {@code application.yml} 本体</strong>（源码树），不是 classpath —— 与 {@code
 * SampleSecretDefaultsTest} 同一个理由：构建残留会让「声明掉了」这件事看不出来（{@code #267} 判据 3）。
 */
class DatabasePoolSettingsTest {

  private static final Path YML = Path.of("src/main/resources/application.yml");

  private static final Path RUNNER =
      Path.of(
          "src/main/java/cn/lincoln/aiplatform/platform/infrastructure/StartupSettingsWarningRunner.java");

  @Test
  void poolSizeIsDeclaredInsteadOfRelyingOnTheLibraryDefault() throws IOException {
    Object raw = datasource().get("hikari");
    assertThat(raw)
        .as("★ spring.datasource.hikari 缺失 ⇒ 池上限等于 Hikari 默认 10，而自检会拿这个没人声明的数字去比")
        .isInstanceOf(Map.class);

    String declared = String.valueOf(((Map<?, ?>) raw).get("maximum-pool-size"));
    assertThat(declared)
        .as("★ 池上限没有显式声明（值是 %s）—— 靠默认值工作就是这条账要清掉的东西", declared)
        .startsWith("${DB_POOL_SIZE:");
  }

  @Test
  void theDeclaredDefaultLeavesRoomForIngestionConcurrency() throws IOException {
    int pool = declaredDefault(poolPlaceholder());
    // ★ 并发的默认值取自自检那一侧（Runnder 的 @Value 回落值），不取 yml：
    //   两个数只要有一处没配，Spring 用的就是回落值 —— 而要比的就是"没人改配置时会发生什么"。
    int concurrency = declaredDefault(annotationDefault("app.ingestion.embedding-concurrency"));

    assertThat(pool)
        .as("★ 默认池上限 %d 小于向量化并发默认值 %d ⇒ 默认配置下入库就会抢连接（#227）", pool, concurrency)
        .isGreaterThanOrEqualTo(concurrency);
  }

  @Test
  void theStartupCheckFallsBackToTheSameNumberTheYmlDeclares() throws IOException {
    String source = Files.readString(RUNNER, StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("maximum-pool-size:(\\d+)").matcher(source);
    assertThat(m.find()).as("★ 自检里读不到 maximum-pool-size 的回落默认值 —— 它被改写或删掉了？").isTrue();

    assertThat(m.group(1))
        .as("★ 自检的回落默认值必须与 yml 声明的一致，否则「没声明」和「声明了 10」两种情况下自检说法不同")
        .isEqualTo(String.valueOf(declaredDefault(poolPlaceholder())));
  }

  private static String poolPlaceholder() throws IOException {
    return String.valueOf(((Map<?, ?>) datasource().get("hikari")).get("maximum-pool-size"));
  }

  private static Map<?, ?> datasource() throws IOException {
    Map<?, ?> spring = (Map<?, ?>) yaml().get("spring");
    return (Map<?, ?>) spring.get("datasource");
  }

  private static Object appValue(String group, String key) throws IOException {
    Map<?, ?> app = (Map<?, ?>) yaml().get("app");
    return ((Map<?, ?>) app.get(group)).get(key);
  }

  private static Map<?, ?> yaml() throws IOException {
    return new Yaml().load(Files.readString(YML, StandardCharsets.UTF_8));
  }

  /** 从 {@code ${ENV:默认值}} 里取出默认值并转成整数（没有冒号 ⇒ 直接就是字面量）。 */
  private static int declaredDefault(String placeholder) {
    Matcher m = Pattern.compile("\\$\\{([A-Z0-9_]+):(-?\\d+)}").matcher(placeholder);
    if (m.find()) {
      return Integer.parseInt(m.group(2));
    }
    return Integer.parseInt(placeholder.trim());
  }

  /** 从 {@code StartupSettingsWarningRunner} 的源码里取出某个 {@code @Value} 的回落默认值。 */
  private static String annotationDefault(String key) throws IOException {
    String source = Files.readString(RUNNER, StandardCharsets.UTF_8);
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(key) + ":(-?\\d+)}")
            .matcher(source);
    assertThat(m.find()).as("★ 自检里找不到 %s 的回落默认值 —— 它被改写或删掉了？", key).isTrue();
    return m.group(1);
  }
}
