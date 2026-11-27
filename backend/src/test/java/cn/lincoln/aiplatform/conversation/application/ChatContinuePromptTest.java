package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 续写时那一段提示词的形状（{@code #371} 第 41 条 · {@code ChatMode.CONTINUE}）。
 *
 * <h3>★ 这一段是"续写能不能真的接上"的唯一杠杆</h3>
 *
 * <p>落库那边只负责把增量追加到原正文后面；能不能<strong>接得上</strong>，取决于模型收到的提示词里
 * 有没有"这是已经写出的部分、从断开处继续、不要重复"。没有这一段，模型会从第一句开始重新答， 而界面上它看起来像"续写没生效"（后半段与前半段重复）。
 *
 * <p>★ 反射调私有方法：它只做字符串拼接、没有副作用。反射失败是<strong>运行期</strong>的 —— 签名再变，这里会红，而那正是它该红的地方。
 */
class ChatContinuePromptTest {

  private static String promptOf(
      String question,
      List<SourceRef> refs,
      String systemPrompt,
      Map<UUID, List<String>> neighbors,
      String memoryBlock,
      String continueFrom)
      throws Exception {
    Method m =
        ChatService.class.getDeclaredMethod(
            "promptOf",
            String.class,
            List.class,
            String.class,
            Map.class,
            String.class,
            String.class);
    m.setAccessible(true);
    return (String)
        m.invoke(null, question, refs, systemPrompt, neighbors, memoryBlock, continueFrom);
  }

  /** 续写时：已写出的正文与三条禁令都要进提示词。 */
  @Test
  void continuedPromptCarriesTheAlreadyWrittenTextAndTheThreeRules() throws Exception {
    String prompt = promptOf("住宿标准是多少？", List.of(), null, Map.of(), "", "差旅住宿标准为每人每天 500 元，超出部分");

    assertThat(prompt).as("★★ 已经写出的正文必须原文进提示词 —— 否则模型不知道从哪儿接").contains("差旅住宿标准为每人每天 500 元，超出部分");
    assertThat(prompt).as("★★ 必须明写「接着往下写」，否则模型会从第一句重新答（界面上就是后半段与前半段重复）").contains("接着往下写");
    assertThat(prompt).as("不许重复已写内容").contains("不要重复已经写过的内容");
    assertThat(prompt).as("不许出现「接着上文」这类元叙述 —— 它会作为回答正文的一部分被存档").contains("接着上文");
    assertThat(prompt).as("不许新增引用编号：这一轮没有重新检索，新编号背后没有来源，点了会跳空").contains("不要新增引用编号");
  }

  /** 反证：不是续写时，这一段一个字都不许出现。 */
  @Test
  void ordinaryPromptHasNoContinuationSection() throws Exception {
    String withNull = promptOf("住宿标准是多少？", List.of(), null, Map.of(), "", null);

    assertThat(withNull)
        .as("★ 反证：不续写的路径不能被这一段污染 —— 否则每轮都会让模型「接着上文写」")
        .doesNotContain("接着往下写")
        .doesNotContain("已经写出的部分");
    assertThat(withNull).as("普通路径逐字不变：系统口径 + 问题，没有参考资料段（本例零引用）").isEqualTo("住宿标准是多少？");
  }

  /** 有引用时：续写段在问题之后、参考资料之前（顺序错了模型会把资料当成"已写内容"的一部分）。 */
  @Test
  void continuationSectionSitsBeforeTheReferences() throws Exception {
    UUID segment = UUID.randomUUID();
    String prompt =
        promptOf("住宿标准是多少？", List.of(ref(segment)), "你是差旅助手。", Map.of(), "", "差旅住宿标准为每人每天 500 元");

    int written = prompt.indexOf("差旅住宿标准为每人每天 500 元");
    int refs = prompt.indexOf("参考资料：");
    assertThat(written).as("已写内容必须在").isGreaterThanOrEqualTo(0);
    assertThat(refs).as("参考资料段必须在").isGreaterThanOrEqualTo(0);
    assertThat(written).as("★★ 已写内容必须排在「参考资料：」之前 —— 反过来模型会把资料读成它的上半段").isLessThan(refs);
    assertThat(prompt).as("系统口径仍排在最前面").startsWith("你是差旅助手。");
    assertThat(prompt).as("引用正文仍要在").contains("住宿标准按职级分档。");
  }

  private static SourceRef ref(UUID segmentId) {
    return new SourceRef(
        1,
        "差旅管理办法",
        "差旅管理办法.pdf",
        "行政制度库",
        0.91,
        1,
        "住宿标准按职级分档。",
        segmentId.toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        ClassificationLevel.INTERNAL);
  }
}
