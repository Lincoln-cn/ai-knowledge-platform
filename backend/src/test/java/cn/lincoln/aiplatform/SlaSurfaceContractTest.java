package cn.lincoln.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * SLA 面契约测试：{@code sla/} 里的工具依赖的接口形状，改坏了这里就红。
 *
 * <p>★ 为什么这条测试必须放在<strong>开源仓库里</strong>：{@code sla/} 被 {@code .gitignore} 忽略 （2026-11-08
 * 用户明令「不开源」），所以<strong>没有任何 CI job 跑得动那些工具</strong>。 后端 API 一改，工具就静默失效 ——
 * 最坏的结果是工具报「服务异常」，而实际是工具自己过时了， 白白消耗一次事故的处理时间。
 *
 * <p>⇒ 唯一能自动守住的部分就是这条测试。它守的是「<strong>公开 API 的形状</strong>」， 不是「SLA 的私有实现」，所以放进开源仓库是合理的。
 *
 * <p>★ 与 {@code sla/contract.json} 是<strong>同一份清单的两侧</strong>：
 *
 * <ul>
 *   <li>这里（仓库侧，权威）：任何一项被改名/删掉，PR 合不进来；
 *   <li>{@code sla/contract.json}（工具侧，兜底）：每次工具启动时自检一次，防的是"工具比后端旧"。
 * </ul>
 *
 * <p>★ 实现方式：<strong>读 {@code @RestController} 源码</strong>里 {@code @RequestMapping} /
 * {@code @GetMapping} 等的字面量，不起 Spring 上下文、不拉 Testcontainers —— 一条形状断言不值得起一个 PostgreSQL。这与 {@code
 * ContractDriftTest} 读前端 TS 源码的做法同源。
 *
 * <p>★ 为什么不反射扫包：试过 {@code ClassPathScanningCandidateComponentProvider}， 在没有 {@code @ComponentScan}
 * 上下文的单测里它返回空集（2026-11-08 实测，两个用例同时红）。 源码正则虽然"土"，但它在这里更可信 —— 而且路径模板本来就是写在注解里的字面量。
 */
class SlaSurfaceContractTest {

  /** 后端主代码根（surefire 的 basedir 是 {@code backend/}）。 */
  private static final Path MAIN_JAVA = Path.of("src/main/java/cn/lincoln/aiplatform");

  /**
   * SLA 工具链依赖的端点快照（method + 完整路径模板）。
   *
   * <p>★ 只放<strong>工具真的会调</strong>的那几条 —— 放全部端点就变成第二份 {@code ContractDriftTest}，
   * 而那个测试已经在管"后端有没有偷偷加/漏端点"了。这里的职责窄而硬：<strong>SLA 用到的这几条， 名字不许变</strong>。
   *
   * <p>★ 与 {@code sla/contract.json} 一一对应（那边不含 {@code /api/v1}，因为工具的 {@code --base} 已经带了它，而 {@code
   * /actuator/health} 挂在根路径）。
   */
  private static final List<String> EXPECTED =
      List.of(
          "POST /api/v1/auth/login",
          "GET /api/v1/assistants",
          "GET /api/v1/assistants/{appId}/workflow/draft",
          "GET /api/v1/knowledge-bases",
          "GET /api/v1/models",
          "GET /api/v1/model-providers",
          "POST /api/v1/conversations",
          "POST /api/v1/conversations/chat");

  /**
   * {@code /actuator/health} 不在上面那张表里：它由 Spring Boot Actuator 提供、不在 {@code @RestController}
   * 里，所以扫不到。它挂在<strong>根路径</strong>且<strong>不带 token</strong>（带 token 反而 401，实测）， 这两条写在 {@code
   * sla/preflight.py} 的 {@code kind: "root"} 分支里，由工具侧自检兜底。
   */
  @Test
  void slaReliesOnTheseEndpointsAndTheyMustKeepTheirShape() {
    Set<String> actual = scanControllers();

    assertThat(actual).describedAs("扫不到任何映射 ⇒ 扫描机制本身失效，下面的结论也不可信").isNotEmpty();

    List<String> missing = new ArrayList<>();
    for (String expected : EXPECTED) {
      if (!actual.contains(expected)) {
        missing.add(expected);
      }
    }

    assertThat(missing)
        .describedAs(
            "SLA 工具链依赖的端点不见了（sla/ 不在仓库里，CI 跑不到它，所以这条必须在开源仓库守着）"
                + "%n清单：%s%n实际扫到 %d 条映射%n"
                + "★ 若这是有意改名：更新本测试的快照 + 同步 sla/contract.json 的 targetRange 与 toolingVersion",
            EXPECTED, actual.size())
        .isEmpty();
  }

  /**
   * 金丝雀：确认扫描结果里<strong>确实有别的端点</strong>，不是"刚好只扫到 EXPECTED 那几条"。
   *
   * <p>★ 理由与 {@code AuthorizationDeclarationTest} 同构：一条闸门如果对任何输入都通过， 它的绿说明不了任何事。这里钉的是"扫描面足够宽" ——
   * 若正则退化到只匹配少数几行， 上面那条测试可能仍然绿（因为那几条恰好还被匹配着），但扫描已经失去意义。
   */
  @Test
  void theScanSurfaceIsWiderThanTheSlaShortlist() {
    Set<String> actual = scanControllers();
    assertThat(actual)
        .describedAs("扫描面只覆盖 SLA 那几条 ⇒ 正则可能退化了，宽一点的断言才有意义")
        .hasSizeGreaterThan(EXPECTED.size());
    assertThat(actual).doesNotContain("GET /api/v1/this-endpoint-must-not-exist");
  }

  // ------------------------------------------------------------------ 扫描

  private static final Pattern CLASS_MAPPING =
      Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)", Pattern.DOTALL);
  private static final Pattern METHOD_MAPPING =
      Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\s*\\(([^)]*)\\)", Pattern.DOTALL);
  private static final Pattern BARE_METHOD_MAPPING =
      Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\b(?!\\s*\\()", Pattern.DOTALL);
  private static final Pattern LITERAL = Pattern.compile("\"([^\"]*)\"");

  /** 扫全部控制器源码，收集 "METHOD /拼接后的路径模板"。 */
  private static Set<String> scanControllers() {
    Set<String> out = new TreeSet<>();
    if (!Files.isDirectory(MAIN_JAVA)) {
      return out;
    }
    try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
      files
          .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
          .filter(SlaSurfaceContractTest::declaresRestController)
          .forEach(path -> out.addAll(parse(path)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static boolean declaresRestController(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8).contains("@RestController");
    } catch (IOException e) {
      return false;
    }
  }

  private static Set<String> parse(Path path) {
    String source;
    try {
      source = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return Set.of();
    }
    // 去掉块注释与行注释，避免把注释里举例的路径当成真映射
    String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");

    Matcher classMatcher = CLASS_MAPPING.matcher(code);
    List<String> basePaths = new ArrayList<>();
    if (classMatcher.find()) {
      basePaths.addAll(literalsOf(classMatcher.group(1)));
    }
    if (basePaths.isEmpty()) {
      basePaths.add("");
    }

    Set<String> out = new TreeSet<>();
    for (String base : basePaths) {
      Matcher mm = METHOD_MAPPING.matcher(code);
      while (mm.find()) {
        String http = mm.group(1).toUpperCase();
        List<String> subs = literalsOf(mm.group(2));
        if (subs.isEmpty()) {
          subs = List.of("");
        }
        for (String sub : subs) {
          out.add(http + " " + join(base, sub));
        }
      }
      Matcher bare = BARE_METHOD_MAPPING.matcher(code);
      while (bare.find()) {
        out.add(bare.group(1).toUpperCase() + " " + join(base, ""));
      }
    }
    return out;
  }

  private static List<String> literalsOf(String annotationArgs) {
    List<String> out = new ArrayList<>();
    // 只取 "value=" 或第一个位置参数里的字面量；这里简化：取注解实参里以 / 开头的字符串
    Matcher m = LITERAL.matcher(annotationArgs);
    while (m.find()) {
      String value = m.group(1).trim();
      if (value.startsWith("/")) {
        out.add(value);
      }
    }
    return out;
  }

  private static String join(String base, String sub) {
    String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    String right = sub.isEmpty() || sub.startsWith("/") ? sub : "/" + sub;
    String full = (left + right).replaceAll("/{2,}", "/");
    return full.isEmpty() ? "/" : full;
  }
}
