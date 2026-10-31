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
}
