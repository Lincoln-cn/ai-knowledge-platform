package cn.lincoln.aiplatform.knowledge.domain;

/**
 * 文档与分段的<strong>归属种类</strong>（{@code 13 §11.3} 的 {@code scope_kind}）。
 *
 * <p>★ <strong>只有两个取值，而且个人库不是第三个</strong>：个人库**就是** {@code kb_knowledge_base} 里 {@code kind =
 * PERSONAL} 的那一行，在 {@code kb_document} / {@code kb_segment} 侧 它与共用库<strong>同形</strong>（{@link
 * #KB}）。把 {@code PERSONAL} 单列进来会造出"同一种文档两种归属写法"的 第二套真相 —— 而那正是本项目最贵的病（{@code 10 §11.1}）。
 *
 * <p>★ 取值与库层的 {@code chk_doc_scope_kind} / {@code chk_segment_scope_kind} 逐字一致 （{@code 14 §2.1}：标识符
 * = 线上值 = 库存值，SCREAMING_SNAKE_CASE）。加第三个取值必须同时改 那两条 CHECK（否则新值会被库层拒绝 —— 那是**好的**失败方式：报错而不是静默）。
 */
public enum DocumentScopeKind {

  /** 共用知识库或个人库下的内容（哪一种是看 {@code kb_knowledge_base.kind}）。 */
  KB,

  /**
   * 会话临时 —— ★ 只有 {@code kb_document} / {@code kb_segment} 行，**没有** {@code kb_knowledge_base} 行
   * （{@code 13 §11.2}：它不该有 ceiling / 向量模型 / 共享面）。
   */
  CONVERSATION
}
