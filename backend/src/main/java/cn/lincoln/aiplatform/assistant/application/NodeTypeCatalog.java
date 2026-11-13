package cn.lincoln.aiplatform.assistant.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 节点目录的唯一读取点（`resources/workflow/node-types.json`）。
 *
 * <p>★ 目录有两个消费者：下发（`GET /workflow/node-types`）与校验（`POST …/workflow/validate`）。
 * 两处各读一遍文件不是错，但"哪些类型算已实现"很容易各算各的 —— 收成一处，免得 界面把某个节点标成可用、校验却认为它不存在。
 *
 * <p>★ <strong>两个布尔是两件事</strong>（`#331`，2026-11-12 从单个 `implemented` 拆开）：
 *
 * <ul>
 *   <li>{@code catalogEnabled} —— 画布上能不能拖这个节点；
 *   <li>{@code executable} —— 发布/运行时会不会被接受（等价于执行器真有它的 NodeExecutor）。
 * </ul>
 *
 * <p>今天有 3 个类型停在中间态（`BASE_NODE` / `QUESTION_NODE` / `NUMBER_CONDITION_NODE`）：能拖进来、点发布才报错。
 * 合并成一个布尔时，用户要摆完节点、连完线、调完参数，点发布才知道白做了。
 */
@Component
public class NodeTypeCatalog {

  private static final String RESOURCE = "workflow/node-types.json";

  private final List<NodeTypeMeta> all;
  private final Set<String> executable;

  public NodeTypeCatalog(ObjectMapper objectMapper) {
    this.all = load(objectMapper);
    this.executable =
        all.stream()
            .filter(NodeTypeMeta::executable)
            .map(NodeTypeMeta::type)
            .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static List<NodeTypeMeta> load(ObjectMapper objectMapper) {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      return objectMapper.readValue(in, new TypeReference<List<NodeTypeMeta>>() {});
    } catch (IOException e) {
      // ★ 目录读不出来就**起不来**，而不是返回空表：空表会让校验把每个节点都判成"未知类型"，
      //   用户看到满屏报错，却与他自己画错了毫无关系。
      throw new IllegalStateException("节点目录读不出来：" + RESOURCE, e);
    }
  }

  public List<NodeTypeMeta> all() {
    return all;
  }

  public boolean known(String type) {
    return all.stream().anyMatch(meta -> meta.type().equals(type));
  }

  /**
   * 这个类型能不能被<strong>发布</strong>（等价于执行器真能跑）。
   *
   * <p>★ 与 {@code catalogEnabled} 不是一回事：后者为真只说明画布上能拖，不代表发布能过。调用方要区分「不能放」与「放了也跑不了」时，两个都要看。
   */
  public boolean executable(String type) {
    return executable.contains(type);
  }

  /**
   * 目录条目（契约 {@code NodeTypeMeta}）。
   *
   * <p>★ {@code configFields} / {@code outputs} 是 `#337` 加的第三、第四格：节点的配置长什么样、
   * 它会往流程状态里写哪些键，从前只写在画布面板与执行器源码里，两边各一份、靠人记。搬进目录之后， 前端面板与变量目录可以从这里生成，后端执行器读的键由 {@link
   * WorkflowNodeKeys} 一处定义， 由 {@code WorkflowNodeKeysTest} 与 {@code WorkflowNodeTypeTest} 钉住一致。
   *
   * <p>★ 两个列表都归一成空表而不是 null：规划中的节点没有配置清单是<strong>正常状态</strong>， 让调用方到处判空会把"这个节点没有配置"与"目录漏填了"混成同一件事。
   */
  public record NodeTypeMeta(
      String type,
      String label,
      String description,
      String category,
      boolean catalogEnabled,
      boolean executable,
      List<ConfigField> configFields,
      List<String> outputs) {

    public NodeTypeMeta {
      configFields = configFields == null ? List.of() : List.copyOf(configFields);
      outputs = outputs == null ? List.of() : List.copyOf(outputs);
    }
  }

  /**
   * 一个配置字段的声明。
   *
   * @param key 契约键名（与前端 {@code NodeConfigOf} 的同名字段、与 {@link WorkflowNodeKeys} 的常量一致）
   * @param label 面板上显示的中文标签
   * @param type 控件与取值类型：{@code string} / {@code string-list} / {@code boolean} / {@code uuid} /
   *     {@code object} / {@code object-list} / {@code branch-list}
   * @param required 面板上必填（执行器另有自己的运行期判断，这里只描述"填不填得完"）
   * @param panelOnly ★ 只给面板用、执行器<strong>不读</strong> —— 让"悬空字段"从靠人记变成可声明的状态 （`#336` 的 {@code kbIds}
   *     与 {@code DOCUMENT_EXTRACT_NODE} 的 {@code kbId} 都是这一类）
   */
  public record ConfigField(
      String key, String label, String type, boolean required, boolean panelOnly) {}
}
