package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型种类。
 *
 * <p>★ 取值与契约 {@code frontend/src/api/models.ts} 的 {@code ModelKind} 一字不差（{@code 'chat' |
 * 'embedding'}）—— 库 / 代码 / 契约三处同一个字面量，中间少一层映射就少一处可能对不上的地方。与 {@code UserStatus} 同理。
 *
 * <p>它决定两件不可混的事：对话模型带 {@code contextWindow}、向量模型带 {@code dimension}， 两者互斥且不能互换（向量维度与向量表绑定，改则需全量重灌）。
 */
public enum ModelKind {
  chat,
  embedding
}
