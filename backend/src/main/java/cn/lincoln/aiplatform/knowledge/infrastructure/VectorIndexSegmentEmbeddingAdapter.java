package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbedding;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.platform.vector.VectorIndexPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 分段向量的仓储实现（{@code knowledge} 侧）—— ★ <strong>翻译 + 委托，不再自己认识表名</strong>。
 *
 * <h3>为什么把 SQL 搬走了（issue #5）</h3>
 *
 * <p>原来这个适配器里硬编码 {@code vec_embedding_768}，并在维度不符时抛 "向量维度不匹配" —— 那是"明确失败"（方向对），但它同时意味着
 * <strong>换一个不同维度的向量模型就只能报错</strong>：没有路由，也没有按需建表。
 *
 * <p>现在 SQL 与表名都在 {@code vectorindex}（{@link VectorIndexPort} 的实现）， 这边只剩一件事：把领域对象翻成端口的值对象、再翻回来。 ★
 * 这样做的收益不只是 #5 —— "分段"与"向量表"之间从此只有一条接口， 换向量库（独立向量库 / Spring AI 的 {@code PgVectorStore}）改的是 {@code
 * vectorindex}， 这个文件一行都不用动（{@code 01 §3.3} 说的"真正可替换的基础设施"）。
 *
 * <p>★ 为什么它还在 {@code knowledge} 而不是搬进 {@code vectorindex}： 它实现的是 {@code knowledge} 的
 * <strong>领域仓储接口</strong> （{@link SegmentEmbeddingRepository}，参数是 {@code SegmentEmbedding}）， 而
 * {@code vectorindex} 不认识分段 —— 边界就画在这里。
 */
@Component
public class VectorIndexSegmentEmbeddingAdapter implements SegmentEmbeddingRepository {

  private final VectorIndexPort vectorIndex;

  public VectorIndexSegmentEmbeddingAdapter(VectorIndexPort vectorIndex) {
    this.vectorIndex = vectorIndex;
  }

  @Override
  public void upsert(SegmentEmbedding embedding) {
    vectorIndex.upsert(
        new VectorIndexPort.Entry(
            embedding.segmentId(),
            embedding.kbId(),
            embedding.documentId(),
            embedding.modelId(),
            embedding.content(),
            embedding.vector()));
  }

  @Override
  public Optional<SegmentEmbedding> findById(UUID segmentId) {
    return vectorIndex.findById(segmentId).map(VectorIndexSegmentEmbeddingAdapter::toDomain);
  }

  @Override
  public long countByDocumentId(UUID documentId) {
    return vectorIndex.countByDocument(documentId);
  }

  @Override
  public void deleteBySegmentId(UUID segmentId) {
    vectorIndex.deleteById(segmentId);
  }

  @Override
  public void deleteByDocumentId(UUID documentId) {
    vectorIndex.deleteByDocument(documentId);
  }

  private static SegmentEmbedding toDomain(VectorIndexPort.Entry entry) {
    return new SegmentEmbedding(
        entry.segmentId(),
        entry.kbId(),
        entry.documentId(),
        entry.modelId(),
        entry.content(),
        entry.vector());
  }
}
