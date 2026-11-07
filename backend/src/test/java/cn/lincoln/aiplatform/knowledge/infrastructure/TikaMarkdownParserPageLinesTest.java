package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * `#96`：逐页 OCR 产出行的观测（{@link TikaMarkdownParser} 的私有读数）。
 *
 * <p>★ 这一组测的是<strong>可观测面本身</strong>，不是"丢行检测准确性"：`renderedLines` 对 OCR 页永远取不到 （那一页之所以走
 * OCR，正是因为它没有文本层），所以 `OcrLineLossGuard` 的双读数比对在生产里无法执行。 能做的只有"这一页产出了几行"，以及"≤ 1 行就喊一声"。
 *
 * <p>★ 为什么纯反射调私有方法而不是造一份扫描件跑 `parse()`：跑 `parse()` 需要 Tesseract， CI
 * 上没有引擎，那条路会变成"环境不满足就跳过"的假绿（本项目栽过多次）。
 */
class TikaMarkdownParserPageLinesTest {

  private Logger parserLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level originalLevel;

  @BeforeEach
  void attachAppender() {
    parserLogger = (Logger) LoggerFactory.getLogger(TikaMarkdownParser.class);
    originalLevel = parserLogger.getLevel();
    parserLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    parserLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    parserLogger.detachAppender(appender);
    parserLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("countLines：空串 0 行，末尾换行不算多一行")
  void countLinesHandlesEmptyAndTrailingNewline() {
    assertThat(invokeCountLines(null)).isZero();
    assertThat(invokeCountLines("")).isZero();
    assertThat(invokeCountLines("a")).isEqualTo(1);
    assertThat(invokeCountLines("a\nb")).isEqualTo(2);
    assertThat(invokeCountLines("a\nb\n")).as("末尾换行只是行尾，不是第五行").isEqualTo(2);
  }

  @Test
  @DisplayName("★ OCR 页只产出 0 行 ⇒ 必须 WARN（整页丢字的最低止血）")
  void anOcrPageWithNoLineIsWarned() {
    List<int[]> pageLines = new ArrayList<>();
    pageLines.add(new int[] {1, 0, 0}); // 第 1 页：有文本层，0 行（起点）
    pageLines.add(new int[] {2, 0, 1}); // 第 2 页：OCR 页，累计仍是 0 行 ⇒ 这一页一个字都没出来

    invokeLogOcrPageLines("扫描件.pdf", pageLines);

    assertThat(warnings())
        .as("整页丢字必须喊出来，否则这份文件会静默变成只有第一页")
        .anyMatch(m -> m.contains("#96") && m.contains("第 2 页") && m.contains("只产出 0 行"));
  }

  @Test
  @DisplayName("★ OCR 页只产出一两个字（1 行）⇒ 也 WARN")
  void anOcrPageWithASingleLineIsWarned() {
    List<int[]> pageLines = new ArrayList<>();
    pageLines.add(new int[] {1, 5, 0});
    pageLines.add(new int[] {2, 6, 1}); // 这一页只多了 1 行

    invokeLogOcrPageLines("扫描件.pdf", pageLines);

    assertThat(warnings()).anyMatch(m -> m.contains("第 2 页") && m.contains("只产出 1 行"));
  }

  @Test
  @DisplayName("OCR 页产出正常 ⇒ 只 INFO，不 WARN")
  void aHealthyOcrPageIsOnlyInfo() {
    List<int[]> pageLines = new ArrayList<>();
    pageLines.add(new int[] {1, 10, 0});
    pageLines.add(new int[] {2, 40, 1});

    invokeLogOcrPageLines("扫描件.pdf", pageLines);

    assertThat(warnings()).as("正常页不该产生告警，否则告警会被训练成噪声").isEmpty();
    assertThat(informations()).anyMatch(m -> m.contains("#96 OCR 页产出") && m.contains("第 2 页 30 行"));
  }

  @Test
  @DisplayName("★ 非 OCR 页永远不 WARN —— 文本层页产出少是别的问题，不在 #96 的判据里")
  void textLayerPagesAreNotJudgedHere() {
    List<int[]> pageLines = new ArrayList<>();
    pageLines.add(new int[] {1, 1, 0}); // 第 1 页有文本层、只有 1 行

    invokeLogOcrPageLines("文本层.pdf", pageLines);

    assertThat(warnings()).isEmpty();
  }

  // ────────────────────────── 反射与日志工具 ──────────────────────────

  private static int invokeCountLines(String text) {
    try {
      Method m = TikaMarkdownParser.class.getDeclaredMethod("countLines", String.class);
      m.setAccessible(true);
      return (int) m.invoke(null, text);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("countLines 调用失败", e);
    }
  }

  private static void invokeLogOcrPageLines(String filename, List<int[]> pageLines) {
    try {
      Method m =
          TikaMarkdownParser.class.getDeclaredMethod("logOcrPageLines", String.class, List.class);
      m.setAccessible(true);
      m.invoke(null, filename, pageLines);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("logOcrPageLines 调用失败", e);
    }
  }

  private List<String> warnings() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private List<String> informations() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
