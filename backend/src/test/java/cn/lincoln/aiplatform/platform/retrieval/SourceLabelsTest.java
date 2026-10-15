package cn.lincoln.aiplatform.platform.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ 引用来源里那条<strong>约定常量</strong>的逐字值：「会话附件」（{@code #31} 判据 3 / 产品口径 2026-10-15 已裁定）。
 *
 * <h3>它钉的是什么（为什么需要一条断言，而不是"写在常量里就够了"）</h3>
 *
 * <p>会话临时的分段没有 {@code kb_knowledge_base} 行 ⇒ 左连接下 {@code kb_name} 是 {@code NULL}， 而契约 {@code
 * SourceRef.kbName} <strong>必填</strong> ⇒ 不给这条约定，会话附件的引用会被 <strong>逐条跳过、整批丢掉</strong>（有
 * WARN、界面一条都没有）。 而这个值同时被<strong>两种</strong>读法消费：
 *
 * <ol>
 *   <li>后端装配点用它填 {@code SourceRef.kbName}（{@code ChatService#kbNameOf}）；
 *   <li>前端把标签渲染成{@code 来源：<kbName>}（{@code RefPanel.vue}）—— <strong>前端只负责标签，值由后端产出</strong>。
 * </ol>
 *
 * <p>于是"这个字被改掉"这件事<strong>不会报错</strong>：界面照样渲染，只是那一条引用换了个名字 （而前端的判据是 {@code kbId} 缺失，与这个名字无关 ⇒
 * 不会有任何一处红）。 所以这个值本身必须有<strong>读数</strong>。
 *
 * <h3>★★ 它为什么能红（三条，逐条对应一种改法）</h3>
 *
 * <table>
 *   <tr><th>改法</th><th>哪一条红</th></tr>
 *   <tr><td>把常量的字改成别的（例：「会话文件」「本会话附件」）</td>
 *       <td>{@link #theConversationAttachmentLabelIsExactlyTheAgreedWording()}（行为读数）</td></tr>
 *   <tr><td>把常量删掉 / 改名</td><td>本类编译不过（引用它的两条断言都在）</td></tr>
 *   <tr><td>在 <strong>装配点</strong>另写一份字面量（常量还在，只是没人用）</td>
 *       <td>{@link #theLabelLiteralLivesInExactlyOneMainSourceFile()} —— 全 {@code src/main/java} 里
 *           "这个字的字面量"只许出现在一处</td></tr>
 * </table>
 *
 * <p>★ 它是怎么锚定"这个字长什么样"的：断言里<strong>逐字写下</strong>那四个字（{@code "会话附件"}）， 而第二条断言要求这个字面量在全 {@code
 * src/main/java} 里只出现<strong>一处</strong> —— 于是"值对不对"与"值有几处"由<strong>同一处</strong>字面量回答。★ 这里的字符串按
 * UTF-8 落在源码里， 与 {@code SourceLabels} 那个常量<strong>同一个形状</strong>（不是两个形状互相比较），
 * 所以谁把文件另存成别的编码，两条一起变、而不会各自漂。
 */
class SourceLabelsTest {

  /** ★ 裁定值「会话附件」（U+4F1A / U+8BDD / U+9644 / U+4EF6）。 */
  private static final String EXPECTED_LABEL = "会话附件";

  private static final Path MAIN_JAVA = Path.of("src", "main", "java");

  /** 它该住的那个文件（相对 {@code src/main/java}）。 */
  private static final String OWNER_FILE =
      "cn/lincoln/aiplatform/platform/retrieval/SourceLabels.java";

  /** ★ 扫的那一串必须带**引号**：注释与断言消息里提到这四个字是允许的（那不是在产出值）。 */
  private static final String LABEL_LITERAL = "\"" + EXPECTED_LABEL + "\"";

  /**
   * ★★ 行为读数：常量<strong>逐字</strong>等于裁定的那四个字。
   *
   * <p>★ 先把它的<strong>边界</strong>说清（免得被读成比它实际更强）：这一条只证明"常量与本类写的字逐字相同"，
   * 真正证明"生产代码里那个字就是这四个字"的是下面那条<strong>源码扫描</strong>（它要求这个字面量只存在一处、 且落在 {@link SourceLabels}
   * 里），而"常量真的被装进了 {@code refs}"由 {@code ChatStreamEventRecordingTest}
   * 的行为断言回答。三者合起来才是"值唯一、正确、且真的被用上"。
   */
  @Test
  void theConversationAttachmentLabelIsExactlyTheAgreedWording() {
    assertThat(SourceLabels.CONVERSATION_ATTACHMENT)
        .as("★★ 会话附件引用的 kbName 必须是这个值：前端把它渲染成 `来源：<kbName>`，" + "而会话域没有库行（kb_id 为空）⇒ 值只能由后端按约定给出")
        .isEqualTo(EXPECTED_LABEL);
  }

  /**
   * ★★ 这个字面量在全 {@code src/main/java} 里只许出现在<strong>一个文件</strong>：{@link SourceLabels} 自己。
   *
   * <p>它挡的正是"常量还在，但装配点另写了一份字面量"这种改法 —— 那种改法下前一条断言仍然绿， 而"值只有一处"这条纪律已经没了（下一个消费方会照抄最近的那一份）。★
   * 它同时是"这个字长什么样"的作者： 扫描用的串就是上面那个常量（带引号，见 {@link #LABEL_LITERAL}）。
   */
  @Test
  void theLabelLiteralLivesInExactlyOneMainSourceFile() {
    List<String> files = filesContaining(MAIN_JAVA, LABEL_LITERAL);

    assertThat(files)
        .as("★ 「会话附件」这个字面量只许有一处（它就是 SourceLabels 那个常量）：" + "第二处意味着「值归后端」变成了两处真相，而两处真相互相分叉时不会报错")
        .containsExactly(OWNER_FILE);
  }

  // ────────────────────────── 夹具（只读源码文本） ──────────────────────────

  /** 扫一棵源码树下哪些 {@code .java} 文件含有给定字面量（返回相对路径、已按 {@code /} 归一）。 */
  private static List<String> filesContaining(Path root, String needle) {
    List<String> found = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        if (!path.toString().endsWith(".java")) {
          continue;
        }
        if (read(path).contains(needle)) {
          found.add(root.relativize(path).toString().replace('\\', '/'));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    found.sort(String::compareTo);
    return found;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
