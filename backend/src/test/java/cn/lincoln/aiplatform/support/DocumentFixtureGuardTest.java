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
 * <h3>★★ 现在它是一条硬判据（2026-10-17 收紧）</h3>
 *
 * <p>{@code #48} 原文判据 2 是"grep = 1"。开工时全库是 <strong>16 处</strong>（写这条时 4 处， 此后每加一个真栈测试就多一处 ——
 * 症状一直在扩散）⇒ 先按"台账式"落地（只许变少）。
 *
 * <p>★ 随后把 16 处<strong>逐类</strong>迁进共用夹具 {@code DocumentFixture}（分批 + 每批跑一遍那些类）， 全部迁完 ⇒
 * <strong>判据已收紧回原文形状</strong>：只有 {@code DocumentFixture} 里允许出现它。
 *
 * <p>★★ 「逐类」这三个字是拿读数换来的：第一版一次性全迁，实测 <strong>3 红 + 16 错</strong> （会话域文档<strong>没有库行</strong> ⇒ 18 个
 * NPE；{@code DocumentScopeShapeTest} 出现 {@code expected: 1 but was: 0} 这种<strong>语义回归</strong>）⇒
 * 那一版整批回退。 分批之后每一批都能定位到具体原因（例如"扩展列段的 {@code kb_id} 必须 {@code cast(? as uuid)}"）。
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

  /** ★ {@link DocumentFixture} 里那两条是**允许**的那一处（`#48` 判据 2 的唯一合法落点）。 */
  private static final String ALLOWED_FILE = "DocumentFixture.java";

  /** ★ 闸门自己排除（它引用这句 SQL 是为了断言，不是又写一份）。 */
  private static final String SELF_FILE = "DocumentFixtureGuardTest.java";

  private static final String NEEDLE = "insert into kb_document";

  @Test
  void onlyTheSharedFixtureWritesTheDocumentTable() throws IOException {
    Path testRoot = sourceRoot();
    List<String> offenders = new ArrayList<>();
    int total = 0;
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
        total += count;
        if (!name.equals(ALLOWED_FILE)) {
          offenders.add(name + " ×" + count);
        }
      }
    }

    assertThat(offenders)
        .as(
            """
            ★★ #48 判据 2：`insert into kb_document` 只许出现在共用夹具 DocumentFixture 里。
            多出来的那些就是"夹具与产品两份真相"的入口：改一处列名，它们会在看起来无关的地方红，
            而只影响夹具的改动它们又不会提醒你。
            ⇒ 把新添的那一处改成走 DocumentFixture（列清单加在夹具里，不在用例里另拼一份）。
            ★ 若它造的是"产品路径拒绝或不产生的形状"（密级高于库上限的存量文档 · org_id 为空而
              department_legacy 非空的回填残留 · 任意 scope_kind/owner_id 组合 · 会话域文档），
              请把**列**或**入口**加进夹具，而不是在用例里另拼一份。
            当前在别处的命中：%s\
            """,
            offenders)
        .isEmpty();
    // ★★ 2026-10-17：**全仓 16 处已全部迁进夹具**，判据因此收紧到 `#48` 原文的形状（只许一处）。
    //   ★ 为什么仍允许 2 条而不是 1 条：夹具里刻意有两条 INSERT ——
    //     ① `INSERT`（常规：scope_kind 恒 'KB'、无 owner_id）
    //     ② `INSERT_WITH_SCOPE`（扩展列：scope_kind/scope_id/owner_id 显式给，且 kb_id 可空）
    //     ★ 后者是为"产品路径不产生的形状"留的入口；少了它，调用方就得为 owner_id 复制整段 SQL
    //     —— 那正是 #48 要防的事。
    //   ★ 数字钉死是为了让"又加一条"必须被显式改一次这个常量（而不是悄悄多一条）。
    assertThat(total).as("夹具里恰好两条 INSERT（常规 + 扩展列）；多一条就要在这里显式说明它为什么必须存在").isEqualTo(2);
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
