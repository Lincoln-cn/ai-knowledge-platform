package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 节点配置键的<strong>单一真源</strong>守卫（`#337` 第一步）。
 *
 * <h3>★ 这组测试防的是哪一种错</h3>
 *
 * <p>`#335` 那次：前端发 {@code kbIds}、执行器读 {@code dataset_id_list}，两边各自的单测都是绿的 —— 因为
 * 执行器用自己的名字喂自己，前端用自己的形状发出去，中间没有任何一个测试同时看见两边。
 *
 * <p>这里做的是同一件事的静态版本：<strong>把"执行器里写的字面量"与"词表"对照</strong>。它不是端到端测试的替代 （它钉不住"这个键有没有被用"，`#336`
 * 的悬空字段就是反例），它钉的是"这个键叫什么"。
 *
 * <p>★ 扫描只覆盖三处：各 {@code *NodeExecutor}、{@link NodeConfigReader}、以及 {@code node-types.json} 的 {@code
 * configFields} / {@code outputs}。跳过的只有注释行 —— 注释里出现键名是说明文字，不是另一份定义。
 */
class WorkflowNodeKeysTest {

  private static final Path APPLICATION_DIR =
      Path.of("src/main/java/cn/lincoln/aiplatform/assistant/application");
  private static final Path CATALOG_FILE = Path.of("src/main/resources/workflow/node-types.json");

  /** 只匹配"取值"形状：{@code .get("x")} / {@code .getOrDefault("x", …)} / {@code text(config, "x")}。 */
  private static final Pattern KEY_LITERAL =
      Pattern.compile(
          "\\.get\\(\"([^\"]+)\"\\)|\\.getOrDefault\\(\"([^\"]+)\"|texts?\\(config,"
              + " \"([^\"]+)\"\\)");

  @Test
  @DisplayName("执行器与准入读取点里不出现词表之外的键名字面量")
  void noLiteralKeyOutsideTheVocabulary() throws IOException {
    Set<String> allowed = WorkflowNodeKeys.configKeys();
    List<String> offenders = new ArrayList<>();

    for (Path file : sourceFiles()) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (isComment(line)) {
          continue;
        }
        Matcher matcher = KEY_LITERAL.matcher(line);
        while (matcher.find()) {
          String key = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
          if (key != null && !allowed.contains(key)) {
            offenders.add(
                file.getFileName() + ":" + (i + 1) + " 的键「" + key + "」不在 WorkflowNodeKeys 里");
          }
        }
      }
    }

    assertThat(offenders)
        .as("新增配置键必须在 WorkflowNodeKeys 里登记：散落的字面量就是 #335 那种「两边各写一个名字」的来源")
        .isEmpty();
  }

  @Test
  @DisplayName("目录里的 configFields.key 都来自契约词表")
  void catalogFieldsComeFromTheContractVocabulary() throws IOException {
    Set<String> contract = WorkflowNodeKeys.contractKeys();
    List<String> offenders = new ArrayList<>();

    for (CatalogEntry entry : catalog()) {
      for (String key : entry.fieldKeys()) {
        if (!contract.contains(key)) {
          offenders.add(entry.type() + " 的字段「" + key + "」不在契约词表里");
        }
      }
    }

    assertThat(offenders).as("目录与 WorkflowNodeKeys.Config 是同一份词表的两个视角").isEmpty();
  }

  @Test
  @DisplayName("目录里的 outputs 都来自输出词表")
  void catalogOutputsComeFromTheOutputVocabulary() throws IOException {
    Set<String> outputs = WorkflowNodeKeys.outputKeys();
    List<String> offenders = new ArrayList<>();

    for (CatalogEntry entry : catalog()) {
      for (String key : entry.outputs()) {
        if (!outputs.contains(key)) {
          offenders.add(entry.type() + " 的输出「" + key + "」不在输出词表里");
        }
      }
    }

    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("同一个节点内不许重复声明同一个字段")
  void noDuplicateFieldKeyWithinOneNode() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (CatalogEntry entry : catalog()) {
      List<String> keys = entry.fieldKeys();
      if (new LinkedHashSet<>(keys).size() != keys.size()) {
        offenders.add(entry.type() + " 重复声明了字段");
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("能跑的节点必须写出至少一个输出 —— 否则它在流程里等于什么都没发生")
  void executableNodesDeclareAtLeastOneOutput() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (CatalogEntry entry : catalog()) {
      if (entry.executable() && entry.outputs().isEmpty()) {
        offenders.add(entry.type());
      }
    }
    assertThat(offenders)
        .as("executable=true 却不写任何流程变量，下游接不到东西；真要这样，它应该留在 catalogEnabled=false")
        .isEmpty();
  }

  // ---------------------------------------------------------------- 读取

  private static List<Path> sourceFiles() throws IOException {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(APPLICATION_DIR)) {
      files.filter(p -> p.getFileName().toString().endsWith("NodeExecutor.java")).forEach(out::add);
    }
    // 准入读取点也算"执行器一侧"：它读的键与执行器读的键必须同一套。
    out.add(APPLICATION_DIR.resolve("NodeConfigReader.java"));
    return out;
  }

  private static boolean isComment(String line) {
    String trimmed = line.trim();
    return trimmed.startsWith("*")
        || trimmed.startsWith("//")
        || trimmed.startsWith("/*")
        || trimmed.startsWith("@");
  }

  private static String firstNonNull(String... values) {
    for (String value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static List<CatalogEntry> catalog() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> raw =
        mapper.readValue(
            Files.readString(CATALOG_FILE, StandardCharsets.UTF_8), new TypeReference<>() {});
    List<CatalogEntry> out = new ArrayList<>();
    for (Map<String, Object> node : raw) {
      List<String> fieldKeys = new ArrayList<>();
      Object fields = node.get("configFields");
      if (fields instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> map && map.get("key") != null) {
            fieldKeys.add(String.valueOf(map.get("key")));
          }
        }
      }
      List<String> outputs = new ArrayList<>();
      Object outRaw = node.get("outputs");
      if (outRaw instanceof List<?> list) {
        for (Object item : list) {
          if (item != null) {
            outputs.add(String.valueOf(item));
          }
        }
      }
      out.add(
          new CatalogEntry(
              String.valueOf(node.get("type")),
              Boolean.TRUE.equals(node.get("executable")),
              fieldKeys,
              outputs));
    }
    return out;
  }

  private record CatalogEntry(
      String type, boolean executable, List<String> fieldKeys, List<String> outputs) {}
}
