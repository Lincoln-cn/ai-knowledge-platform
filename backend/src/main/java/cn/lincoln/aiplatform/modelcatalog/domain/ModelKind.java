package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型种类。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code CHAT / EMBEDDING}）， 标识符 = 线上值 =
 * 库存值三者同名。展示文案不进标识符、不进线上值、不进库。
 *
 * <p>它决定两件不可混的事：对话模型带 {@code contextWindow}、向量模型带 {@code dimension}， 两者互斥且不能互换（向量维度与向量表绑定，改则需全量重灌）。
 */
public enum ModelKind {
  CHAT,
  EMBEDDING
}
