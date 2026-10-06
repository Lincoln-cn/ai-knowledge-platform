package cn.lincoln.aiplatform.platform.retrieval;

import java.util.Locale;

/**
 * 检索模式（契约 {@code RetrievalMode}，取值一字不差）。
 *
 * <p>★ 为什么这个枚举住在 {@code platform} 而不是 {@code retrieval.domain}：
 * <strong>两个模块都要用它，而它们之间没有依赖边</strong> —— {@code retrieval} 是自己的模式， 而 {@code knowledge}
 * 要把「这个库默认用哪种模式」存进 {@code kb_knowledge_base} （矩阵 {@code 01 §三.2} 里 {@code knowledge} 不依赖 {@code
 * retrieval}， 所以不能共用后者的枚举）。这类"契约词汇"放共享内核，与 {@code platform.access.ClassificationLevel} 同一做法 ——
 * 同一个概念只许有一份定义， 否则两边各写一套字符串常量，漂移时症状是"库里存着 'hybrid'、后端只认 'HYBRID'"。
 */
public enum RetrievalMode {
  /** 向量：pgvector 余弦相似度 —— 自然语言提问，语义相近即可命中。 */
  VECTOR,
  /** 全文：{@code chinese_zh} 的 tsvector + GIN —— 专有名词、编号、精确措辞。 */
  FULLTEXT,
  /** 混合：向量与全文用 RRF 融合 —— 通用场景的推荐值（{@code 03 §2.2}）。 */
  HYBRID;

  /**
   * 解析契约字符串。
   *
   * <p>★ 空值回默认（{@link #HYBRID}，与 mock 的默认值一致）；<strong>非法值明确报错</strong> —— 不学 mock
   * 那种"不认识就静默回退"，因为那会让前端拼错的值悄悄变成另一种模式。
   */
  public static RetrievalMode parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return HYBRID;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("检索模式只支持 VECTOR / FULLTEXT / HYBRID，收到：" + raw, e);
    }
  }
}
