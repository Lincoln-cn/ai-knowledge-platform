package cn.lincoln.aiplatform.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * ★ 执行记录的「归因」契约形状闸门（{@code #112}）。
 *
 * <p>{@code WorkflowRecord} 的 {@code graphVersion}（run 级）与 {@code
 * nodeTrail[].parentNodeId}（节点级）都不是渲染必需的字段 ——
 * 删掉它们，页面照常、任何闸门都不红，而「改版之后查不出这次跑的是哪一版」这种损失要到真正排查时才显形（本项目头号病 {@code silent-failure}
 * 的形态）。所以这里把它们钉进一条会红的断言。
 *
 * <h3>★ 为什么断言的是 TS 文件而不是 Java 类</h3>
 *
 * <p>执行引擎在 V1.0（{@code ROADMAP} 0.5.x 之后），后端 {@code workflow} 模块今天只有一个 {@code package-info.java} ⇒
 * 没有对应的 Java 记录可以"两边对读"。这条闸门因此只钉**契约这一侧的形状**：字段在、名字对、以及 {@code graphVersion} 确实是 <b>run 级</b>（位于
 * {@code nodeTrail} 之后）而不是被塞进每条节点记录里。
 */
class WorkflowRecordContractShapeTest {

  /** surefire 的工作目录是 {@code backend/}，所以往上一层才是仓库根。 */
  private static final Path CONTRACT = Path.of("..", "frontend", "src", "api", "workflow.ts");

  @Test
  void recordKeepsTheTwoAttributionFieldsAndGraphVersionIsRunLevel() {
    String source = read();
    String record = slice(source, "export interface WorkflowRecord {");
    String trail = sliceUntil(source, "  nodeTrail: Array<{", "\n  }>");

    assertThat(record).as("run 级要记下『这次跑的是哪一版图』，否则改版后历史执行无法归因").contains("graphVersion?: number");
    assertThat(record)
        .as("并行扇出时节点要能说出自己属于哪条支路（V1.0 可以恒为 null，但位置必须在）")
        .contains("parentNodeId?: string | null");

    int graphVersionAt = record.indexOf("graphVersion?: number");
    int nodeTrailAt = record.indexOf("nodeTrail: Array<{");

    assertThat(nodeTrailAt).as("nodeTrail 字段本身必须还在 —— 否则这条断言是在空集上成立").isGreaterThanOrEqualTo(0);
    assertThat(graphVersionAt)
        .as("graphVersion 必须写在 nodeTrail 之后（run 级），塞进节点记录会让每条记录重复同一个值")
        .isGreaterThan(nodeTrailAt);
    assertThat(trail)
        .as("节点级只放 parentNodeId，不放 graphVersion 字段（注释里提到它是允许的）")
        .doesNotContain("graphVersion?:");
  }

  /** 从锚点截到下一个 {@code endAnchor} —— 够用且不引入 TS 解析器。 */
  private static String sliceUntil(String source, String anchor, String endAnchor) {
    int start = source.indexOf(anchor);
    assertThat(start).as("契约里找不到锚点：%s（契约改名/挪文件都会让这条闸门失效，请同步本测试）", anchor).isGreaterThanOrEqualTo(0);
    int end = source.indexOf(endAnchor, start);
    assertThat(end).as("找不到收尾锚点：%s", endAnchor).isGreaterThan(start);
    return source.substring(start, end);
  }

  /** 从某个锚点开始截到下一个同缩进级别的 `}` 为止 —— 够用且不引入 TS 解析器。 */
  private static String slice(String source, String anchor) {
    int start = source.indexOf(anchor);
    assertThat(start).as("契约里找不到锚点：%s（契约改名/挪文件都会让这条闸门失效，请同步本测试）", anchor).isGreaterThanOrEqualTo(0);
    int end = source.indexOf("\n}", start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }

  private static String read() {
    try {
      return Files.readString(CONTRACT, StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("读不到前端契约：" + CONTRACT.toAbsolutePath(), failure);
    }
  }
}
