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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 测试方法命名：<strong>标识符英文、说明写进 javadoc/注释</strong>（0.3.2 定案）。
 *
 * <h3>为什么定这条（用户原话的理由比"好看"硬）</h3>
 *
 * <p>「<em>后续代码如果交给人工维护，更大的可能是使用 javadoc 提取文档，而不是根据方法名来处理</em>。」
 * 既然如此，把中文放进<strong>标识符</strong>里就没有收益，却把<strong>编码耦合</strong>引进了代码：
 *
 * <ul>
 *   <li>任何一个用非 UTF-8 读写源码的工具都会让标识符变形 ⇒ 编译失败或名字变乱码；
 *   <li>本项目已经因这类问题栽过两次：PowerShell 的 {@code Set-Content -Encoding ASCII} 把中文变成 {@code ???}（96
 *       处、文件报废）；{@code .cmd} 里的中文注释被 cmd 按 GBK 读， 连带把同一文件的 {@code set VITE_USE_MOCK=false}
 *       一起吃掉（前端静默跑成 mock）。
 *   <li>而中文该待的地方（<strong>为什么这么写</strong>）本来就在 javadoc 里 —— 那里是文档的入口。
 * </ul>
 *
 * <h3>为什么带一份"历史遗留清单"</h3>
 *
 * <p>定案时全测试树有 <strong>43 个文件</strong>用的是中文方法名（从 0.3.0 起就这么写的）。 一次性全量重命名是约 200
 * 个方法的机械改动，混在任何一批功能改动里都会让 diff 没法审。 所以按本项目既有的"防黑洞"做法（同 {@code ContractDriftTest} 的未实现清单）：
 * <strong>新代码立即禁掉，既有的登记在册</strong>，并且——<strong>清单必须随清理收缩</strong>：
 * 某个文件已经改干净了却还留在清单里，这条测试就会红，逼人把它删掉。
 */
class TestNamingConventionTest {

  /**
   * {@code void 中文名(} —— 只看<strong>方法声明行</strong>，注释与断言消息是中文（那是刻意的）。
   *
   * <p>★ 正则必须锚在行首（{@code ^\s*}）：第一版没锚，结果**把本类 javadoc 里的示例** （那行 {@code void 中文名(}）当成了违规 ——
   * 闸门抓自己，虽然是"判据太宽"而不是误报， 但也说明"扫源码文本"这类判据必须说清它认的是哪一行。
   */
  private static final Pattern CHINESE_TEST_METHOD =
      Pattern.compile(
          "^\\s*(?:public |private |protected |static |final"
              + " )*void\\s+([\\p{IsHan}][\\p{IsHan}\\w]*)");

  private static final Path TEST_ROOT = Path.of("src", "test", "java");

  /**
   * 历史遗留：定案时仍有中文测试方法名的文件（相对 {@code src/test/java}）。
   *
   * <p>★ 清理规则：改干净一个就删一个 —— 留着不删会被 {@link #grandfatheredListMustShrink()} 判红。
   */
  private static final Set<String> GRANDFATHERED =
      Set.of(
          "cn/lincoln/aiplatform/AccessScopeUniquenessTest.java",
          "cn/lincoln/aiplatform/AuthorizationDeclarationTest.java",
          "cn/lincoln/aiplatform/ContractDriftTest.java",
          "cn/lincoln/aiplatform/MigrationIntegrationTest.java",
          "cn/lincoln/aiplatform/ModuleBoundaryTest.java",
          "cn/lincoln/aiplatform/PlatformPurityTest.java",
          "cn/lincoln/aiplatform/identity/ComplianceTest.java",
          "cn/lincoln/aiplatform/identity/SecurityChainTest.java",
          "cn/lincoln/aiplatform/identity/UserRoleManagementTest.java",
          "cn/lincoln/aiplatform/identity/domain/PasswordPolicyTest.java",
          "cn/lincoln/aiplatform/identity/infrastructure/CaptchaServiceTest.java",
          "cn/lincoln/aiplatform/identity/infrastructure/JwtTokenServiceTest.java",
          "cn/lincoln/aiplatform/identity/infrastructure/PermissionCatalogTest.java",
          "cn/lincoln/aiplatform/knowledge/DocumentManagementTest.java",
          "cn/lincoln/aiplatform/knowledge/DocumentParseTest.java",
          "cn/lincoln/aiplatform/knowledge/DocumentProgressTest.java",
          "cn/lincoln/aiplatform/knowledge/DocumentRegistrationTest.java",
          "cn/lincoln/aiplatform/knowledge/DocumentStateMachineTest.java",
          "cn/lincoln/aiplatform/knowledge/EmbeddingPipelineTest.java",
          "cn/lincoln/aiplatform/knowledge/KnowledgeBaseManagementTest.java",
          "cn/lincoln/aiplatform/knowledge/MarkdownChunkerTest.java",
          "cn/lincoln/aiplatform/knowledge/NoiseStripperTest.java",
          "cn/lincoln/aiplatform/knowledge/OcrEngineTest.java",
          "cn/lincoln/aiplatform/knowledge/OcrIngestionTest.java",
          "cn/lincoln/aiplatform/knowledge/OcrPageInventoryTest.java",
          "cn/lincoln/aiplatform/knowledge/OcrParserTest.java",
          "cn/lincoln/aiplatform/knowledge/ParseMetricsTest.java",
          "cn/lincoln/aiplatform/knowledge/SearchTextTest.java",
          "cn/lincoln/aiplatform/knowledge/SegmentContentTest.java",
          "cn/lincoln/aiplatform/knowledge/SegmentEmbeddingRepositoryTest.java",
          "cn/lincoln/aiplatform/knowledge/SegmentIngestionTest.java",
          "cn/lincoln/aiplatform/knowledge/SegmenterTest.java",
          "cn/lincoln/aiplatform/knowledge/infrastructure/OcrEngineFactsTest.java",
          "cn/lincoln/aiplatform/modelcatalog/ModelApiTest.java",
          "cn/lincoln/aiplatform/modelcatalog/ModelCapabilityProbeTest.java",
          "cn/lincoln/aiplatform/modelcatalog/ModelPersistenceTest.java",
          "cn/lincoln/aiplatform/modelcatalog/OpenAiStubContractTest.java",
          "cn/lincoln/aiplatform/modelcatalog/UpstreamEmbeddingAdapterTest.java",
          "cn/lincoln/aiplatform/modelcatalog/domain/ModelStateMachineTest.java",
          "cn/lincoln/aiplatform/modelcatalog/infrastructure/ApiKeyCipherTest.java",
          "cn/lincoln/aiplatform/platform/access/ScopeFilterTest.java",
          "cn/lincoln/aiplatform/platform/web/ApiResponseTest.java");

  @Test
  void identifiersMustBeAscii_ChineseBelongsToJavadoc() {
    List<String> offenders = new ArrayList<>();
    forEachTestSource(
        (relative, lineNumber, line) -> {
          Matcher matcher = CHINESE_TEST_METHOD.matcher(line);
          if (matcher.find() && !GRANDFATHERED.contains(relative)) {
            offenders.add(relative + ":" + lineNumber + " → void " + matcher.group(1) + "(");
          }
        });

    assertThat(offenders)
        .as(
            "测试方法名必须是 ASCII 标识符：中文的「为什么」写进 javadoc/注释。"
                + "★ 换掉它的理由不是好看 —— 是同一个标识符一旦被非 UTF-8 的工具读写就会变形；"
                + "而文档的入口本来就是 javadoc。清单外的文件即新增违规：%s",
            offenders)
        .isEmpty();
  }

  @Test
  void grandfatheredListMustShrink() {
    List<String> cleaned = new ArrayList<>();
    for (String relative : GRANDFATHERED) {
      Path path = TEST_ROOT.resolve(relative);
      if (!Files.exists(path)) {
        cleaned.add(relative + "（文件已不存在）");
        continue;
      }
      if (!containsChineseMethod(read(path))) {
        cleaned.add(relative);
      }
    }

    assertThat(cleaned)
        .as("这些文件已经改干净了，把它们从 GRANDFATHERED 里删掉 —— 否则清单会变成永久黑洞：%s", cleaned)
        .isEmpty();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private interface LineVisitor {
    void accept(String relative, int lineNumber, String line);
  }

  private static void forEachTestSource(LineVisitor visitor) {
    try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                String relative = slash(TEST_ROOT.relativize(path));
                List<String> lines = read(path);
                for (int i = 0; i < lines.size(); i++) {
                  visitor.accept(relative, i + 1, lines.get(i));
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean containsChineseMethod(List<String> lines) {
    for (String line : lines) {
      if (CHINESE_TEST_METHOD.matcher(line).find()) {
        return true;
      }
    }
    return false;
  }

  private static List<String> read(Path path) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }
}
