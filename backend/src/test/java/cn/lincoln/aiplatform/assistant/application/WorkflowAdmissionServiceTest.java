package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 执行准入与发布校验要分开：发布允许「带着未实现的节点先发出去」（产品要能预告能力），执行不行 —— 执行器遇到未实现的类型只能中途放弃，那是「跑到一半才说不行」（{@code #113}）。
 */
class WorkflowAdmissionServiceTest {

  private static final NodeTypeCatalog CATALOG = new NodeTypeCatalog(JsonMapper.builder().build());

  private final WorkflowValidationService validation = new WorkflowValidationService(CATALOG);
  private final WorkflowAdmissionService admission =
      new WorkflowAdmissionService(validation, CATALOG);

  private static WorkflowValidationService.Node node(String id, String type) {
    return new WorkflowValidationService.Node(id, type, "节点 " + id);
  }

  private static WorkflowValidationService.Edge edge(String id, String from, String to) {
    return new WorkflowValidationService.Edge(id, from, to);
  }

  private static WorkflowValidationService.Graph chain() {
    return new WorkflowValidationService.Graph(
        List.of(node("s1", "START_NODE"), node("a1", "AI_CHAT_NODE"), node("r1", "REPLY_NODE")),
        List.of(edge("e1", "s1", "a1"), edge("e2", "a1", "r1")));
  }

  @Test
  void aRunnableGraphIsAdmitted() {
    WorkflowValidationService.Result result = admission.admission(chain());

    assertThat(result.issues()).filteredOn(issue -> "ERROR".equals(issue.level())).isEmpty();
    assertThat(result.passed()).as("三个已实现节点连成一条链，现在就该能跑").isTrue();
  }

  @Test
  void aCycleIsRejectedEvenWhenEveryNodeAndTypeIsFine() {
    WorkflowValidationService.Graph loop =
        new WorkflowValidationService.Graph(
            List.of(node("s1", "START_NODE"), node("a1", "AI_CHAT_NODE"), node("r1", "REPLY_NODE")),
            List.of(edge("e1", "s1", "a1"), edge("e2", "a1", "r1"), edge("e3", "r1", "a1")));

    WorkflowValidationService.Result result = admission.admission(loop);

    assertThat(result.passed()).as("有环 ⇒ 执行器会一直转下去").isFalse();
    assertThat(result.issues())
        .filteredOn(issue -> WorkflowAdmissionService.CYCLE.equals(issue.code()))
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.level()).isEqualTo("ERROR");
              assertThat(issue.nodeId()).as("要点名闭合那个环的节点").isEqualTo("a1");
              assertThat(issue.hint()).contains("连线");
            });
  }

  @Test
  void aSelfLoopIsACycleToo() {
    WorkflowValidationService.Graph loop =
        new WorkflowValidationService.Graph(
            List.of(node("s1", "START_NODE"), node("a1", "AI_CHAT_NODE")),
            List.of(edge("e1", "s1", "a1"), edge("e2", "a1", "a1")));

    assertThat(admission.admission(loop).passed()).isFalse();
  }

  @Test
  void aPlannedTypeBlocksBothPublishingAndExecution() {
    // 末尾补一个 REPLY_NODE：不补的话发布这一层会因为「没有输出节点」先红，
    // 那样这条用例就证明不了「区别在类型上」—— 这正是第一版踩的坑。
    WorkflowValidationService.Graph planned =
        new WorkflowValidationService.Graph(
            List.of(
                node("s1", "START_NODE"),
                node("f1", "FUNCTION_LIB_NODE"),
                node("r1", "REPLY_NODE")),
            List.of(edge("e1", "s1", "f1"), edge("e2", "f1", "r1")));

    WorkflowValidationService.Result publishable = validation.validate(planned);
    WorkflowValidationService.Result runnable = admission.admission(planned);

    // 新语义（#113 判据 2）：implemented=false = 本版本不支持 ⇒ PLANNED_TYPE 由 WARNING 升为 ERROR，
    // 发布这一层现在也直接拒绝（收紧前这里断言 isTrue，即"发布只警告、准入才拦"的旧分界）。
    assertThat(publishable.passed())
        .as(
            "发布这一层现在也拒绝规划中的类型（PLANNED_TYPE 升 ERROR）—— 旧分界『发布只警告、准入才拦』已被 #113 收紧"
                + "（收紧前这里断言的是 isTrue）")
        .isFalse();
    assertThat(publishable.issues())
        .as("发布层用 PLANNED_TYPE 点名这个本版本不支持的类型")
        .filteredOn(issue -> "PLANNED_TYPE".equals(issue.code()))
        .hasSize(1);
    assertThat(runnable.passed()).as("执行这一层遇到未实现的类型同样拒绝").isFalse();
    assertThat(runnable.issues())
        .filteredOn(issue -> WorkflowAdmissionService.NOT_EXECUTABLE_TYPE.equals(issue.code()))
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.level()).isEqualTo("ERROR");
              assertThat(issue.nodeId()).isEqualTo("f1");
              assertThat(issue.message()).contains("还没实现");
            });
  }
}
