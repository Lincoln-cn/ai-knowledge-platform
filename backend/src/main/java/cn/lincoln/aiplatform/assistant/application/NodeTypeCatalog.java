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
 */
@Component
public class NodeTypeCatalog {

  private static final String RESOURCE = "workflow/node-types.json";

  private final List<NodeTypeMeta> all;
  private final Set<String> implemented;

  public NodeTypeCatalog(ObjectMapper objectMapper) {
    this.all = load(objectMapper);
    this.implemented =
        all.stream()
            .filter(NodeTypeMeta::implemented)
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

  public boolean implemented(String type) {
    return implemented.contains(type);
  }

  /** 目录条目（契约 `NodeTypeMeta`）。 */
  public record NodeTypeMeta(
      String type, String label, String description, String category, boolean implemented) {}
}
