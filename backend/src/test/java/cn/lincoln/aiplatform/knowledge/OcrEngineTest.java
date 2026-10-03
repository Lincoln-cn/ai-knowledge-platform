package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import cn.lincoln.aiplatform.knowledge.infrastructure.OcrEngine;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrHealthIndicator;
import cn.lincoln.aiplatform.knowledge.infrastructure.OcrProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

/**
 * OCR 引擎可用性探测（第 9 周 · 0.3.1）—— ★ <strong>可移植性与离线部署的那一半</strong>。
 *
 * <h3>★ 这一组在防什么</h3>
 *
 * <p>OCR 引擎（{@code tesseract} + 语言包）是<strong>系统级资产</strong>，不像 jar 那样跟着构建走。 没有可用性探测之前，"这台机器能不能
 * OCR"是一个<strong>偶然后果</strong>：装了就能用、没装就静默不可用， 而界面只说「可能是扫描件」—— 用户会去反复重新导出那个本来就好的扫描件。
 *
 * <p>⇒ 本类钉住三件事：
 *
 * <ol>
 *   <li>三种"不可用"各有<strong>可读原因与下一步</strong>（配置关了 / 找不到可执行文件 / 缺语言包）
 *   <li>★ "缺语言包"要<strong>指名道姓</strong>说缺哪个文件 —— 它是离线交付最常漏的一件
 *   <li>结论要能从 {@code /actuator/health} 读出来（部署验收的判据是 {@code
 *       components.ocr.details.available}，不是"health 整体是不是 UP"）
 * </ol>
 */
class OcrEngineTest {

  // ────────────────────────── 1 · 三种不可用，各有可读原因 ──────────────────────────

  /** 配置里关掉 ⇒ 原因必须说清是"配置关的"，而不是让它看起来像环境坏了。 */
  @Test
  void 配置关闭时给出可读原因与开关位置() {
    OcrEngine engine = new OcrEngine(props(false, null, null));

    OcrEngine.Status status = engine.status();

    assertThat(status.available()).isFalse();
    assertThat(status.reason()).as("要说清是配置关的").contains("app.ocr.enabled=false");
    assertThat(status.hint()).as("下一步要能照着做").contains("app.ocr.enabled=true");
  }

  @TempDir Path tempDir;

  /**
   * ★ 找不到可执行文件 ⇒ 原因里要出现<strong>去哪里找过</strong>。
   *
   * <p>否则用户只知道"不可用"，还得自己去猜它找的是 PATH 还是某个配置项。
   */
  @Test
  void 路径指错时报出查找位置() {
    String wrongPath = tempDir.resolve("not-a-tesseract-dir").toString();
    OcrEngine engine = new OcrEngine(props(true, wrongPath, null));

    OcrEngine.Status status = engine.status();

    assertThat(status.available()).isFalse();
    assertThat(status.reason()).as("要说清找的是哪个位置").contains(wrongPath);
    assertThat(status.hint()).contains("tesseract");
  }

  /**
   * ★★ 缺语言包 ⇒ 必须<strong>指名道姓</strong>。
   *
   * <p>这是离线交付最常漏的一件：引擎装了、语言包忘了拷 ⇒ 中文退化成方块/乱码， <strong>而日志里一句错都没有</strong>。所以探测要主动去数语言包文件在不在。
   */
  @Test
  void 缺语言包时指名道姓报出缺的是哪个文件() throws Exception {
    assumeThat(new OcrEngine(props(true, null, null)).available())
        .as("本机没有可用的 tesseract ⇒ 这条验的是「缺语言包」的分支，跳过")
        .isTrue();

    // tessdata 指向一个**存在但空**的目录：引擎找得到，语言包一个都没有
    String emptyTessdata = tempDir.resolve("empty-tessdata").toString();
    Files.createDirectories(Path.of(emptyTessdata));

    OcrEngine engine = new OcrEngine(props(true, null, emptyTessdata));
    OcrEngine.Status status = engine.status();

    assertThat(status.available()).isFalse();
    assertThat(status.reason())
        .as("要说出缺的是哪个文件、在哪个目录 —— 否则部署的人只能猜")
        .contains("chi_sim.traineddata")
        .contains("eng.traineddata")
        .contains(emptyTessdata);
    assertThat(status.hint()).as("下一步要指到离线交付清单").contains("deploy/README.md");
  }

  // ────────────────────────── 2 · 可用时把事实说全（部署自检要用的那几项） ──────────────────────────

  /**
   * 配置齐了 ⇒ 可用，且<strong>报出实际用的语言与可执行文件</strong>。
   *
   * <p>★ 这三项就是离线部署自检要比对的东西：语言对不对、用的哪个 tesseract、tessdata 在哪。
   */
  @Test
  void 可用时报出语言与可执行文件位置() {
    assumeThat(new OcrEngine(props(true, null, null)).available())
        .as("本机没有可用的 tesseract ⇒ 跳过（真栈闸门 check_ocr_e2e.py 才是端到端证据）")
        .isTrue();

    OcrEngine engine = new OcrEngine(props(true, null, null));
    OcrEngine.Status status = engine.status();

    assertThat(status.available()).isTrue();
    assertThat(status.reason()).as("可用时没有失败原因").isEmpty();
    assertThat(status.languages()).as("语言要报出来（chi_sim 在不在是最该核对的一项）").contains("chi_sim", "eng");
    assertThat(engine.effectiveTessdataPath()).as("语言包目录要能读出来").isPresent();
  }

  // ────────────────────────── 3 · 健康端点（部署验收的那一面） ──────────────────────────

  /**
   * ★ {@code /actuator/health} 的 {@code components.ocr} 必须能回答"能不能 OCR"。
   *
   * <p>不可用时状态是 <strong>DEGRADED 而不是 DOWN</strong>：没装 OCR 不影响登录、文本型文档与检索， 把它报成服务挂掉是另一种失真；而 Boot 默认只有
   * {@code DOWN}/{@code OUT_OF_SERVICE} 才映射成 503， 所以 {@code DEGRADED} 不会让容器被探针重启。
   */
  @Test
  void 健康指示器暴露可用性与原因() {
    OcrHealthIndicator indicator = new OcrHealthIndicator(new OcrEngine(props(false, null, null)));

    var health = indicator.health();

    assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(health.getDetails())
        .containsEntry("available", false)
        .containsKeys("reason", "hint", "tessdataPath", "dpi");
  }

  /** 可用时状态必须是 UP（探针与部署验收都看这个）。 */
  @Test
  void 可用时健康状态是UP() {
    OcrEngine engine = new OcrEngine(props(true, null, null));
    assumeThat(engine.available()).as("本机没有可用的 tesseract ⇒ 跳过").isTrue();

    var health = new OcrHealthIndicator(engine).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("available", true);
  }

  private static OcrProperties props(boolean enabled, String tesseractPath, String tessdataPath) {
    return new OcrProperties(enabled, tesseractPath, tessdataPath, "chi_sim+eng", 300, 120, true);
  }
}
