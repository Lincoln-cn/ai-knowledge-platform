package cn.lincoln.aiplatform.assistant.application;

import java.util.Map;
import java.util.UUID;

/**
 * 执行器读配置的三个小工具（包内可见）。
 *
 * <p>★ 为什么单独一个类而不是每个执行器各写一份：这三段逻辑（取字符串、取 UUID、报"没填"） 表面看是五行代码，但<strong>报错文案的形状</strong>才是要紧的东西 ——
 * "某某节点没有选模型： 在节点配置里给 modelId" 这句话只有一处时，用户看到的十种节点报的是同一种说法。 分散之后，第一个改文案的人只会改到自己那个节点。
 */
final class NodeConfigSupport {

  private NodeConfigSupport() {}

  /** 取一个非空字符串配置；空白与缺失都返回 null（调用方自己决定要不要报错）。 */
  static String text(Map<String, Object> config, String key) {
    Object raw = config == null ? null : config.get(key);
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw).trim();
    return value.isEmpty() ? null : value;
  }

  /**
   * 取一个必填的 UUID 配置。
   *
   * @param nodeName 节点中文名（报错时出现在句首）
   * @param hint 用户该填哪个字段（出现在"在节点配置里给 X"里）
   */
  static UUID requireUuid(Map<String, Object> config, String key, String nodeName, String hint) {
    String value = text(config, key);
    if (value == null) {
      throw new IllegalStateException(nodeName + "没有选模型：在节点配置里给 " + hint);
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(nodeName + "的 " + hint + " 不是合法 UUID：" + value, e);
    }
  }

  /** 取一个非空字符串配置，缺失就报错（文案由调用方给，因为"缺什么"各有各的说法）。 */
  static String requireText(Map<String, Object> config, String key, String missingMessage) {
    String value = text(config, key);
    if (value == null) {
      throw new IllegalStateException(missingMessage);
    }
    return value;
  }

  /** 配置里的路径键附默认值：给了就用，没给就用默认（用于"读哪个流程变量"这类可覆盖的键）。 */
  static String keyOrDefault(Map<String, Object> config, String key, String fallback) {
    String value = text(config, key);
    return value == null ? fallback : value;
  }
}
