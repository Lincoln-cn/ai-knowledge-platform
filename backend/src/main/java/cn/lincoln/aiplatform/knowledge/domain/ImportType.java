package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 导入类型（契约 {@code ImportType}，BR-03-01）。
 *
 * <p>★ 创建时选定，之后不可更改 —— 本地上传与在线抓取的加工链不同，中途换轨会留下半截状态。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code 14} 册 §2.1）： 标识符 = 线上值 = 库存值三者同名。
 */
public enum ImportType {
  LOCAL,
  ONLINE
}
