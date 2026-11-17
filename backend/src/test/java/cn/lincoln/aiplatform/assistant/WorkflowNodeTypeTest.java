package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 节点目录（`#198` 第二块）：类型名唯一、目录与主代码两侧的"能跑"清单一致。
 *
 * <p>★ 这里<strong>不写任何数量</strong>（`#337` 第四步）：原来有一条 "恰好 17 个" 与一条 `isEqualTo(17)`，每加一个节点都要回来 改数字 ——
 * 而"数量"本身没有判据价值。误删一条时，`executableTypesMatchTheExecutor`（目录的 {@code executable} 集合 == 主代码里真实存在的执行器）与
 * {@code check_workflow_node_types.py}（注册名集合 == 可执行集合）都会红， 不需要再手写一遍。判据：**加一个节点时这份文件不需要改**。
 */
class WorkflowNodeTypeTest {

  private static List<Map<String, Object>> load() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (InputStream in =
        new org.springframework.core.io.ClassPathResource("workflow/node-types.json")
            .getInputStream()) {
      return mapper.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
    }
  }

  @Test
  void catalogEnabledMatchesExecutable() throws Exception {
    // ★ 原来这条是 `enabled 恰好 17 个`。数字换成"两个集合相等"：
    //   今天没有"能拖进画布但还不能跑"的节点（拖进去也发布不了，等于骗人）；
    //   将来真要允许规划中节点上画布，改这一条是**有意的产品改变**，而不是每次加节点来改数字。
    Set<String> onCanvas =
        load().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("catalogEnabled")))
            .map(m -> String.valueOf(m.get("type")))
            .collect(Collectors.toSet());
    Set<String> runnable =
        load().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("executable")))
            .map(m -> String.valueOf(m.get("type")))
            .collect(Collectors.toSet());
    assertThat(onCanvas)
        .as("画布可拖的与可运行的必须是同一批：多一个 ⇒ 拖得进却发布不了，少一个 ⇒ 能跑却拖不进画布（#20）")
        .isEqualTo(runnable);
  }

  @Test
  void executableTypesMatchTheExecutor() throws Exception {
    // ★ 这条是 #331 的判据 2，`#337` 换了比对对象：原来比对手写的 LinearWorkflowExecutor.SUPPORTED_TYPES
    //   （那是"同一件事的第二份清单"，加节点时要同时改两处），现在比**主代码里真实的执行器**
    //   （扫 src/main/java 里所有 NodeExecutor 的 type()）。
    //   改任一边都会红 —— 这正是"能放"与"能跑"两义性要防的东西。
    Set<String> fromCatalog =
        load().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("executable")))
            .map(m -> String.valueOf(m.get("type")))
            .collect(Collectors.toSet());
    assertThat(fromCatalog)
        .as("目录 executable 与主代码真实执行器必须一致：多一个 ⇒ 发布放行但运行时抛，少一个 ⇒ 能跑却拖不进画布")
        .isEqualTo(
            cn.lincoln.aiplatform.assistant.application.NodeExecutorSources.implementedTypes());
  }

  @Test
  void everyExecutableIsAlsoInCatalog() throws Exception {
    // executable 蕴含 catalogEnabled：能跑却拖不进画布 = 这个类型谁也用不上。
    for (Map<String, Object> row : load()) {
      if (Boolean.TRUE.equals(row.get("executable"))) {
        assertThat(row.get("catalogEnabled"))
            .as("executable=true 但 catalogEnabled=false：" + row.get("type"))
            .isEqualTo(Boolean.TRUE);
      }
    }
  }

  @Test
  void typeNamesAreUniqueAndUpperCase() throws Exception {
    List<Object> types = load().stream().map(m -> m.get("type")).toList();
    assertThat(types).as("类型名不许重复（重复会让查表悄悄选中先出现的那个）").doesNotHaveDuplicates();
    assertThat(types).allSatisfy(t -> assertThat(t.toString()).matches("[A-Z][A-Z0-9_]*_NODE"));
  }

  @Test
  void categoriesStayInsideTheMenuGroups() throws Exception {
    // ★ 分组是菜单的骨架：取一个画布没定义过的分组名，节点会从菜单里"消失"（不在任何分组下，
    //   也不报错）。这里把取值钉成白名单 —— 新增分组要同时改这一处和画布的分组定义。
    Set<String> allowed = Set.of("基础", "生成", "检索", "交互", "逻辑", "扩展", "多模态");
    for (Map<String, Object> row : load()) {
      String category = String.valueOf(row.get("category"));
      assertThat(allowed)
          .as("未知分组「%s」（%s）：加分组要连画布一起改", category, row.get("type"))
          .contains(category);
    }
  }

  @Test
  void everyEntryHasLabelDescriptionAndCategory() throws Exception {
    List<Map<String, Object>> rows = load();
    for (Map<String, Object> row : rows) {
      assertThat(row.get("label")).as("label 不能空：" + row.get("type")).isNotNull();
      assertThat(row.get("description")).as("description 不能空：" + row.get("type")).isNotNull();
      assertThat(row.get("category")).as("category 不能空（节点菜单靠它分组）：" + row.get("type")).isNotNull();
    }
  }
}
