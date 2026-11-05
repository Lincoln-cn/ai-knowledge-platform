package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 判据 6（{@code #113}）：钉住「当前没做中断点识别」这件事的<strong>先红后绿</strong>证据。
 *
 * <p>★ 这条测试的<strong>断言是期望行为</strong>（含人工介入节点的图必须被拒绝发布）， 而它在收紧之前会<strong>红</strong> ——
 * 红恰恰证明现状是「放行」：今天 {@code WorkflowValidationService} 只把 {@code implemented:false} 的类型当 {@code
 * WARNING} 放过， 而 {@code QUESTION_NODE}（追问）/ {@code FORM_NODE}（表单收集）这两类<strong>人工介入</strong>节点
 * 更是连警告都不出（{@code QUESTION_NODE} 在目录里还被错标成 {@code implemented:true}）。于是含中断点的图今天能一路发布成功。
 *
 * <p>★ 两次读数都必须存档（这是判据 6 的唯一证据）：
 *
 * <ol>
 *   <li><strong>收紧前</strong>：本测试 <em>FAILED</em> —— 因为 {@code passed()==true}（图被放行了）。
 *   <li><strong>收紧后</strong>：本测试 <em>PASSED</em> —— 中断点被认出来、发布被拒（{@code passed()==false}）。
 * </ol>
 *
 * <p>★ 图刻意挑「除中断点外一切合规」的形状（开始 → 追问 → 回复）：这样收紧前后唯一变化的就是 中断点识别本身，红与绿都只归因于它，而不是被「没有输出节点」这类别的错误顺手拦下。
 */
@DisplayName("判据6 · 含人工介入节点的图必须被拒绝发布（先红后绿）")
class WorkflowInterruptPublishTest {

  private final WorkflowValidationService service =
      new WorkflowValidationService(new NodeTypeCatalog(new ObjectMapper()));

  private static WorkflowValidationService.Node node(String id, String type) {
    return new WorkflowValidationService.Node(id, type, "标题-" + id);
  }

  private static WorkflowValidationService.Edge edge(String source, String target) {
    return new WorkflowValidationService.Edge(source + "-" + target, source, target);
  }

  /**
   * 追问节点（{@code QUESTION_NODE}）是人工介入：本版本不支持，含它的图必须拒绝发布。
   *
   * <p>★ 这一条在收紧前必红（现状放行），是判据 6「当前没做识别」的证据。
   */
  @Test
  @DisplayName("含追问节点（QUESTION_NODE）的图必须被拒绝发布")
  void aGraphWithAQuestionNodeMustNotPublish() {
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
            "含人工介入节点（QUESTION_NODE）的图必须拒绝发布 —— 本版本不支持人工介入"
                + "（09 §5.3.2 ②）。收紧前这里会红：现状把它当合规放行了（passed()==true）")
        .isFalse();
  }

  /**
   * 表单收集节点（{@code FORM_NODE}）同属人工介入：本版本不支持，含它的图必须拒绝发布。
   *
   * <p>★ 与追问节点一起把「人工介入」这一类钉全（{@code FORM_NODE} 在目录里是 {@code implemented:false}）。
   */
  @Test
  @DisplayName("含表单收集节点（FORM_NODE）的图必须被拒绝发布")
  void aGraphWithAFormNodeMustNotPublish() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"), node("f1", "FORM_NODE"), node("r1", "REPLY_NODE")),
                List.of(edge("s1", "f1"), edge("f1", "r1"))));

    assertThat(result.passed())
        .as("含表单收集节点（FORM_NODE）的图必须拒绝发布 —— 本版本不支持人工介入（09 §5.3.2 ②）")
        .isFalse();
  }

  /** 反证：一张「没有中断点」的干净图必须照常通过 —— 否则上面两条只是「所有图都发不出去」的假绿。 */
  @Test
  @DisplayName("反证 · 不含中断点的干净图必须照常通过（不是一刀切拦死）")
  void aCleanGraphWithoutInterruptsStillPublishes() {
    WorkflowValidationService.Result result =
        service.validate(
            new WorkflowValidationService.Graph(
                List.of(
                    node("s1", "START_NODE"), node("c1", "AI_CHAT_NODE"), node("r1", "REPLY_NODE")),
                List.of(edge("s1", "c1"), edge("c1", "r1"))));

    assertThat(result.passed()).as("反证：不含中断点的图必须通过 —— 否则上面的『必须拒绝』只是把所有图一刀切拦死，不是真的认出了中断点").isTrue();
  }
}
