package cn.lincoln.aiplatform.caliber;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★ 口径组的地基断言：{@link CaliberShape#MIGRATION_FILES} 必须与<strong>源码树上的迁移目录逐个相等</strong>。
 *
 * <h3>★ 它治的是哪一种失效（`#267`，2026-10-31 真实发生过一次）</h3>
 *
 * <p>{@code MIGRATION_FILES} 是手写清单，而 {@code tableColumns()} / {@code migrationText()} 只读清单里的文件 ——
 * 于是<strong>一个没登记的迁移对所有口径断言"不存在"</strong>。而全部口径断言写的是"全量清单相等"， 两侧（基线与读数）会同时变旧 ⇒
 * <strong>断言照样绿</strong>。
 *
 * <p>实测规模：清单停在 {@code V34} 那一批（34 个），源码树已有 61 个 ⇒ <strong>27 个迁移对全部形状断言不可见</strong>；
 * 结果"实体字段"那条红了（{@code #26} 的 {@code labels} 在实体上），而"列清单"那条仍绿（假绿）—— 正是 {@code CaliberShape} 的
 * javadoc 自己警告过的形状。{@code V34} 当年也是这样被发现的，同一形状又长回来了。
 *
 * <h3>★★ 为什么读<strong>源码目录</strong>，而不是像 {@code CaliberShape#read} 那样读 classpath</h3>
 *
 * <p>因为 {@code target/classes} 会留下<strong>已从源码删除的迁移</strong>：本机实测就踩到过一次 —— {@code
 * target/classes/db/migration/knowledge/V59__knowledge_segment_soft_delete.sql} 存在（构建残留）， 而 {@code
 * src/main/resources} 里没有、{@code git} 也从未跟踪过它。
 *
 * <p>若这里读 classpath，那条残留会让本断言<strong>对一个已经不在仓库里的文件变红</strong>（在本机红、在 CI 绿 —— 最坏的一种
 * 不确定性）；反过来也成立：一个被删掉的迁移只要清单没跟着删，读 classpath 的实现会继续读到残留副本而<strong>静默绿</strong>。
 * 所以清单的正确性必须对照<strong>源码</strong>。
 *
 * <p>{@code target/classes} 里的那份残留也说明 {@code CaliberShape#read} 本身有同一个盲区（它读 classpath）—— 这一条记在
 * `#267` 里， 不在本类里解决。
 *
 * <p>★ 本类<strong>不需要库、不需要 Spring</strong>：纯读文件树。
 */
class MigrationInventoryTest {

  /**
   * ★ 清单 == 源码树上的迁移目录。
   *
   * <p>失败时 AssertJ 会同时列出"清单里多出来的"与"源码里没登记的"—— 后者才是常见的那个： 有人加了迁移、没改 {@code
   * CaliberShape}，于是新形状对所有口径断言不存在。
   */
  @Test
  void theInventoryListsEveryMigrationOnDisk() throws Exception {
    List<String> onDisk = migrationsOnDisk();
    assertThat(CaliberShape.migrationFiles())
        .as(
            """
            ★ CaliberShape.MIGRATION_FILES 与源码树上的 db/migration 不一致。
            这份清单不是"索引"，它是全部口径断言**唯一的数据源**：没登记的文件，其建出来的列与文本
            对每一条形状断言都不存在（而基线也会跟着停留在旧形状 ⇒ 红绿都不可信）。
            新增迁移时同步这里；删除迁移时也要同步（本断言两侧都咬）。
            源码树（%s）：%s
            """,
            migrationSourceRoot().toAbsolutePath(), onDisk)
        .containsExactlyInAnyOrderElementsOf(onDisk);
  }

  /**
   * 源码树 {@code db/migration} 下的全部 {@code .sql}，形如 {@code knowledge/V55__…sql}（{@code /} 分隔）。
   *
   * <p>★ 只认 {@code .sql}：Flyway 只跑这种后缀，目录里的 {@code README} 之类不该进清单。
   */
  private static List<String> migrationsOnDisk() throws Exception {
    Path base = migrationSourceRoot();
    try (Stream<Path> walk = Files.walk(base)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .map(path -> base.relativize(path).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  /**
   * 迁移目录的<strong>源码</strong>位置。
   *
   * <p>两个候选覆盖两种真实跑法：① surefire 的 {@code user.dir} = {@code backend/}（CI 与本仓的 {@code cd backend &&
   * mvn test}）；② {@code user.dir} = 仓库根（{@code mvn -f backend/pom.xml test}、IDE 默认）。
   * 两个都不是就<strong>直接失败并打印工作目录</strong> —— 不能"找不到就当空集"（那会让本断言恒绿）。
   */
  private static Path migrationSourceRoot() {
    Path direct = Path.of("src", "main", "resources", "db", "migration");
    if (Files.isDirectory(direct)) {
      return direct;
    }
    Path nested = Path.of("backend", "src", "main", "resources", "db", "migration");
    if (Files.isDirectory(nested)) {
      return nested;
    }
    throw new IllegalStateException(
        "找不到迁移源码目录（当前工作目录 = " + Path.of("").toAbsolutePath() + "）—— 本断言拒绝把「读不到」当成「空集」");
  }
}
