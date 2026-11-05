package cn.lincoln.aiplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动配置自检的判据（`#227` / `#65` 各自暴露过一次"配错了却在第一次入库才炸"）。
 *
 * <p>★ 这一层是纯函数，所以不必起容器 —— 判据要能"改一个数就验证一条规则"， 否则没人会为了验一条边界去等 60 秒的 Testcontainers。
 *
 * <p>★ 三条断言风格在这里贯穿：
 *
 * <ol>
 *   <li>每条规则都断言"消息里说清了怎么改"，而不是只断言"有报错"—— 人类读的是后半句；
 *   <li>每条规则都配一个**边界内的对照**：只验"坏的会报"不验"好的不报"，规则写宽了也照样绿；
 *   <li>默认值必须零告警：默认配置就该是能跑的，否则自检会在每次启动时刷屏。
 * </ol>
 */
class StartupSettingsCheckTest {

  @Test
  void defaultSettingsProduceNoWarnings() {
    List<String> problems = StartupSettingsCheck.problems(4, 10, 80, 1200);
    assertThat(problems)
        .as("★ 默认配置（并发 4 / 池 10 / 窗口 80 / 预算 1200）必须零告警 —— 否则自检会在每次启动时刷屏")
        .isEmpty();
  }

  @Test
  void embeddingConcurrencyAbovePoolSizeIsReported() {
    List<String> problems = StartupSettingsCheck.problems(69, 10, 80, 1200);
    assertThat(problems).as("#227 现场：69 份一起跑、池只有 10").hasSize(1);
    assertThat(problems.get(0))
        .as("要说清后果与改法，而不是只说『配置不合法』")
        .contains("app.ingestion.embedding-concurrency=69")
        .contains("大于连接池上限 10")
        .contains("Failed to obtain JDBC Connection")
        .contains("改成小于池上限");
  }

  @Test
  void embeddingConcurrencyAtPoolSizeIsNotReported() {
    assertThat(StartupSettingsCheck.problems(10, 10, 80, 1200))
        .as("★ 对照：等于池上限不报（它确实能跑，只是没有余量）—— 规则写成 >= 会让正常配置也告警")
        .isEmpty();
  }

  @Test
  void zeroEmbeddingConcurrencyIsReportedAsTurningItOff() {
    List<String> problems = StartupSettingsCheck.problems(0, 10, 80, 1200);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .as("配成 0 的后果不是『慢』，而是『文档永远停在待向量化』，且没有任何报错")
        .contains("下限是 1")
        .contains("待向量化");
  }

  @Test
  void negativeContextWindowIsReported() {
    List<String> problems = StartupSettingsCheck.problems(4, 10, -1, 1200);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0)).contains("窗口不能为负").contains("IllegalArgumentException");
  }

  @Test
  void windowWithTooSmallBudgetIsReportedAsNeverFitting() {
    // 200 - 80 = 120，正好等于 MIN_BODY_CHARS ⇒ 边界内，不报
    assertThat(StartupSettingsCheck.problems(4, 10, 80, 200))
        .as("★ 边界：预算恰好够『前缀 + 最小主块』时不报")
        .isEmpty();

    List<String> problems = StartupSettingsCheck.problems(4, 10, 80, 150);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .as("★ 要说清『窗口看起来开着、实际一条都没进来』—— 这是最容易读成『功能已生效』的形状")
        .contains("app.segmentation.max-chars=150")
        .contains("会被裁空")
        .contains("建议 max-chars ≥");
  }

  @Test
  void windowOffWithSmallBudgetIsNotAboutTheWindow() {
    assertThat(StartupSettingsCheck.problems(4, 10, 0, 150))
        .as("★ 窗口关着时，预算小与窗口无关 —— 这条规则不该报（否则用户会去查一个没开的功能）")
        .isEmpty();
  }

  @Test
  @DisplayName("★ 清理窗口比恢复窗口短 —— 会删掉「还能点恢复」的内容，必须报")
  void cleanupWindowShorterThanRestoreWindowIsReported() {
    var problems = StartupSettingsCheck.problems(4, 10, 80, 1200, 7, 3);

    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .as("要说清两个键的名字、以及用户会看到什么")
        .contains("cleanup.retain-days=3")
        .contains("restore-window-days=7")
        .contains("恢复");
  }

  @Test
  @DisplayName("两个窗口同值、或清理窗口更长，都不报（后者只是多占空间，不是错）")
  void equalOrLongerCleanupWindowIsNotReported() {
    assertThat(StartupSettingsCheck.problems(4, 10, 80, 1200, 7, 7)).isEmpty();
    assertThat(StartupSettingsCheck.problems(4, 10, 80, 1200, 7, 30)).isEmpty();
  }

  @Test
  @DisplayName("★ 四参老版本不产生这条新告警（默认等于没配，等价行为不变）")
  void legacyFourArgOverloadStaysQuiet() {
    assertThat(StartupSettingsCheck.problems(4, 10, 80, 1200)).isEmpty();
  }

  @Test
  void sampleSecretsAreReportedAsMissingConfiguration() {
    List<String> problems =
        StartupSettingsCheck.secretProblems(
            StartupSettingsCheck.SAMPLE_JWT_SECRET, StartupSettingsCheck.SAMPLE_API_KEY_SECRET);

    assertThat(problems).as("两个密钥都是示例值 ⇒ 两条，一条一条能改的话").hasSize(2);
    assertThat(problems.get(0))
        .as("要说清后果（可伪造令牌）与改法（环境变量名）")
        .contains("app.security.jwt.secret")
        .contains("JWT_SECRET")
        .contains("合法令牌");
    assertThat(problems.get(1))
        .as("要说清后果（等于明文存库）与改法，以及换完的代价")
        .contains("app.model.api-key-secret")
        .contains("MODEL_API_KEY_SECRET")
        .contains("重新录一遍");
  }

  @Test
  void replacedSecretsAreQuiet() {
    List<String> problems =
        StartupSettingsCheck.secretProblems(
            "Zm9vYmFyYmF6LXJhbmRvbS1zZWNyZXQtdmFsdWU=", "YW5vdGhlci1yYW5kb20tc2VjcmV0LXZhbHVlIQ==");
    assertThat(problems).as("换过了就不该再吵 —— 只验『坏的会报』不验『好的不报』，规则写宽了也照样绿").isEmpty();
  }

  @Test
  void undeclaredModelSlotsAreQuiet() {
    List<String> problems = StartupSettingsCheck.modelSlotProblems(-1);
    assertThat(problems)
        .as("★ -1 = 没声明 ⇒ 走 Ollama 自己的默认值（3 × GPU 数），不该报 —— 否则每个默认部署都被吓一次")
        .isEmpty();
  }

  @Test
  void twoOrMoreModelSlotsAreQuiet() {
    assertThat(StartupSettingsCheck.modelSlotProblems(2)).as("两个槽位正好够问答链路").isEmpty();
    assertThat(StartupSettingsCheck.modelSlotProblems(3)).as("三个更不会报").isEmpty();
  }

  @Test
  void singleModelSlotIsReportedAndSaysHowToFixIt() {
    List<String> problems = StartupSettingsCheck.modelSlotProblems(1);

    assertThat(problems).as("★ 这一条就是 #282 的现场").hasSize(1);
    String message = problems.get(0);
    assertThat(message)
        .as("要能照着改：报出配置名、后果、修法")
        .contains("app.model.ollama.max-loaded-models=1")
        .contains("两个模型同时驻留")
        .contains("header parser received no bytes")
        .contains("改成 ≥2");
  }

  @Test
  void onlyTheSampleOneIsReportedWhenTheOtherIsReplaced() {
    List<String> problems =
        StartupSettingsCheck.secretProblems(
            "Zm9vYmFyYmF6LXJhbmRvbS1zZWNyZXQtdmFsdWU=", StartupSettingsCheck.SAMPLE_API_KEY_SECRET);

    assertThat(problems).as("只该报没换的那条").hasSize(1);
    assertThat(problems.get(0)).contains("app.model.api-key-secret");
  }

  // ===== #303：解析/分段并发的校验（与向量化同一套容量口径、同一处校验）=====

  @Test
  @DisplayName("★ parse-concurrency=0 = auto（min(核数, 池/2)）永远合法 —— 默认配置必须零告警")
  void autoParseConcurrencyIsQuiet() {
    assertThat(StartupSettingsCheck.ingestionProblems(0, 2, 8)).isEmpty();
  }

  @Test
  @DisplayName("负数报 —— 说清 0 是 auto、怎么改")
  void negativeParseConcurrencyIsReported() {
    List<String> problems = StartupSettingsCheck.ingestionProblems(-1, 10, 8);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .contains("app.ingestion.parse-concurrency=-1")
        .contains("负数没有意义")
        .contains("改成 0 或 ≥1");
  }

  @Test
  @DisplayName("★ 大于池上限报 —— #303 现场（池=2 时 20 份一起解析）：说清后果与改成一半的建议")
  void parseConcurrencyAbovePoolSizeIsReported() {
    List<String> problems = StartupSettingsCheck.ingestionProblems(4, 2, 8);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .contains("app.ingestion.parse-concurrency=4")
        .contains("大于连接池上限 2")
        .contains("#303")
        .contains("池上限的一半");
  }

  @Test
  @DisplayName("★ 大于逻辑核数报 —— 解析是 CPU 密集，超核数只有抢占没有吞吐")
  void parseConcurrencyAboveCpuCountIsReported() {
    List<String> problems = StartupSettingsCheck.ingestionProblems(8, 20, 4);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0))
        .contains("app.ingestion.parse-concurrency=8")
        .contains("大于逻辑核数 4")
        .contains("CPU 密集");
  }

  @Test
  @DisplayName("★ 对照：边界内的合法值不报（1..min(池,核数)）")
  void parseConcurrencyWithinBoundsIsQuiet() {
    assertThat(StartupSettingsCheck.ingestionProblems(2, 2, 8)).isEmpty();
    assertThat(StartupSettingsCheck.ingestionProblems(1, 10, 8)).isEmpty();
  }
}
