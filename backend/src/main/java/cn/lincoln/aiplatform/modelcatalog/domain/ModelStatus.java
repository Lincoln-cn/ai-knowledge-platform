package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型加载状态（四态）。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code NOT_LOADED / LOADING / LOADED /
 * LOAD_FAILED}）， 标识符 = 线上值 = 库存值三者同名。展示文案（"未加载 / 加载中 / 已加载 / 加载失败"）只出现在前端 label 映射里，
 * <strong>不进标识符、不进线上值、不进库</strong> —— 机器码稳定、可比较、可排序；文案随时可改而不影响契约与数据。
 *
 * <p>★ 这四态不是装饰：它们是 {@link Model} 状态机的节点，决定了界面能不能真的走全 （例如 {@code LOAD_FAILED} 之后必须再 {@code
 * startLoad()} 才能重新 {@code markLoaded()}，不允许直接跳）。 非法转移由 {@link Model} 拒绝，而不是靠调用方自觉。
 */
public enum ModelStatus {
  NOT_LOADED,
  LOADING,
  LOADED,
  LOAD_FAILED
}
