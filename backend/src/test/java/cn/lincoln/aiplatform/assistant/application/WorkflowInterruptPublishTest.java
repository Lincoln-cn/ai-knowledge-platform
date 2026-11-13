package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 人工介入节点（追问 / 表单收集）能不能发布。
 *
 * <p>★ 这个文件在 {@code #338} 被<strong>翻转</strong>过，理由必须写清，否则下一个人会以为它是"为了让谁变绿而改的"：
 *
 * <ul>
 *   <li><strong>原来</strong>：断言含 {@code QUESTION_NODE} / {@code FORM_NODE}
 *       的图<strong>必须被拒绝发布</strong> （错误码 {@code INTERRUPT_NODE}），理由是 {@code #113} 那会儿"本版本不支持中断点"。
 *   <li><strong>现在</strong>：断言这两类图<strong>必须能发布</strong>。理由不是"拒绝太严"，而是当初那条理由已经不成立 —— 0.7.4
 *       落地了中断与恢复整条链（{@code WorkflowInterrupted} → {@code as_workflow_pause}（V97）→ {@code
 *       WorkflowResumeService}），目录里这两个节点也标成了 {@code executable:true}。再拦一道的结果是
 *       "能跑的图发不出去"，而用户没有任何办法绕过。
 * </ul>
 *
 * <p>★ 放开闸门之前先确认了它真的能跑完：{@code
 * WorkflowResumeServiceTest#resumeStartsAfterTheInterruptAndFinishesTheGraph} 是这一批新加的，它第一次跑就红了 ——
 * 恢复链路原来把恢复点传成了中断点本身，答复之后原地再停一次， 图上什么都不会发生。原来四条测试只覆盖"还没开始跑就被挡下"，所以这个一直没被发现。
 *
 * <p>★ 反证仍在：{@code aPlannedTypeIsStillRejected} 钉住"目录说跑不了的类型照旧拒绝发布"—— 否则"删除 INTERRUPT_NODE
 * 规则"就会被读成"发布校验不管节点类型了"。
 */
@DisplayName("#338 · 人工介入节点可以发布，规划中的节点仍然不行")
class WorkflowInterruptPublishTest {

  private final WorkflowValidationService service =
      new WorkflowValidationService(new NodeTypeCatalog(new ObjectMapper()));

  private static WorkflowValidationService.Node node(String id, String type) {
    return new WorkflowValidationService.Node(id, type, "标题-" + id);
  }

  private static WorkflowValidationService.Edge edge(String source, String target) {
    return new WorkflowValidationService.Edge(source + "-" + target, source, target);
  }

  private static List<String> codes(WorkflowValidationService.Result result) {
    return result.issues().stream().map(WorkflowValidationService.Issue::code).toList();
  }

  /**
   * 追问节点（{@code QUESTION_NODE}）是人工介入，执行到它会停下来等答复、答复到了再往下跑 ⇒ 可以发布。
   *
   * <p>★ 图刻意挑「除中断点外一切合规」的形状（开始 → 追问 → 回复）：这样这条测试只归因于中断点本身， 不会被「没有输出节点」这类别的错误顺手拦下或放行。
   */
  @Test
  @DisplayName("含追问节点（QUESTION_NODE）的图可以发布")
  void aGraphWithAQuestionNodePublishes() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"),
                    node("q1", "QUESTION_NODE"),
                    node("r1", "REPLY_NODE")),
                List.of(edge("s1", "q1"), edge("q1", "r1"))));

    assertThat(result.passed())
        .as(
            "含人工介入节点的图必须能发布（#338）—— 中断与恢复已经落地，拒绝发布等于让能跑的图发不出去。" + "若这里红了，先看 codes()：%s",
            codes(result))
        .isTrue();
    assertThat(codes(result)).doesNotContain("INTERRUPT_NODE");
  }

  /** 表单收集节点（{@code FORM_NODE}）同属人工介入：同样可以发布。 */
  @Test
  @DisplayName("含表单收集节点（FORM_NODE）的图可以发布")
  void aGraphWithAFormNodePublishes() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"), node("f1", "FORM_NODE"), node("r1", "REPLY_NODE")),
                List.of(edge("s1", "f1"), edge("f1", "r1"))));

    assertThat(result.passed()).as("含表单收集节点的图必须能发布（#338）；codes=%s", codes(result)).isTrue();
    assertThat(codes(result)).doesNotContain("INTERRUPT_NODE");
  }

  /**
   * 反证：目录里 {@code executable:false} 的节点（图片生成）照旧拒绝发布。
   *
   * <p>★ 没有这一条，"删掉 INTERRUPT_NODE 规则"就会被读成"发布校验不再看节点类型"—— 而它仍然看，只是不再按 "是不是人工介入"这个维度看。
   */
  @Test
  @DisplayName("反证 · 规划中的节点（IMAGE_GENERATE_NODE）仍然拒绝发布")
  void aPlannedTypeIsStillRejected() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"),
                    node("g1", "IMAGE_GENERATE_NODE"),
                    node("r1", "REPLY_NODE")),
                List.of(edge("s1", "g1"), edge("g1", "r1"))));

    assertThat(result.passed()).as("规划中的节点还没实现，发布后这一步不会执行 ⇒ 必须拒绝").isFalse();
    assertThat(codes(result)).contains("PLANNED_TYPE");
  }

  /** 反证：一张「没有中断点」的干净图必须照常通过 —— 否则上面两条只是「发布校验被删空」的假绿。 */
  @Test
  @DisplayName("反证 · 不含中断点的干净图必须照常通过")
  void aCleanGraphWithoutInterruptsStillPublishes() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"), node("c1", "AI_CHAT_NODE"), node("r1", "REPLY_NODE")),
                List.of(edge("s1", "c1"), edge("c1", "r1"))));

    assertThat(result.passed()).as("反证：不含中断点的图必须通过；codes=%s", codes(result)).isTrue();
  }
}
