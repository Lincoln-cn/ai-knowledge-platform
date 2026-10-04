package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析进度的唯一写入口（第 9 周 · 0.3.1）—— 与 {@link EmbeddingProgressService} 同形。
 *
 * <h3>★ 为什么必须独立成一个服务、而不是在解析线程里直接改对象</h3>
 *
 * <p>扫描件的解析要跑**分钟级**（实测每页约 3.6 秒）。如果解析还在上传那个事务里， 逐页进度都写在**未提交**的事务里 ⇒ 别的请求（以及轮询的前端）看不到任何进度， "已识别
 * 3/10 页"永远停在 0/10。所以：
 *
 * <ul>
 *   <li>解析**不在事务里**（{@code ParseRunner} 直接调解析器）
 *   <li>每次回写**各自一个短事务**（本类的方法），提交之后才对外可见
 * </ul>
 *
 * <p>★ 覆盖写 + 回退是 no-op（由领域层 {@code Document#reportOcrPageDone} 保证）： 页面轮询、任务重投、乱序回调都不会把进度讲回去。
 */
@Service
public class ParseProgressService {

  private final DocumentRepository documents;

  public ParseProgressService(DocumentRepository documents) {
    this.documents = documents;
  }

  /**
   * 开始解析：{@code PENDING_PARSE → SEGMENTING}，并把进度置成 {@code 0/M}。
   *
   * @param ocrTotalPages 需要 OCR 的页数（快扫得出的分母）；不涉及 OCR 时传 0
   */
  @Transactional
  public void start(UUID documentId, int ocrTotalPages) {
    Document document = require(documentId);
    document.startParse(ocrTotalPages);
    documents.save(document);
  }

  /**
   * 回写"已完成 OCR 的页数"。
   *
   * @param done 已完成的页数（**事实**，不是增量）
   */
  @Transactional
  public void reportOcrPage(UUID documentId, int done) {
    Document document = require(documentId);
    document.reportOcrPageDone(done);
    documents.save(document);
  }

  private Document require(UUID documentId) {
    return documents
        .findById(documentId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
  }
}
