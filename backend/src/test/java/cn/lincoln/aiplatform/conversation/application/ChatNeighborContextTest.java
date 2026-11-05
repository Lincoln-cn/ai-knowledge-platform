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
 * {@code #65} 的查询侧上下文窗口：邻居段只进「给模型的上下文」，<strong>不产生新引用</strong>。
 *
 * <p>★★ 这里测的是两件容易做错的事：
 *
 * <ol>
 *   <li><strong>引用条数与编号不许变</strong> —— 邻居段不是「多召回的一条」，它是主块的邻座。 一旦它占了一个 {@code [N]}，正文里的编号就与 {@code
 *       refs} 的条数对不上， 而那正是 O5「引用一致性」要抓的 {@code dangling_refs}。
 *   <li><strong>开关不开时输出逐字不变</strong> —— 与 {@code #297} 的 {@code queryCount=0}、 {@code #283} 的
 *       {@code multiHopEnabled=false} 同一条判据：凡是在问答主链路上多一次 数据库查询的开关，缺省必须与「不做这件事」一致。
 * </ol>
 */
class ChatNeighborContextTest {

  private static SourceRef ref(int index, UUID segmentId, String full) {
    return new SourceRef(
        index,
        "第一节",
        "员工手册.pdf",
        "制度库",
        1.0,
        index,
        full,
        segmentId.toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        ClassificationLevel.INTERNAL);
  }

  /** 反射调私有的四参 {@code promptOf}（它只做字符串拼接，没有副作用，测试不必起上下文）。 */
  private static String promptOf(
      String question, List<SourceRef> refs, String systemPrompt, Map<UUID, List<String>> neighbors)
      throws Exception {
    Method m =
        ChatService.class.getDeclaredMethod(
            "promptOf", String.class, List.class, String.class, Map.class);
    m.setAccessible(true);
    return (String) m.invoke(null, question, refs, systemPrompt, neighbors);
  }

  @Test
  void neighborTextIsNotAnExtraCitationAndHasNoIndexOfItsOwn() throws Exception {
    UUID segment = UUID.randomUUID();
    String prompt =
        promptOf(
            "住宿标准是多少？",
            List.of(ref(1, segment, "差旅住宿标准为每人每天 500 元。")),
            null,
            Map.of(segment, List.of("上一段的尾巴：具体见下表。", "附表一 住宿标准明细。")));

    assertThat(prompt).as("主块正文必须在").contains("差旅住宿标准为每人每天 500 元。");
    assertThat(prompt).as("邻座正文必须进上下文").contains("附表一 住宿标准明细。");
    assertThat(prompt).as("邻居段必须标明不单独编号，否则模型会自己编一个 [2] 去引用它").contains("不单独编号");
    assertThat(prompt).as("全文只能出现一个编号 [1]").containsOnlyOnce("[1]");
    assertThat(prompt).as("不许出现 [2]").doesNotContain("[2]");
  }

  @Test
  void noNeighborsKeepsThePromptByteForByteIdentical() throws Exception {
    UUID segment = UUID.randomUUID();
    String withNone = promptOf("问题", List.of(ref(1, segment, "正文")), "系统口径", Map.of());
    String withEmpty =
        promptOf("问题", List.of(ref(1, segment, "正文")), "系统口径", Map.of(segment, List.of()));
    assertThat(withEmpty).as("开关关（邻居为空 Map）与取到 0 条邻居必须给出同一份提示词").isEqualTo(withNone);
    assertThat(withNone).doesNotContain("不单独编号");
  }
}
