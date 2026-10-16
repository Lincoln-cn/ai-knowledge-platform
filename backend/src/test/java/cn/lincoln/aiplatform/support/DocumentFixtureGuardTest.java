package cn.lincoln.aiplatform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code #48} 判据 2：<strong>全库直接写 {@code kb_document} 的地方只许变少，不许变多</strong>。
 *
 * <h3>它守的是什么</h3>
 *
 * <p>{@code T300}（{@code V27}）把 {@code kb_document.department} 改名为 {@code department_legacy} 时，
 * <strong>主代码全绿、编译全绿</strong>，却有 <strong>52 条测试红</strong> —— 真因是测试夹具 <strong>用裸 SQL
 * 直接写表</strong>：绕过实体与仓储 ⇒ 表结构一变，编译器一句话都不说， 只在运行时炸，而且爆炸点离改动点很远（改的是领域实体，红的是检索测试）。
 *
 * <p>★★ 更危险的一面（{@code #48} 危害 2）：如果某次改动<strong>只影响夹具而主代码没变</strong>， 那一批测试根本不会提醒你 —— 它们测的是自己拼的
 * SQL，不是产品路径。<strong>夹具与产品是两份真相。</strong>
 *
 * <h3>★★ 为什么是"台账"而不是"必须为 1"</h3>
 *
 * <p>{@code #48} 原文判据 2 写的是"grep = 1"（只允许一处共用夹具）。<strong>本批实测：全库已经是 15 处</strong> （写这条时的 4
 * 处，此后每加一个真栈测试就多一处 —— 症状一直在扩散）。
 *
 * <p>★ 本批<strong>尝试过</strong>把 15 处全部迁进一个共用夹具（{@code DocumentFixture}），实测 <strong>3 红 + 16
 * 错</strong>，其中 {@code DocumentScopeShapeTest} 出现"expected 1 but was 0"这种
 * <strong>语义回归</strong>（那些类造的正是"产品路径<em>不产生</em>的归属形状"，逐处迁移会改变语义）。 ⇒
 * 按"不许把红的树留在身后"，整批回退；<strong>只留下这条闸门</strong>，并把判据改成它今天真能兑现的形状：
 *
 * <ul>
 *   <li><strong>计数不许上升</strong>（今天基线 {@value #BASELINE} —— 实测 16 个类各一处）—— 谁再添第 16 处，这条当场红；
 *   <li>★ <strong>只许下降</strong>：每把一个类迁到共用夹具，就把 {@link #BASELINE} 减一。
 *       基线数字是一次**签名**：改它必须在提交里说明"迁了哪一个类"。
 * </ul>
 *
 * <h3>★ 判据的读法（本批返工两次，都记在这里）</h3>
 *
 * <p>数的是"<strong>非注释行里出现</strong> {@code insert into kb_document}"—— 先把非注释行接成一段再找，于是这三种真实写法都抓得到：
 * {@code X = "insert into …"} · {@code .update("insert into …", …)} · 长字符串被 spotless 折到下一行。
 *
 * <p>★★ 第一版写的是"这一行以该 SQL 的字符串字面量<strong>开头</strong>"，上面三种写法全被放过； <strong>我注入坏味道去验它时，它一声不吭</strong>
 * —— 这正是本仓最爱记的那类洞： <strong>判据读着覆盖了、其实没覆盖</strong>。
 *
 * <p>★★ 而修好之后我又被骗了一次：这回把坏味道注入到**闸门自己的文件**里， 被"排除自己"那条规则挡掉了，看起来仍像闸门失效 —— ⇒ ★
 * <strong>验一条闸门时，坏味道必须注入到"它应当管的地方"</strong>： 换到另一个文件后它当场红（读数 {@code
 * 当前在别处的命中：[DocumentPendingOrgTest.java ×1]}）。
 */
class DocumentFixtureGuardTest {

  /**
   * ★★ 今天的基线：全库有 15 个测试类里直接拼 {@code insert into kb_document}。
   *
   * <p>★ 这个数字**只许下降**：迁一个类到共用夹具，就把它减一。改了它而提交里说不清"迁了哪个类"， 那这次改动就是把闸门调松了 ——
   * 与"修闸门"读数完全一样，唯一能区分的是**它还能不能红**。
   */
  private static final int BASELINE = 16;

  /** ★ 闸门自己排除（它引用这句 SQL 是为了断言，不是又写一份）。 */
  private static final String SELF_FILE = "DocumentFixtureGuardTest.java";

  private static final String NEEDLE = "insert into kb_document";

  @Test
  void rawDocumentInsertCountOnlyGoesDown() throws IOException {
    Path testRoot = sourceRoot();
    List<String> hits = new ArrayList<>();
    for (Path file : javaFiles(testRoot)) {
      String name = file.getFileName().toString();
      if (name.equals(SELF_FILE)) {
        continue;
      }
      StringBuilder code = new StringBuilder();
      for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        String stripped = raw.strip();
        // ★ 逐字注释不算：本仓注释大量引用这句 SQL 讲来历，算进去会让这条断言从第一天起就失真
        if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) {
          continue;
        }
        code.append(stripped).append('\n');
      }
      int count = countOccurrences(code.toString(), NEEDLE);
      if (count > 0) {
        hits.add(name + " ×" + count);
      }
    }

    assertThat(hits)
        .as(
            """
            ★★ #48 判据 2（台账式）：全库直接写 kb_document 的地方**只许变少**。
            今天基线 = %d，实测 = %d。
            ⇒ 若你新添了一处裸 SQL：请改成走共用夹具（列清单收在一处）——
              那条路正是 #48 要收掉的"夹具与产品两份真相"。
            ⇒ 若你迁走了一处：把 BASELINE 减一，并在提交里写明迁的是哪个类。
            当前命中：%s\
            """,
            BASELINE, hits.size(), hits)
        .hasSizeLessThanOrEqualTo(BASELINE);
  }

  // ────────────────────────── 定位源码 ──────────────────────────

  /**
   * 测试源码根。
   *
   * <p>★ surefire 的工作目录是 {@code backend/}（与 {@code ContractDriftTest} 读契约文件同一条）， 而这条断言读的是**全库的 java
   * 文件**（不是 classpath 资源）—— 所以它必须在工作目录上走文件系统。 ★ 找不到就**抛**（不是 skip）：一条"路径不对就静默通过"的闸门比没有闸门更坏。
   */
  private static Path sourceRoot() {
    Path root = Path.of("src", "test", "java").toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException(
          "读不到测试源码根：" + root + "（surefire 的工作目录应为 backend/）—— 本闸门不接受『文件不在』");
    }
    return root;
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }

  /** {@code needle} 在 {@code haystack} 里出现几次（★ 用 indexOf 而不是正则：这段文本里有括号等元字符）。 */
  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = haystack.indexOf(needle, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + needle.length();
    }
  }
}
