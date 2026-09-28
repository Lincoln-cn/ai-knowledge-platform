package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型加载状态（四态）。
 *
 * <p>★ 枚举名用<strong>中文字面量</strong>（{@code 未加载 | 加载中 | 已加载 | 加载失败}），与契约 {@code
 * frontend/src/api/models.ts} 的 {@code ModelStatus} 一字不差 —— 与 {@code UserStatus} 同一条纪律。
 *
 * <p>★ 这四态不是装饰：它们是 {@link Model} 状态机的节点，决定了界面能不能真的走全 （例如「加载失败」之后必须再 {@code startLoad()} 才能重新 {@code
 * markLoaded()}，不允许直接跳）。 非法转移由 {@link Model} 拒绝，而不是靠调用方自觉。
 */
public enum ModelStatus {
  未加载,
  加载中,
  已加载,
  加载失败
}
