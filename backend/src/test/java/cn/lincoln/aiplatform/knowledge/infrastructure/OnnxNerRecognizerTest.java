package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OnnxNerRecognizer} 的判据。
 *
 * <h3>为什么把这个类拆成"不碰模型"与"必须碰模型"两组</h3>
 *
 * <p>模型 388MB，不进仓库、CI runner 上也没有。所以：
 *
 * <ul>
 *   <li><b>不碰模型的判据</b>（标签表解析、碎片收敛、后缀过滤、关系返回空）—— 用 {@code @TempDir} 现场造假 config.json
 *       与假后缀表，<b>永远跑</b>。 这些判据覆盖的正是"写错也不会报异常"的那些地方，必须有 CI 覆盖。
 *   <li><b>必须碰模型的判据</b>（真抽出一段机构名）—— 用 {@link Assumptions} 在缺文件时跳过， 本地跑得到、CI
 *       上跳过。<b>跳过不是通过</b>，所以它在本地必须有读数。
 * </ul>
 *
 * <h3>★ 这个类是为了防"写完了抽不出东西"</h3>
 *
 * <p>第一版 {@code OnnxNerRecognizer} 的 {@code labels()} 返回空、一个实体都抽不出来：
 * 能编译、能过"返回空"的测试、名字也对，但功能是零。所以这里的判据不许断言"返回非 null"， 必须是"返回的集合里真的有这一条"。
 */
class OnnxNerRecognizerTest {

  private static final Path MODEL_DIR =
      Path.of("..", "models", "bert-chinese-ner-onnx").toAbsolutePath().normalize();

  // ── 不依赖模型文件的判据 ────────────────────────────────────────────────

  @Test
  @DisplayName("从 config.json 的 id2label 读标签，且按数值排序（\"10\" 不许排在 \"2\" 前面）")
  void readsLabelsInNumericOrder(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("config.json"),
        """
        {
          "id2label": {
            "0": "O",
            "2": "B-organization",
            "10": "I-organization",
            "1": "B-position"
          }
        }
        """);

    List<String> labels = OnnxNerRecognizer.readLabels(dir.resolve("config.json"));

    // 字典序会把 "10" 排在 "2" 前面 ⇒ 第 2 号标签会被读成 B-organization 之外的东西
    assertThat(labels).containsExactly("O", "B-position", "B-organization", "I-organization");
  }

  @Test
  @DisplayName("config.json 里没有机构类标签时直接失败，不许静默加载")
  void refusesConfigWithoutOrgLabel(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("config.json"),
        """
        { "id2label": { "0": "O", "1": "B-position", "2": "I-position" } }
        """);

    assertThatThrownBy(() -> OnnxNerRecognizer.readLabels(dir.resolve("config.json")))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("没有任何机构类");
  }

  @Test
  @DisplayName("后缀表为空时构造失败：收敛层会把所有候选都丢掉")
  void rejectsEmptySuffixTable() {
    assertThatThrownBy(() -> new OnnxNerRecognizer(MODEL_DIR, 2, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("机构后缀为空");
  }

  @Test
  @DisplayName("碎片收敛：留『生产制造部』，丢掉『生』『生产』『生产制造』")
  void compactsFragments() {
    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 2);

    List<String> compacted = recognizer.compact(List.of("生", "生产", "生产制造", "生产制造部"));

    assertThat(compacted).containsExactly("生产制造部");
  }

  @Test
  @DisplayName("碎片收敛：去掉虚词前缀（『的财务资产部』→『财务资产部』）")
  void stripsVirtualWordPrefix() {
    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 2);

    // ★ 用单字虚词：「的」在表里，剥掉就停。多字词（如「应配合」）会停在第一个表外的字，
    // 那是这套"排除法"的已知漏网 —— 不加一个四字的表就修不了，而加四字的表会误伤真名字，
    // 所以这一层只承诺处理单字虚词，多字噪声靠后续的字典过滤与人工确认。
    assertThat(recognizer.compact(List.of("的财务资产部"))).containsExactly("财务资产部");
    assertThat(recognizer.compact(List.of("和采购供应部"))).containsExactly("采购供应部");
  }

  @Test
  @DisplayName("碎片收敛：不以机构后缀结尾的候选丢掉（『供应链协同平台』不是机构）")
  void dropsSpansWithoutOrgSuffix() {
    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 2);

    assertThat(recognizer.compact(List.of("供应链协同平台", "财务资产部"))).containsExactly("财务资产部");
  }

  @Test
  @DisplayName("关系返回空，且这是'这一层不抽'而不是'没有关系'")
  void relationsAreNotHandledHere() {
    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 2);

    // 关系由 EntityExtractor 的标记词规则抽；如果这个类哪天开始返回非空，
    // 说明有人加了第二条关系抽取路径，那条路径必须同时解决"与规则冲突时谁赢"。
    assertThat(recognizer.relations("综合部隶属财务资产部管理。")).isEmpty();
    assertThat(recognizer.supportedTypes()).containsExactly("ORG");
  }

  @Test
  @DisplayName("模型文件不存在时不抛异常，只返回空（并且只警告一次）")
  void missingModelReturnsEmptyInsteadOfThrowing(@TempDir Path dir) {
    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(dir, 2);

    assertThat(recognizer.recognize("财务资产部负责解释本制度。")).isEmpty();
    // 第二次调用走 loadFailure 缓存，不应该重复尝试加载
    assertThat(recognizer.recognize("采购供应部牵头。")).isEmpty();
  }

  // ── 必须碰模型的判据（缺文件时跳过，跳过不是通过）──────────────────────

  @Test
  @DisplayName("真推理：从制度文本里抽出机构名")
  void recognizesOrganizationFromRealText() {
    Assumptions.assumeTrue(
        Files.exists(MODEL_DIR.resolve("model.onnx")),
        "缺模型文件：python tools/fetch_onnx_models.py --only ner");

    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 4);
    try {
      List<String> names =
          recognizer.recognize("本制度由财务资产部负责解释，采购供应部配合执行。").stream()
              .map(EntityRecognizerName::of)
              .toList();

      assertThat(names).contains("财务资产部");
      // 人名不许出现：这个类只声明支持 ORG
      assertThat(names).allMatch(n -> recognizer.supportedTypes().contains("ORG"));
    } finally {
      recognizer.close();
    }
  }

  @Test
  @DisplayName("边界扩展：模型把『部』标成 O 时，机构名不能被截断（0.6.6）")
  void spanBoundaryIsExtendedToOrgSuffix() {
    Assumptions.assumeTrue(
        Files.exists(MODEL_DIR.resolve("model.onnx")),
        "缺模型文件：python tools/fetch_onnx_models.py --only ner");

    OnnxNerRecognizer recognizer = new OnnxNerRecognizer(MODEL_DIR, 4);
    try {
      List<String> names =
          recognizer
              .recognize("信息技术部是公司信息化建设的归口管理部门。" + "财务资产部负责预算编制与资金审核。" + "采购供应部主管供应商准入与合同签订。")
              .stream()
              .map(EntityRecognizerName::of)
              .toList();

      // ★ 这条是 2026-11-11 真栈撞出来的：三个机构只抽到一个。
      //   模型的原始输出是 [信息技术部, 财务资产, 采购供应] —— 后两个的「部」被标成 O
      //   （WordPiece 边界问题），截断后的「财务资产」不以「部」结尾 ⇒ 被 compact() 的后缀
      //   过滤整条丢掉。★ 后果不是"少一个字"，是"整条实体消失且没有日志"。
      //   去掉 decode() 里的 isOrgSuffixStart 分支，这条会红（只剩「信息技术部」）。
      assertThat(names).containsExactlyInAnyOrder("信息技术部", "财务资产部", "采购供应部");
    } finally {
      recognizer.close();
    }
  }

  /** 只为在断言里取名字，避免把整个 record 塞进断言。 */
  private static final class EntityRecognizerName {
    private static String of(
        cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer.Recognized recognized) {
      return recognized.name();
    }
  }
}
