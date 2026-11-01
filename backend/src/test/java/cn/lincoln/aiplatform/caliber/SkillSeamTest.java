package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ★ 「技能」与「会话虚拟执行目录」两缝的落点（`#77` 判据 1 与判据 4）。
 *
 * <p>这两件事在 0.5.0 之前**只留缝不做实体**（`ROADMAP §11.7.2`）。留缝的风险不是"没做"， 而是**后来人以为已经做了** —— 所以缝上要压两块石头：
 *
 * <ol>
 *   <li>设计册里有一行写明"技能 = 提示词 + 工具 + 知识库的组合单元，V1.0 之后落表"（判据 1）；
 *   <li>代码里**确实没有**技能这一层（判据 4：钉住"当前没做"，防误读）。
 * </ol>
 *
 * <p>执行目录那一半已经在 `13 §11.6.1` 定成"键空间前缀 + 读写都过端口"，由 {@link FileIoBoundaryTest} 守。
 */
class SkillSeamTest {

  @Test
  @DisplayName("设计册里要有一行写明「技能」这一层的落点与代价（#77 判据 1）")
  void theSkillSeamIsWrittenDownInTheDesignBook() throws IOException {
    String design =
        Files.readString(repoRoot().resolve("docs/系统设计方案/06-版本演进与扩展点.md"), StandardCharsets.UTF_8);
    assertThat(design)
        .as(
            "★ 06 §三 必须写清「技能 = 提示词 + 工具 + 知识库 的组合单元，V1.0 之后落表」"
                + "或者明确不做并写代价 —— 留白就是下一个人的空白页（#77 判据 1）")
        .contains("技能 = 提示词 + 工具 + 知识库 的组合单元");
  }

  @Test
  @DisplayName("★ 技能表与技能类都还不存在 —— 留缝的意思是「现在没有」，不是「已经会了」（#77 判据 4）")
  void thereIsNoSkillLayerYet() throws IOException {
    List<String> skillTables = new ArrayList<>();
    Path migrations = repoRoot().resolve("backend/src/main/resources/db/migration");
    try (Stream<Path> files = Files.walk(migrations)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
        String sql =
            Files.readString(file, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        if (sql.contains("create table")
            && sql.matches("(?s).*create\\s+table\\s+\\w*skill\\w*.*")) {
          skillTables.add(migrations.relativize(file).toString());
        }
      }
    }
    assertThat(skillTables)
        .as(
            "★ 出现了技能表：%s —— 那就要同时改这条断言、补 `06 §三` 的落点说明与一条\"技能可复用\"的验收判据。"
                + "只建表不说话，等于让下一个人以为技能层已经能用",
            skillTables)
        .isEmpty();

    List<String> skillTypes = new ArrayList<>();
    Path mainSources = repoRoot().resolve("backend/src/main/java");
    try (Stream<Path> files = Files.walk(mainSources)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith("Skill")) {
          skillTypes.add(mainSources.relativize(file).toString());
        }
      }
    }
    assertThat(skillTypes).as("★ 出现了技能类型：%s —— 同上：留缝变实体时，这条断言与设计册要一起改", skillTypes).isEmpty();
  }

  @Test
  @DisplayName("会话执行目录是键空间前缀，不是真目录（13 §11.6.1 那条纪律要写得出来）")
  void theConversationWorkspaceIsAKeyPrefixNotADirectory() throws IOException {
    String design =
        Files.readString(repoRoot().resolve("docs/系统设计方案/13-密级与数据隔离设计.md"), StandardCharsets.UTF_8);
    assertThat(design)
        .as("★ 执行目录这一缝必须写明「键空间里的一个前缀，不是文件系统里的一个真目录」—— " + "真目录意味着目录本身有生命周期/配额/权限，那三件事本项目都还没有载体")
        .contains("不是文件系统里的一个真目录");
  }

  private static Path repoRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("docs/系统设计方案"))) {
      current = current.getParent();
    }
    assertThat(current).as("★ 找不到 docs/系统设计方案 —— 这条测试必须能定位仓库根，不能靠相对路径猜").isNotNull();
    return current;
  }
}
