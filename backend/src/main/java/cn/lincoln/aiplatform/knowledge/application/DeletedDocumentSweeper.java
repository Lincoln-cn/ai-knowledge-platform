package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回收站超期清理（`#259`）：把过了保留期的已删文档<strong>真的</strong>删掉。
 *
 * <h3>它补的是哪一格</h3>
 *
 * <p>`#259` 之后"删除"只是把内容拿出检索面，内容仍在库里占着空间。没有这个作业， 回收站就是一个只进不出的表 —— 而"只进不出"在容量上是账单、在合规上是**该删的没删** （`02
 * §5.1.1` 的留存期口径要求业务数据到期清理）。
 *
 * <h3>★ 三条约束</h3>
 *
 * <ol>
 *   <li><strong>默认关闭</strong>：删数据的作业不许"装好就生效"。开启要有意识（配置项 + 一次确认）；
 *   <li><strong>一次一批</strong>：{@code app.knowledge.cleanup.batch-size} 限制单次处理条数，
 *       避免一次把巨量行搬进内存（清理任务出问题时往往已经跑了一夜）；
 *   <li><strong>失败不重试、但要响</strong>：{@code RuntimeException} ⇒ error 日志 + 返回已删条数。
 *       静默重试会把"删不掉的文档"变成一条永远失败的日志，而没人会去看它。
 * </ol>
 *
 * <p>★ 与 {@code AuditRetentionSweeper} 的分工：那个删<em>审计记录</em>，这个删<em>业务内容</em>。 两者都写 {@code
 * audit.purge} / {@code document.purge}，但一个是"删掉谁做过什么之前"、 另一个是"删掉材料本身" —— 混成一条作业会让审计的留存期与业务的留存期互相拖住。
 */
@Component
public class DeletedDocumentSweeper {

  private static final Logger log = LoggerFactory.getLogger(DeletedDocumentSweeper.class);

  private final boolean enabled;
  private final int retainDays;
  private final int batchSize;
  private final DocumentRepository documents;
  private final SegmentRepository segments;
  private final SegmentEmbeddingRepository segmentEmbeddings;
  private final AuditLogPort audit;
  private final Clock clock;

  public DeletedDocumentSweeper(
      @Value("${app.knowledge.cleanup.enabled:false}") boolean enabled,
      @Value("${app.knowledge.cleanup.retain-days:7}") int retainDays,
      @Value("${app.knowledge.cleanup.batch-size:50}") int batchSize,
      DocumentRepository documents,
      SegmentRepository segments,
      SegmentEmbeddingRepository segmentEmbeddings,
      AuditLogPort audit,
      Clock clock) {
    this.enabled = enabled;
    this.retainDays = Math.max(1, retainDays);
    this.batchSize = Math.max(1, batchSize);
    this.documents = documents;
    this.segments = segments;
    this.segmentEmbeddings = segmentEmbeddings;
    this.audit = audit;
    this.clock = clock;
  }

  /** 截止时间：删除时间早于它的文档该被清理。关闭时返回 {@code null}（调用方据此跳过）。 */
  public Instant cutoff() {
    return enabled ? clock.instant().minus(Duration.ofDays(retainDays)) : null;
  }

  /**
   * 跑一轮。
   *
   * @return 真正删掉的文档数（关闭时 0 —— 与"跑过但一条都没到期"的 0 是同一个数，靠日志区分）
   */
  @Transactional
  public int sweep() {
    Instant cutoff = cutoff();
    if (cutoff == null) {
      log.debug("回收站清理未启用（app.knowledge.cleanup.enabled=false）");
      return 0;
    }
    List<Document> expired;
    try {
      expired = documents.findDeletedBefore(cutoff, batchSize);
    } catch (RuntimeException failure) {
      log.error("回收站清理：查询超期文档失败（这次一条都没删）—— {}", failure.getMessage());
      return 0;
    }
    int removed = 0;
    for (Document document : expired) {
      try {
        // ★ 顺序与手动彻底删除一致：先分段与向量，再文档行。反过来会留孤儿分段，
        //   而孤儿分段在检索里**照样命中**（那正是 #75 判据 ④ 当初的教训）。
        segments.deleteByDocumentId(document.id());
        segmentEmbeddings.deleteByDocumentId(document.id());
        documents.delete(document);
        audit.record(
            AuditAction.DOCUMENT_PURGE,
            "DOCUMENT",
            document.id().toString(),
            null,
            Map.of(
                "name", document.name(),
                "kbId", document.kbId().toString(),
                "trigger", "retention"));
        removed++;
      } catch (RuntimeException failure) {
        // 单篇失败不拖垮整批：其余文档继续清（一篇坏数据不该让整轮清理停摆）
        log.error("回收站清理：文档 {} 清理失败，其余继续 —— {}", document.id(), failure.getMessage());
      }
    }
    if (removed > 0) {
      log.info("回收站清理：删掉 {} 篇（截止 {}，保留 {} 天）", removed, cutoff, retainDays);
    }
    return removed;
  }
}
