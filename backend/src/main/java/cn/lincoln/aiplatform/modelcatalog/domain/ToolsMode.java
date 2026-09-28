package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 工具调用能力三态（契约 {@code ToolsMode}）。
 *
 * <p>★ 取值与契约一字不差（{@code UNSUPPORTED | SUPPORTED | PARALLEL}）。
 *
 * <p>MVP 只声明不接口（V1.0 才可用工具调用）—— 先把能力位存对，免得到时候再改表。 {@code PARALLEL} 与 {@code SUPPORTED} 的区分是为了
 * 「模型能不能并行发多个工具调用」这件事 不必等到要用了才发现声明粒度不够。
 */
public enum ToolsMode {
  UNSUPPORTED,
  SUPPORTED,
  PARALLEL
}
