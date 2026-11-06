package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★ {@code #83} 第十二条（深度合成内容标识）的<b>只记账</b>读数。
 *
 * <h3>★ 这个断言钉住的是「本版没有视觉生成 ⇒ 标识无处可打」</h3>
 *
 * <p>{@code #83} 第十二条要求对图片、视频等生成内容添加显著标识；但本平台本版<b>不产生任何 视觉生成内容</b>（只产文本回答）——
 * 标识没有可打的位置，做一个空的"标识功能"就是 silent-failure （界面上有个东西看起来在工作、实际什么都标识不了）。处置 = 只记账：进 {@code #83} Issue，
 * 触发条件写死「平台开始产生图片/视频等视觉生成内容之前必须把标识做实」。
 *
 * <h3>怎么红 / 怎么算做了</h3>
 *
 * <ul>
 *   <li><b>反证读数</b>：故意在主代码（{@code src/main/java}）里放一个下列指纹标识符（如 {@code deepSynthesisLabel}）⇒ 本测试立刻红
 *       —— 那说明视觉生成或内容标识已经落进代码。
 *   <li>红了<b>不是把测试删掉</b>，而是三件事一起做：① 本方法改成<b>正向</b>断言（标识必须挂在 生成出口上）② 同步 {@code #83} 的处置判定（只记账 → 真做）③
 *       补标识的可核对测试。
 *   <li>指纹是"内容标识"的具体标识符形状，<b>不是</b>泛词 —— 不用 {@code watermark}（那是会话密级水位， {@code
 *       ConversationWatermarkPort}，语义无关），不用 {@code label}（遍地都是）。
 * </ul>
 */
class VisualGenerationAbsenceTest {

  /** 「深度合成内容标识落进了主代码」的具体指纹。命中任何一个就算做了。 */
  private static final List<String> VISUAL_CONTENT_LABEL_MARKERS =
      List.of(
          "deepSynthesisLabel",
          "deepSynthesisMark",
          "generatedMediaLabel",
          "visibleContentLabel",
          "invisibleContentWatermark",
          "aigcContentLabel");

  @Test
  void visualGenerationContentLabelIsAbsentUntilVisualGenerationExists() {
    List<String> hits = new ArrayList<>();
    for (String marker : VISUAL_CONTENT_LABEL_MARKERS) {
      occurrences(marker).forEach((location) -> hits.add(location + " → " + marker));
    }
    assertThat(hits)
        .as(
            "★★ #83 第十二条：本平台本版不产生图片/视频等视觉生成内容，深度合成内容标识处置=只记账"
                + "（标识无处可打，空功能=silent-failure）。这条断言就是「今天没人做这件事」的读数。"
                + "⇒ 如果这里红了，说明有人把视觉生成或内容标识落进了主代码——那是对的，但必须同时："
                + "① 把本方法改成正向断言（标识必须挂在生成出口）② 同步 #83 的处置判定与登记"
                + "（只记账→真做）③ 补「生成的视觉内容带标识」的可核对测试。"
                + "指纹="
                + VISUAL_CONTENT_LABEL_MARKERS)
        .isEmpty();
  }

  /** 返回主代码里 {@code identifier} 的出现位置（{@code 文件名:行号}），跳过注释行。 */
  private static List<String> occurrences(String identifier) {
    List<String> hits = new ArrayList<>();
    Path mainJava = Path.of("src", "main", "java");
    try (Stream<Path> files = Files.walk(mainJava)) {
      files
          .filter((path) -> path.toString().endsWith(".java"))
          .forEach(
              (file) -> {
                try {
                  List<String> lines = Files.readAllLines(file);
                  for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//")
                        || trimmed.startsWith("*")
                        || trimmed.startsWith("/*")) {
                      continue;
                    }
                    if (line.contains(identifier)) {
                      hits.add(file.getFileName() + ":" + (i + 1));
                    }
                  }
                } catch (IOException unreadable) {
                  throw new IllegalStateException("无法读取 " + file, unreadable);
                }
              });
    } catch (IOException walkFailed) {
      throw new IllegalStateException("无法扫描 " + mainJava, walkFailed);
    }
    return hits;
  }
}
