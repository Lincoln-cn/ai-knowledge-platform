package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量化进度回写（第 8 周 · 0.3.0）—— <strong>真实进度</strong>的唯一写入口（{@code 03 §3.4}）。
 *
 * <p>★ 两件事必须一起发生，缺一条就会出"看起来有进度、其实是假的"：
 *
 * <ol>
 *   <li><strong>覆盖写</strong>：落的是"已完成多少段的<strong>事实</strong>"，不是 {@code += 1} （累加在事件重投时会变成 200%，见
 *       {@code 03 §3.3}）
 *   <li><strong>落库</strong>：进度必须持久化 —— 前端刷新后还能看到，靠的就是它（这也是"刷新页面不重置"的唯一实现方式）
 * </ol>
 *
 * <p>★★ 2026-10-29（{@code #12} 裁定 C+）：这里原先还有第三件事 —— 发布 {@code EmbeddingProgressed}
 * 事实事件。实测它<strong>一个消费者都没有</strong>（全仓 搜 {@code EmbeddingProgressed}，只有定义、本类和 {@code
 * ingestion/package-info} 里的一句文档；{@code @ApplicationModuleListener} /
 * {@code @TransactionalEventListener} 也没有任何一处监听它）⇒ 那条发布只是让"事件在走"这个假象留了下来。 <strong>已删</strong>。★
 * 领域类型 {@code EmbeddingProgressed} 保留（它是"进度这一事实"的形状），将来真有消费者时<strong>从
 * 事件总线之外的那条路接</strong>，别再默认"发了就有人听"。
 *
 * <p>★ 这个方法可以被重复调用（同一批重投、用户刷新、断点续跑）：{@code reportEmbeddingProgress} 对回退值是
 * no-op，所以"投几次结果一致"由领域层保证，而不是靠调用方只投一次。
 */
@Service
public class EmbeddingProgressService {

  private final DocumentRepository documents;

  public EmbeddingProgressService(DocumentRepository documents) {
    this.documents = documents;
  }

  /**
   * 回写某文档的向量化进度。
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
    documents.save(document);
  }
}
