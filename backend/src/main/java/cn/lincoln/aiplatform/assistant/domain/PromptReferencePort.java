package cn.lincoln.aiplatform.assistant.domain;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 提示词引用是否存在（导入包的依赖校验用）。
 *
 * <p>★ 为什么需要它：提示词配置的 id 属于<strong>导出方环境</strong>，跨环境导入时同一个 id 几乎一定不存在。 只回一个布尔集合，不让导入方拿到提示词正文 ——
 * 导入只关心"这条引用要不要保留"。
 */
public interface PromptReferencePort {

  /**
   * @param promptIds 待确认的提示词 id
   * @return 其中真实存在的 id（保序不保证）；入参为空 ⇒ 空集
   */
  Set<UUID> existing(Collection<UUID> promptIds);
}
