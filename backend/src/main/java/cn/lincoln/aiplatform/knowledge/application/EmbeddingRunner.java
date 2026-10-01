package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBase;
import cn.lincoln.aiplatform.knowledge.domain.KnowledgeBaseRepository;
import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbedding;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingStatus;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 向量化执行器（第 8 周 · 0.3.0）—— {@code 03 §3.2} 流水线的「分批 embed + 写向量表 + 每批回写进度」。
 *
 * <h3>★ 为什么是后台跑（而不是在 HTTP 请求里跑完）</h3>
 *
 * <p>一篇 340 段的文档要打 17 批上游，同步跑意味着请求挂在那里几十秒到几分钟：网关超时、用户以为卡死、 而且<strong>进度根本看不见</strong>（0/340 与
 * 340/340 同时返回）。后台跑 + 每批回写进度，才让 {@code 03 §3.4}「真实进度」这件事成立 —— 前端轮询看到的是库里的数字，刷新页面也不重置。
 *
 * <h3>★ 可续跑：进度从<strong>分段的实际状态</strong>算，不从内存里的计数器算</h3>
 *
 * <p>只处理 {@code embedding_status <> EMBEDDED} 的分段（{@code 03 §3.5} 的分段级重试口径）。
 * 于是"跑到一半进程挂了"的下一次重试会跳过已完成的段 —— 代价从"重跑 497 个已成功的段"降到"只跑那 3 个"。
 *
 * <h3>★ 三个必须一起发生</h3>
 *
 * <ol>
 *   <li>写向量（{@code id = segmentId} ⇒ 重复写是覆盖，幂等）
 *   <li>改分段状态（{@code EMBEDDED}/{@code FAILED}）—— 它是"哪些还要重试"的唯一依据
 *   <li>回写进度并发布事实事件（{@code EmbeddingProgressService}，覆盖写）
 * </ol>
 */
@Service
public class EmbeddingRunner {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingRunner.class);

  /** 批大小。★ 它同时决定"进度条的粒度"：进度是批级回写的（{@code 03 §3.4}）， 批太大 ⇒ 进度长时间不动，看起来像卡死；批太小 ⇒ 上游请求数暴涨。 */
  static final int BATCH_SIZE = 20;

  private final DocumentRepository documents;
  private final SegmentRepository segments;
  private final SegmentEmbeddingRepository embeddings;
  private final KnowledgeBaseRepository knowledgeBases;
  private final EmbeddingPort embeddingPort;
  private final EmbeddingProgressService progress;

  /** 虚拟线程（{@code ADR-01}：IO 密集）。单例执行器随应用生命周期。 */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public EmbeddingRunner(
      DocumentRepository documents,
      SegmentRepository segments,
      SegmentEmbeddingRepository embeddings,
      KnowledgeBaseRepository knowledgeBases,
      EmbeddingPort embeddingPort,
      EmbeddingProgressService progress) {
    this.documents = documents;
    this.segments = segments;
    this.embeddings = embeddings;
    this.knowledgeBases = knowledgeBases;
    this.embeddingPort = embeddingPort;
    this.progress = progress;
  }

  /**
   * 触发向量化（用户显式动作 —— 契约 {@code startEmbedding}：后端不自动推进）。
   *
   * <p>★ <strong>先过状态机守卫、再提交后台任务</strong>：重复点击要立刻拿到可读拒绝
   * （「当前状态是「向量化中」，不允许转到…」），而不是提交一个注定失败的任务、让用户对着没反应的按钮猜。
   *
   * @param documentId 文档 id
   * @throws BusinessException 文档不存在、知识库未绑定向量模型
   * @throws IllegalStateException 当前状态不允许开始向量化（状态机守卫）
   */
  public void startAsync(UUID documentId) {
    Document document = require(documentId);
    UUID modelId = requireEmbeddingModel(document.kbId());

    document.startEmbedding();
    documents.save(document);

    executor.submit(() -> run(documentId, modelId));
  }

  /**
   * 同步跑一轮（{@link #startAsync} 在后台调它；测试与排障也直接调它）。
   *
   * @param documentId 文档 id
   * @param modelId 向量模型 id（知识库上绑定的那个）
   */
  public void run(UUID documentId, UUID modelId) {
    try {
      Document document = require(documentId);
      List<Segment> all = segments.findByDocumentId(documentId);
      if (all.isEmpty()) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "文档没有分段，无法向量化", "请先确认文档已解析并成功分块（查看分段管理页）");
      }

      List<Segment> pending =
          all.stream().filter(s -> s.embeddingStatus() != SegmentEmbeddingStatus.EMBEDDED).toList();
      int done = all.size() - pending.size();

      for (int i = 0; i < pending.size(); i += BATCH_SIZE) {
        List<Segment> batch = pending.subList(i, Math.min(pending.size(), i + BATCH_SIZE));
        try {
          embedBatch(modelId, batch);
        } catch (RuntimeException e) {
          // ★ 失败要把这一批的分段标出来：只重试未完成的分段就靠它（03 §3.5）
          batch.forEach(Segment::markEmbeddingFailed);
          segments.saveAll(batch);
          throw e;
        }
        done += batch.size();
        // ★ 覆盖写 + 发事实事件（判据 ②④）；进度是"已完成的段数"这个事实，不是增量
        progress.report(documentId, done);
      }

      Document fresh = require(documentId);
      fresh.markEmbedded(); // 守卫：processed 必须等于 segmentCount，差一段就抛
      documents.save(fresh);
      log.info("向量化完成：文档 {} 共 {} 段", documentId, all.size());
    } catch (BusinessException e) {
      fail(documentId, e.getMessage(), e.hint());
    } catch (RuntimeException e) {
      log.warn("向量化失败：文档 {} —— {}", documentId, e.getMessage());
      fail(documentId, "向量化失败：" + e.getMessage(), "请确认模型服务可用后重试（重试只会重跑未完成的段）");
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 一批：取向量 → 写向量表（幂等）→ 标分段状态。★ 顺序由端口保证，这里逐条对齐。 */
  private void embedBatch(UUID modelId, List<Segment> batch) {
    List<String> texts = batch.stream().map(Segment::searchText).toList();
    List<float[]> vectors = embeddingPort.embed(modelId, texts);
    if (vectors.size() != batch.size()) {
      throw new IllegalStateException(
          "上游返回的向量条数（" + vectors.size() + "）与本批分段数（" + batch.size() + "）不一致 —— 拒绝写入");
    }
    List<Segment> embedded = new ArrayList<>(batch.size());
    for (int i = 0; i < batch.size(); i++) {
      Segment segment = batch.get(i);
      embeddings.upsert(
          new SegmentEmbedding(
              segment.id(),
              segment.kbId(),
              segment.documentId(),
              modelId,
              segment.content(),
              vectors.get(i)));
      segment.markEmbedded();
      embedded.add(segment);
    }
    segments.saveAll(embedded);
  }

  private void fail(UUID documentId, String reason, String hint) {
    documents
        .findById(documentId)
        .filter(doc -> doc.status() == DocumentStatus.EMBEDDING)
        .ifPresent(
            doc -> {
              // 只有还在「向量化中」才标失败：已成功/已被改段的文档不该被一个迟到的失败覆盖
              doc.markEmbeddingFailed(reason, hint);
              documents.save(doc);
            });
  }

  private Document require(UUID documentId) {
    return documents
        .findById(documentId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
  }

  private UUID requireEmbeddingModel(UUID kbId) {
    KnowledgeBase base =
        knowledgeBases
            .findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
    UUID modelId = base.embeddingModelId();
    if (modelId == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "知识库未绑定向量模型，无法向量化", "请在知识库设置中绑定一个 EMBEDDING 类型的模型");
    }
    return modelId;
  }
}
