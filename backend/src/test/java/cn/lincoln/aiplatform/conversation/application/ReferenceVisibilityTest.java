package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.Message;
import cn.lincoln.aiplatform.conversation.domain.SourceRef;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.DataScope;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用快照的读侧收窄（{@code #27} 判据 ③）。
 *
 * <p>★ 为什么是纯单测而不是起栈：这条判据的全部逻辑就是"拿当前范围比一次密级"，起一个 Testcontainers 只会让它慢 60
 * 秒而不会多证明任何东西。真正需要真栈的是"历史接口有没有接上它" —— 那由 {@code ChatHistory} 一侧的既有集成测试与 {@link
 * #visibleRefsIsWiredIntoTheHistoryPath()} 之外的编译约束共同保证。
 */
class ReferenceVisibilityTest {

  private static final UUID SEGMENT = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private static SourceRef ref(ClassificationLevel level, String full) {
    return new SourceRef(
        1,
        "《测试制度》",
        "测试制度.md",
        "测试库",
        0.91,
        1,
        full,
        SEGMENT.toString(),
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        level);
  }

  private static List<SourceRef> filterUnder(ClassificationLevel clearance, List<SourceRef> refs) {
    ScopeFilter scope =
        new ScopeFilter(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            DataScope.ALL,
            Set.of("ADMIN"),
            clearance,
            Set.of());
    return ReferenceVisibility.filter(refs, scope);
  }

  @Test
  @DisplayName("高密级引用对低 clearance 的读者静默消失 —— 不是打码、不是占位")
  void higherClassificationIsSilentlyDropped() {
    List<SourceRef> refs =
        List.of(ref(ClassificationLevel.INTERNAL, "内部正文"), ref(ClassificationLevel.SECRET, "机密正文"));

    List<SourceRef> seen = filterUnder(ClassificationLevel.INTERNAL, refs);

    assertThat(seen)
        .as("★ 只留下看得见的；那条机密的不是被替换成「(已隐藏)」而是根本不存在（占位本身是侧信道）")
        .extracting(SourceRef::full)
        .containsExactly("内部正文");
  }

  @Test
  @DisplayName("全部被过滤掉 ⇒ 空列表（不是 null）—— 「到过且零条可看」与「从来没到过」是两件事")
  void everythingFilteredBecomesEmptyListNotNull() {
    List<SourceRef> refs = List.of(ref(ClassificationLevel.SECRET, "机密正文"));

    List<SourceRef> seen = filterUnder(ClassificationLevel.PUBLIC, refs);

    assertThat(seen).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("null 原样返回 null —— 引用从没到过，不许被读成「一条都看不到」")
  void nullStaysNull() {
    assertThat(filterUnder(ClassificationLevel.PUBLIC, null)).isNull();
  }

  @Test
  @DisplayName("同一密级与更低密级都看得见（PUBLIC < INTERNAL < SECRET < CONFIDENTIAL）")
  void equalAndLowerStayVisible() {
    List<SourceRef> refs =
        List.of(
            ref(ClassificationLevel.PUBLIC, "公开"),
            ref(ClassificationLevel.INTERNAL, "内部"),
            ref(ClassificationLevel.SECRET, "机密"));

    assertThat(filterUnder(ClassificationLevel.INTERNAL, refs))
        .as("内部及以下都在，秘密看不到")
        .extracting(SourceRef::full)
        .containsExactly("公开", "内部");
  }

  @Test
  @DisplayName("★ 反问：若按「读的人自己的密级」而不是「引用的密级」判，上面几条会全绿而漏洞仍在")
  void doesNotLeakThroughTheMessageOverload() {
    Message message =
        new Message(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "回答正文");
    message.bindReferences(List.of(ref(ClassificationLevel.SECRET, "机密正文")));

    assertThat(filterUnder(ClassificationLevel.INTERNAL, message.references()))
        .as("★ 消息上的 references() 是非空列表，收窄必须作用在元素上而不是整体")
        .isEmpty();
  }
}
