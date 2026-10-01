package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.EmbeddingProgressed;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量化进度回写（第 8 周 · 0.3.0）—— <strong>真实进度</strong>的唯一写入口（{@code 03 §3.4}）。
 *
 * <p>★ 三件事必须一起发生，缺一条就会出"看起来有进度、其实是假的"：
 *
 * <ol>
 *   <li><strong>覆盖写</strong>：落的是"已完成多少段的<strong>事实</strong>"，不是 {@code += 1} （累加在事件重投时会变成 200%，见
 *       {@code 03 §3.3}）
 *   <li><strong>落库</strong>：进度必须持久化 —— 前端刷新后还能看到，靠的就是它（这也是"刷新页面不重置"的唯一实现方式）
 *   <li><strong>发布事实事件</strong>：{@link EmbeddingProgressed} 带的是<strong>落库之后</strong>的当前值
 * </ol>
 *
 * <p>★ 这个方法可以被重复调用（同一批重投、用户刷新、断点续跑）：{@code reportEmbeddingProgress} 对回退值是
 * no-op，所以"投几次结果一致"由领域层保证，而不是靠调用方只投一次。
 */
@Service
public class EmbeddingProgressService {

  private final DocumentRepository documents;
  private final ApplicationEventPublisher events;

  public EmbeddingProgressService(DocumentRepository documents, ApplicationEventPublisher events) {
    this.documents = documents;
    this.events = events;
  }

  /**
   * 回写某文档的向量化进度，并发布事实事件。
   *
   * @param documentId 文档 id
   * @param processed 已完成的段数（事实）
   * @throws BusinessException 文档不存在（内部调用路径；对外端点自行做可见性判定）
   * @throws IllegalStateException 文档不在向量化中
   * @throws IllegalArgumentException 进度越界
   */
  @Transactional
  public void report(UUID documentId, int processed) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
    document.reportEmbeddingProgress(processed);
    Document saved = documents.save(document);
    // ★ 发的是"落库之后的事实"：重投旧事件时（回退被 no-op）这里发出去的仍是当前值，
    //   消费者不会看到一次"进度倒退"的假事实。
    events.publishEvent(
        new EmbeddingProgressed(saved.id(), saved.processedSegments(), saved.segmentCount()));
  }
}
