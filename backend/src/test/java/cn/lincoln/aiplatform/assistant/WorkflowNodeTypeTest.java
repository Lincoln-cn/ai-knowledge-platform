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
 * 节点目录（`#198` 第二块）：18 个节点、10 个已实现、类型名不重复。
 *
 * <p>★ 为什么断言"10 个已实现"：画布只注册了这 10 个（`check_workflow_node_types.py` 守的是同一件事）。 目录里把某个标成
 * `implemented=true` 而画布没注册，用户点它会掉进"找不到节点模型"的空画布 —— 那正是 `#20` 那次事故的形状。这条断言把两边的数字钉在一起。
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
  void catalogCoversAllEighteenTypes() throws Exception {
    assertThat(load()).as("18 个节点：10 已实现 + 8 规划中").hasSize(18);
  }

  @Test
  void exactlyTenAreInCatalog() throws Exception {
    long enabled =
        load().stream().filter(m -> Boolean.TRUE.equals(m.get("catalogEnabled"))).count();
    assertThat(enabled).as("★ 画布可用的必须恰好 10 个 —— 与画布注册的类型数一致（#20 的教训）").isEqualTo(10);
  }

  @Test
  void executableTypesMatchTheExecutor() throws Exception {
    // ★ 这条是 #331 的判据 2：目录里 executable=true 的集合必须**正好等于**执行器的 SUPPORTED_TYPES。
    //   改任一边都会红 —— 这正是"能放"与"能跑"两义性要防的东西。
    Set<String> fromCatalog =
        load().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("executable")))
            .map(m -> String.valueOf(m.get("type")))
            .collect(Collectors.toSet());
    assertThat(fromCatalog)
        .as("目录 executable 与执行器 SUPPORTED_TYPES 必须一致：多一个 ⇒ 发布放行但运行时抛，少一个 ⇒ 能跑却发布被拒")
        .isEqualTo(
            cn.lincoln.aiplatform.assistant.application.LinearWorkflowExecutor.SUPPORTED_TYPES);
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
    Map<String, Long> byType =
        rows.stream()
            .collect(Collectors.groupingBy(r -> r.get("type").toString(), Collectors.counting()));
    assertThat(byType).hasSize(18);
  }
}
