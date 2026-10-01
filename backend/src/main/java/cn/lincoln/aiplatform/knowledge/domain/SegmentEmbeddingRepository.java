package cn.lincoln.aiplatform.knowledge.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 分段向量仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ <strong>幂等 upsert 是本接口的核心语义</strong>（{@code 03 §3.3}）：同一个 {@code segmentId}
 * 重复写必须<strong>覆盖</strong>，不是追加、也不是报错。理由：模块间是"至少一次投递"， 重投的事件与用户重复点击都会走到这里；靠"调用方保证只写一次"是保证不住的。
 */
public interface SegmentEmbeddingRepository {

  /**
   * 写入或覆盖一个分段的向量（幂等）。
   *
   * @param embedding 向量行；其 {@code vector} 长度必须与向量表维度一致
   * @throws IllegalArgumentException 维度与向量表不一致（禁止静默截断/补零）
   */
  void upsert(SegmentEmbedding embedding);

  /** 按分段 id 取（测试与"这份向量是谁生成的"排查用）。 */
  Optional<SegmentEmbedding> findById(UUID segmentId);

  /** 某文档已写入的向量条数（用于对账"段落数 vs 向量数"）。 */
  long countByDocumentId(UUID documentId);

  /**
   * 删除一个分段的向量。
   *
   * <p>★ 删分段时必须连带删向量，否则<strong>被删掉的分段仍然能被检索到</strong> —— 用户删了内容、界面也不显示了，检索却还答得出来，这是最难查的一种"看起来是对的"。
   */
  void deleteBySegmentId(UUID segmentId);

  /**
   * 删除某文档的全部向量（重新分块前清旧的）。
   *
   * <p>★ 与 {@code SegmentRepository#deleteByDocumentId} 配对使用：分段先删后写是幂等的（{@code 03 §3.3}），
   * 向量也必须跟着走同一套节奏，否则段落数与向量数会对不上。
   */
  void deleteByDocumentId(UUID documentId);
}
