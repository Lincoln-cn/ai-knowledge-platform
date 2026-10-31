package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.ChunkSource;
import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.OrgAssignment;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.knowledge.domain.PlannedSegment;
import cn.lincoln.aiplatform.knowledge.domain.SearchText;
import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentContent;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentPlan;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.knowledge.domain.Segmenter;
import cn.lincoln.aiplatform.knowledge.infrastructure.SegmentationProperties;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.AuditAction;
import cn.lincoln.aiplatform.platform.domain.AuditLogPort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.infrastructure.metrics.BusinessMetrics;
import cn.lincoln.aiplatform.platform.org.OrgDirectoryPort;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档建档（第 5 周 · 0.2.0 批 2）—— 元数据必填 + ceiling 校验 + 入库即分块（第 7 周 · 0.2.2 批 2b）。
 *
 * <h3>★ 建档必填三项（{@code 12 §5.1} / {@code DOC-I6}）</h3>
 *
 * <p>文种 / 责任部门 / 密级分别决定「召回前缀」与「安全过滤」，<strong>不能有默认值</strong>。 缺任一 ⇒ 400 且说清缺的是哪一项 ——
 * 笼统的「参数不合法」会让调用方停在原地。
 *
 * <h3>★ ceiling 校验（{@code 13 §6}）</h3>
 *
 * <p>文档密级<strong>高于</strong>库的 {@code maxClassification} ⇒ 拒绝 + 可读原因（含两个密级值）。 等于/低于 ⇒ 放行。
 *
 * <h3>★ 入库即分块（第 7 周 · 0.2.2 批 2b）</h3>
 *
 * <p>{@code recordParseSuccess} 之后立刻调 {@link Segmenter#plan} 分块并落库。 参数口径：
 *
 * <ul>
 *   <li>{@code segmentLength} 默认 500、{@code overlap} 默认 60 —— 与前端向导 {@code CreateWizard.vue} 的
 *       {@code segForm} 默认值一致，否则预览与入库结果不同
 *   <li>{@code maxChars}：① KB 绑定的向量模型有 {@code contextWindow} 则用它（近似）；② 否则回落配置 {@code
 *       app.segmentation.max-chars}，默认 1200 —— 与前端 mock 的 {@code MODEL_LIMIT} 一致
 * </ul>
 *
 * <p>成功 ⇒ 落 segments + 回填 {@code segmentCount}/{@code charCount} + 状态 {@code PENDING_EMBEDDING} （不是
 * {@code SUCCEEDED} —— 向量化还没做）。 失败 ⇒ {@code SEGMENT_FAILED} + 可读原因（不许堆栈、不许静默）。
 *
 * <p>★ 重入幂等：同一文档重复分块不产生重复行（先删后写）。
 */
@Service
public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  private static final int MAX_PAGE_SIZE = 100;

  private final DocumentRepository documents;
  private final KnowledgeBaseService knowledgeBases;
  private final cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser parser;
  private final SegmentRepository segments;
  private final SegmentEmbeddingRepository segmentEmbeddings;
  private final MaxCharsResolver maxCharsResolver;
  private final SegmentationProperties segmentationProperties;
  private final EmbeddingRunner runner;

  /** 责任部门的存在性校验 + 前缀里那段材料的取数（{@code 07 §11.2}）。 */
  private final OrgDirectoryPort orgDirectory;

  /** {@code org_id → code}（前缀/哈希的第二个参数），含存量数据的占位口径。 */
  private final OrgCodeResolver orgCodes;

  /** 把"去解析这份字节"交给 {@link ParseRunner} —— 见 {@link DocumentUploaded}（不用直接依赖，避免构造环）。 */
  private final ApplicationEventPublisher events;

  /**
   * 指标旁路（{@code #73}）：入库失败要能被持续看到。
   *
   * <p>★ 它记在<b>状态真的被写成 FAILED 的那一行旁边</b>，不是记在"有人调了某方法"上 —— 后者会把"重试成功"也算成失败。
   */
  private final BusinessMetrics metrics;

  private final Segmenter segmenter = new Segmenter();

  /**
   * 会话密级水位的补偿口（{@code #247} 判据 ①）：文档<b>升密</b>时把引用过它的会话抬到新密级。
   *
   * <p>★ 走平台端口而不是直接依赖 {@code conversation.domain}：模块边界由 {@code ModuleBoundaryTest} 守着（见端口 javadoc）。
   */
  private final cn.lincoln.aiplatform.platform.watermark.ConversationWatermarkPort watermark;

  /**
   * 审计留痕（{@code #257}）：删除与改密级都要写一条。
   *
   * <p>★ 与业务同事务（端口 javadoc 里写了理由）：审计写不进去时整笔回滚，避免"变更发生了却没有记录"。
   */
  private final AuditLogPort audit;

  public DocumentService(
      DocumentRepository documents,
      KnowledgeBaseService knowledgeBases,
      cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser parser,
      SegmentRepository segments,
      SegmentEmbeddingRepository segmentEmbeddings,
      MaxCharsResolver maxCharsResolver,
      SegmentationProperties segmentationProperties,
      EmbeddingRunner runner,
      OrgDirectoryPort orgDirectory,
      OrgCodeResolver orgCodes,
      ApplicationEventPublisher events,
      BusinessMetrics metrics,
      cn.lincoln.aiplatform.platform.watermark.ConversationWatermarkPort watermark,
      AuditLogPort audit) {
    this.documents = documents;
    this.knowledgeBases = knowledgeBases;
    this.parser = parser;
    this.segments = segments;
    this.segmentEmbeddings = segmentEmbeddings;
    this.maxCharsResolver = maxCharsResolver;
    this.segmentationProperties = segmentationProperties;
    this.runner = runner;
    this.orgDirectory = orgDirectory;
    this.orgCodes = orgCodes;
    this.events = events;
    this.metrics = metrics;
    this.watermark = watermark;
    this.audit = audit;
  }

  // ────────────────────────── 查询 ──────────────────────────

  /**
   * 按库分页列文档。★ 库不可见 ⇒ <strong>404</strong>（继承库的可见性，与批 1 同一判定）。
   *
   * <p>★ {@code total} 是本库过滤之后的数，与页内条数同口径。
   *
   * <p>★★ {@code orgAssignment}（{@code #56} 的出口）：传 {@link OrgAssignment#UNASSIGNED} 就把
   * "回填责任部门时按名字匹配不上"的文档列出来 —— 它们 {@code org_id} 为空、原值留在 {@code
   * department_legacy}（不可更新的存档列）。**列出来才处理得了**：在那之前界面只显示 "责任部门那一列空着"，看起来像"还没填"，而不像"系统需要你处理"。
   *
   * @param kbId 知识库 id
   * @param page 页码（1 起）
   * @param size 页大小
   * @param orgAssignment 归属过滤；{@code null} 视为 {@link OrgAssignment#ANY}（不过滤 = 加这一档之前的行为）
   */
  @Transactional(readOnly = true)
  public PageResult<DocumentView> list(
      UUID kbId, Integer page, Integer size, OrgAssignment orgAssignment) {
    // ★ 先做库的可见性判定：库不可见 ⇒ 其文档也不可见（读 404）
    knowledgeBases.get(kbId);
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    OrgAssignment filter = orgAssignment == null ? OrgAssignment.ANY : orgAssignment;
    // ★ 列表与 total 传**同一个** filter：两处各拼一遍必然漂移（08 §4.4.3 纪律 1）
    List<Document> found = documents.findByKbId(kbId, (safePage - 1) * safeSize, safeSize, filter);
    long total = documents.countByKbId(kbId, filter);
    return PageResult.of(
        found.stream().map(DocumentView::from).toList(), total, safePage, safeSize);
  }

  /**
   * 回收站：本库<strong>已软删</strong>的文档（删除时间倒序，`#75` 判据 ④）。
   *
   * <p>★ 它存在的唯一理由是让「彻底删除」有地方可点：在那之前那个端点只能由运维用 curl 调， 而"不可恢复的操作只有一个看不见的入口"本身就是风险。
   *
   * <p>★ 分页上限与在用列表共用 `MAX_PAGE_SIZE`，`total` 与页内条数同口径（同一份谓词）。
   */
  @Transactional(readOnly = true)
  public PageResult<DocumentView> listDeleted(UUID kbId, Integer page, Integer size) {
    knowledgeBases.get(kbId);
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    List<Document> found = documents.findDeletedByKbId(kbId, (safePage - 1) * safeSize, safeSize);
    long total = documents.countDeletedByKbId(kbId);
    return PageResult.of(
        found.stream().map(DocumentView::from).toList(), total, safePage, safeSize);
  }

  // ────────────────────────── 库级进度（第 8 周 · 0.3.0） ──────────────────────────

  /**
   * ★★ 指定/变更某篇文档的<strong>责任部门</strong>（契约 {@code assignDocumentOrg}；{@code #150}）。
   *
   * <h3>它补的是哪一格</h3>
   *
   * <p>{@code #56} 让"回填时按名字匹配不上"的文档<strong>能被列出来</strong>（ {@code
   * ?orgAssignment=UNASSIGNED}），但建档之后**没有**修改责任部门的入口 ⇒ 列得出来却处理不掉。 这一条就是那个出口。
   *
   * <h3>★ 校验顺序（每一步各自的失败形态都不同，不许合并）</h3>
   *
   * <ol>
   *   <li><strong>库可见</strong> ⇒ 否则 <strong>404</strong>（继承库的可见性，与列表同一条）；
   *   <li><strong>文档存在、属于这个库、未被软删</strong> ⇒ 否则 <strong>404</strong>。 ★ 三种情况共用一个出口：告诉调用方"这个 id
   *       存在但不在你的库里"等于送他一次探测 （{@code 07 §3.3} 纪律 2）；
   *   <li><strong>部门存在且启用</strong> ⇒ 否则 <strong>400</strong> 带可读原因。 ★ 不校验就会让 {@code org_id}
   *       指向一个不存在的 uuid ⇒ 外键报错变成一次 500（"我崩了"）， 而真正的原因却是"你传错了"（与建档路径 {@code #37} 同一条）。
   * </ol>
   *
   * <p>★★ <strong>刻意不动的东西</strong>：{@code department_legacy}（存档列，历史证据）· 密级 · 文种 · 分段 · 向量 ——
   * 这个方法只回答"这份文档归哪个部门"。
   *
   * @param kbId 知识库 id
   * @param documentId 文档 id
   * @param orgId 新的责任部门
   * @return 更新后的文档视图
   * @throws BusinessException 库/文档不可见（404）· 部门不存在或已停用（400）
   */
  @Transactional
  public DocumentView assignOrg(UUID kbId, UUID documentId, UUID orgId) {
    // ★ 1 · 库的可见性（写也 404）
    knowledgeBases.get(kbId);

    // ★ 2 · 文档必须属于这个库、且没被软删
    if (documentId == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文档 id 必填", "指定要改归属的那篇文档");
    }
    Document document =
        documents
            .findById(documentId)
            .filter(found -> found.deletedAt() == null && kbId.equals(found.kbId()))
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在", "它可能已被删除，或者不在这个知识库里"));

    // ★ 3 · 部门存在且启用
    if (orgId == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "责任部门必填",
          "请从组织树选责任部门 —— 它决定召回前缀里那段稳定标识（org code），不能有默认值（07 §11.2）");
    }
    OrgDirectoryPort.OrgBrief org =
        orgDirectory
            .findById(orgId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "责任部门不存在：" + orgId,
                        "请从组织树重新选择 —— 它可能已被删除，或你传的是一个不属于任何部门的 id"));
    if (!org.enabled()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "责任部门已停用：" + org.name(),
          "选一个启用中的部门 —— 把文档归属到一个已停用的部门，它不会出现在任何人的组织视图里");
    }

    // ★ 4 · 只改归属（领域对象上那一个方法；departmentLegacy 等一概不动）
    document.assignOrg(orgId);
    return DocumentView.from(documents.save(document));
  }

  /**
   * 库级入库进度（契约 {@code GET /knowledge-bases/:kbId/ingestion-progress}）。
   *
   * <p>★ 聚合在库里做（{@code DocumentRepository#countByStatus}），不是把文档取回来在内存里数 ——
   * 后者会在分页上限处静默算错，而进度条上那个数字没人会怀疑。
   *
   * <p>★ {@code running} 只算<strong>自己会往前走</strong>的状态：{@code PENDING_EMBEDDING} 不算 ——
   * 它在等用户点「开始向量化」，算进去前端会一直轮询一个不会变化的数字（{@code 03 §3.4}： 仅在有进行中任务时轮询，全部完成即停）。
   *
   * @param kbId 知识库 id
   * @throws BusinessException 库不可见/不存在（404，不泄漏存在性）
   */
  /**
   * 上传策略（契约 {@code UploadPolicy}）—— 先做库的可见性判定，再给平台级上限。
   *
   * <p>★ 值与 {@code spring.servlet.multipart.max-file-size=100MB} 对齐；库级更小配额还没有数据模型，等配额落地再加字段。
   */
  @Transactional(readOnly = true)
  public UploadPolicyView uploadPolicy(UUID kbId) {
    knowledgeBases.get(kbId);
    return new UploadPolicyView(100, 20, List.of(".pdf", ".docx", ".doc", ".md", ".txt"));
  }

  @Transactional(readOnly = true)
  public IngestionProgressView progress(UUID kbId) {
    knowledgeBases.get(kbId);
    Map<DocumentStatus, Long> counts = documents.countByStatus(kbId);

    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long done = counts.getOrDefault(DocumentStatus.SUCCEEDED, 0L);
    long failed =
        counts.getOrDefault(DocumentStatus.SEGMENT_FAILED, 0L)
            + counts.getOrDefault(DocumentStatus.EMBEDDING_FAILED, 0L);
    List<IngestionProgressView.Stage> stages =
        Arrays.stream(DocumentStatus.values())
            .filter(status -> counts.getOrDefault(status, 0L) > 0)
            .map(
                status ->
                    new IngestionProgressView.Stage(status.name(), counts.get(status).intValue()))
            .toList();
    boolean running =
        counts.getOrDefault(DocumentStatus.PENDING_PARSE, 0L) > 0
            || counts.getOrDefault(DocumentStatus.SEGMENTING, 0L) > 0
            || counts.getOrDefault(DocumentStatus.EMBEDDING, 0L) > 0;

    return new IngestionProgressView(
        kbId.toString(), (int) total, (int) done, (int) failed, stages, running);
  }

  // ────────────────────────── 向量化（第 8 周 · 0.3.0） ──────────────────────────

  /**
   * 开始向量化（契约 {@code startEmbedding}：<strong>用户显式动作</strong>，后端不自动推进）。
   *
   * <p>★ 越权/不可见 ⇒ 404（与 {@link #list} 同一判定）。
   *
   * <p>★ 立刻返回：真正跑向量化的是 {@link EmbeddingRunner} 的后台任务，每批回写进度 —— 前端靠轮询看进度，靠的就是"进度在库里"（判据「刷新页面不重置」）。
   *
   * @param documentId 文档 id
   * @throws BusinessException 文档不可见/不存在、知识库未绑定向量模型
   * @throws IllegalStateException 当前状态不允许开始向量化（状态机守卫，重复点击会拿到可读拒绝）
   */
  public DocumentView startEmbedding(UUID documentId) {
    Document document = requireVisibleDocument(documentId);
    // ★ #229：同一个用户意图（「把这份文档向量化」）在两个端点上不该有两种结果。
    //   向量化失败的文档，用户会先点「开始向量化」，而这条路以前稳定 400（只接 PENDING_EMBEDDING 来源），
    //   真正的入口是另一个端点 /retry —— 400 文案里也不提。
    //   ⇒ 在这里委派给 retry：状态退回「待向量化」再跑，用户点哪个按钮都对。
    if (document.status() == DocumentStatus.EMBEDDING_FAILED) {
      return retry(documentId);
    }
    runner.startAsync(documentId);
    // 回读一次：状态已由 startAsync 推进到「向量化中」，返回给前端的必须是推进后的状态
    return DocumentView.from(documents.findById(document.id()).orElse(document));
  }

  /**
   * 重试（契约 {@code retryDocument}）—— 按<strong>当前失败阶段</strong>决定起点（{@code 03 §3.5}）。
   *
   * <p>★ 分段失败的重试需要<strong>原始文件</strong>，而本版不保留上传的字节 ⇒ 如实拒绝并说清下一步，
   * 而不是假装重试了一次（那会让用户反复点、状态一直不变，然后去翻日志）。
   */
  public DocumentView retry(UUID documentId) {
    Document document = requireVisibleDocument(documentId);
    switch (document.status()) {
      case EMBEDDING_FAILED -> {
        document.retryEmbedding(); // → 待向量化：进度归零、清失败原因
        documents.save(document);
        runner.startAsync(documentId);
      }
      case SEGMENT_FAILED ->
          throw new BusinessException(
              ErrorCode.BAD_REQUEST, "分段失败的重试需要原始文件，本版不保留上传的文件", "请重新上传该文档；已解析出的部分正文可在「解析结果」里查看");
      default ->
          throw new BusinessException(
              ErrorCode.BAD_REQUEST, "当前状态无需重试：" + document.status(), "只有分段失败与向量化失败的文档需要重试");
    }
    return DocumentView.from(documents.findById(documentId).orElse(document));
  }

  /**
   * 重新向量化（契约 {@code reembedDocument}）—— 例如换了向量模型之后。
   *
   * <p>★ 必须把<strong>分段状态也退回「待向量化」</strong>：向量化器只处理未完成的分段 （{@code 03 §3.5}
   * 的续跑口径），不重置的话"重新向量化"会一个分段都不做， 而文档状态却回到了「向量化中」—— 看起来像成功了。
   */
  public DocumentView reembed(UUID documentId) {
    Document document = requireVisibleDocument(documentId);
    List<Segment> all = segments.findByDocumentId(documentId);
    if (all.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "文档没有分段，无法重新向量化", "请先确认文档已解析并成功分块");
    }
    all.forEach(Segment::markPendingEmbedding);
    segments.saveAll(all);
    document.markVectorsStale();
    documents.save(document);
    runner.startAsync(documentId);
    return DocumentView.from(documents.findById(documentId).orElse(document));
  }

  // ────────────────────────── 文档管理：改名 / 启停 / 删除 ──────────────────────────

  /**
   * 改名（契约 {@code renameDocument}：{@code PATCH /documents/{id}}）。
   *
   * <p>★ 光改文档行是不够的：结构化前缀里就有文档名（{@code 12 §4.2}），而它已经写进了每个分段的 {@code search_text}/{@code
   * content_hash}。只改聚合根会留下「文档叫 A、它所有分段的检索文本里写着 B」——按新名字召回不到， 而两处各自看起来都没问题。{@code SEG-I7} 的 hash
   * 正是为这种「前缀变了」准备的。 ⇒ 这里重写全部分段的前缀，并把向量标成过期（改名后要重新向量化才一致）。
   */
  @Transactional
  public DocumentView rename(UUID documentId, String name, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    requireNotEmbedding(document, "改名");
    try {
      document.rename(name);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
    }
    document.setUpdatedBy(currentUserId);
    documents.save(document);
    reprefixSegments(document, currentUserId);
    return DocumentView.from(documents.findById(documentId).orElse(document));
  }

  /**
   * ★★ 变更文档密级（{@code #247}）—— 密级的<strong>唯一写入入口</strong>（除建档时的上传表单）。
   *
   * <h3>★ 为什么这件事必须在一个事务里</h3>
   *
   * <p>升密会牵动两处：文档行本身，以及<strong>引用过它的会话</strong>的 {@code max_classification}。后者是 {@code 13 §2.2}
   * 的不变量（"会话密级 = 其引用集合的最大密级"）。分开写会出现一个任何人都看不见的中间态：文档已经是 {@code SECRET}，而会话仍按 {@code INTERNAL} 筛 ——
   * 于是继续提问时那篇文档"看得见名字、检索不到正文"，像是索引出了问题。
   *
   * <h3>★ 降密不补偿，且这是刻意的</h3>
   *
   * <p>会话水位单调（只升不降，{@code #108}）。文档降密后水位停在旧的高档位：这是<strong>安全的严格方向</strong>（能看到的只会更少），
   * 也如实记录了"这个会话的回答曾经引用过什么"。所以这里只对升密调补偿 —— 并且用一条测试钉住"降密后水位不变"，
   * 免得将来有人把它当成"忘了补"而顺手补上（那会把水位往下拉，是真正的越权）。
   *
   * <h3>校验顺序（每条都有 400 的可读理由）</h3>
   *
   * <ol>
   *   <li>文档可见（否则 404）—— 与所有写端点同一判定。
   *   <li>密级必填。
   *   <li>不高于所属库的 {@code maxClassification}（ceiling，{@code 13 §6}）—— 与建档同一口径，不能开一个绕过库上限的后门。
   *   <li>正在向量化 ⇒ 拒绝：那期间分段的密级继承与向量都在动，此刻换密级会写出一半新一半旧的分段。
   * </ol>
   */
  @Transactional
  public DocumentView changeClassification(
      UUID documentId, ClassificationLevel level, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    if (level == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "密级必填", "它决定召回前缀与安全过滤，不能有默认值；请从「公开 / 内部 / 秘密 / 机密」里选一个（13 §2）");
    }
    KnowledgeBaseView base = knowledgeBases.get(document.kbId());
    if (!level.within(base.maxClassification())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "文档密级（%s）高于知识库的密级上限（%s）—— 请提高库的上限，或改用更合适的库"
              .formatted(label(level), label(base.maxClassification())),
          "换一个不高于库上限的密级，或者先调高这个知识库的密级上限 —— 库上限是这个库允许装的最高密级（13 §6）");
    }
    requireNotEmbedding(document, "改密级");

    ClassificationLevel previous = document.classification();
    document.changeClassification(level, currentUserId);
    documents.save(document);

    // ★ ① 分段必须跟着重打（13 §2.4 第 1 步）：分段的 classification 是**独立列**（V22 冗余密级，
    //   不是 generated 列），只改文档行会让旧分段仍停在低密 ⇒ 低 clearance 用户照样检索得到它，
    //   而界面上那篇文档已经显示成"秘密"了 —— 升密看起来生效、实际没生效，正是本项目最怕的形状。
    //   ★ 不需要重新向量化：密级是元数据，不进 embedding（13 §2.4 末段）。
    List<Segment> affected = segments.findByDocumentId(documentId);
    for (Segment segment : affected) {
      segment.reclassify(level);
    }
    if (!affected.isEmpty()) {
      segments.saveAll(affected);
    }

    // ★ ② 只有升密才补偿会话水位。降密/不变都不调 —— 理由见方法 javadoc 第 2 节。
    if (level.ordinal() > previous.ordinal()) {
      int raised = watermark.raiseForDocument(documentId, level);
      log.info("文档 {} 密级 {} → {}：抬高了 {} 个会话的水位", documentId, previous, level, raised);
    }
    log.info("文档 {} 密级 {} → {}：重打了 {} 个分段的密级", documentId, previous, level, affected.size());
    // ★ ③ 留痕（`#257` / 13 §七）：与业务同事务 —— 审计写不进去时整笔回滚。否则会出现
    //   "文档密级已经变了，但谁改的查不到"的静默变更，而这正是审计要防的东西。
    audit.record(
        AuditAction.DOCUMENT_RECLASSIFY,
        "DOCUMENT",
        documentId.toString(),
        currentUserId,
        Map.of("from", previous.name(), "to", level.name(), "segments", affected.size()));
    return DocumentView.from(documents.findById(documentId).orElse(document));
  }

  /**
   * 启用/停用（契约 {@code setDocumentEnabled}）。
   *
   * <p>★ 只动聚合根的 {@code enabled} 位，不碰分段与向量：停用是「不参与检索」，不是删除 —— 重新启用后无需重新向量化。
   */
  /**
   * 换一套标签（`#26`）：文档列与**每一个分段列**在同一个事务里一起改。
   *
   * <p>三条口径：
   *
   * <ol>
   *   <li><b>不校验库上限</b>：库的 ceiling 是密级的边界（`13 §6.2`），标签没有上限概念 ——
   *       标签之间的可见性是"相交才算"，不存在"这个库不允许出现「人事资料」"这种事。
   *   <li><b>不需要 {@code requireNotEmbedding}</b>：与改密级不同，标签<strong>不进 embedding</strong>、 也不进 {@code
   *       search_text}（`V57` 只加了两列），所以向量化跑到一半改标签不会让向量与正文错配。 真要拦的话，用户会看到"正在向量化，改不了标签"而完全没有必要。
   *   <li><b>同步分段列是必须的</b>：`LABEL_PREDICATE` 读的是 {@code kb_segment.labels}（不 join 回文档）。
   *       只改文档列的症状是"界面改了标签、检索仍按旧标签过滤"，由 Gauge {@code aiplatform.segment.label-mismatch}
   *       兜住（读数一涨就说明某条写入路径漏了）。
   * </ol>
   *
   * <p>★ 留痕：`action = document.relabel`，`detail` 带改动前后的两份标签 —— 标签直接影响"谁能检索到它"， 与密级同级（`13 §七`）。
   */
  @Transactional
  public DocumentView changeLabels(UUID documentId, List<String> labels, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    List<String> before = document.labels();
    List<String> after = Document.normalizeLabels(labels);

    document.changeLabels(after, currentUserId);
    documents.save(document);

    // ★ 分段必须在同一个事务里跟着改：谓词作用在分段表上（V57 的冗余列），
    //   漏这一步的读数是 Gauge `aiplatform.segment.label-mismatch` 一涨，而不是某个页面报错。
    List<Segment> affected = segments.findByDocumentId(documentId);
    for (Segment segment : affected) {
      segment.changeLabels(after);
    }
    if (!affected.isEmpty()) {
      segments.saveAll(affected);
    }

    log.info("文档 {} 标签 {} → {}：同步了 {} 个分段", documentId, before, after, affected.size());
    audit.record(
        AuditAction.DOCUMENT_RELABEL,
        "DOCUMENT",
        documentId.toString(),
        currentUserId,
        Map.of("from", before, "to", after, "segments", affected.size()));
    return DocumentView.from(documents.findById(documentId).orElse(document));
  }

  @Transactional
  public DocumentView setEnabled(UUID documentId, boolean enabled, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    if (enabled) {
      document.enable();
    } else {
      document.disable();
    }
    document.setUpdatedBy(currentUserId);
    return DocumentView.from(documents.save(document));
  }

  /**
   * 删除（契约 {@code deleteDocument}）—— 文档<strong>软删</strong>，分段与向量<strong>真删</strong>。
   *
   * <p>★ 为什么连接连删：软删只落聚合根（{@code 02 §5.1}），而分段没有软删列。把分段与向量留着的话，
   * 这篇文档的内容<strong>仍然能被检索到</strong>（引用里出现一篇"已删除"的文档）—— 列表里看不见、检索却答得出来， 是最难排查的一种。
   *
   * <p>★ 向量化中不许删：在途任务正按段落写向量与进度，把行删掉之后它会写一堆孤儿数据。与其让用户面对一个 "删了但状态还在变"的文档，不如明确拒绝并说清等待。
   */
  /**
   * 彻底删除（物理删除，`#75` 判据 ④ · `02 §5.1.1`）。
   *
   * <p>★ <strong>必须先软删</strong>：软删是"从用户视野里消失"（可撤销的那一步在心里），彻底删除是"这份材料不再存在"。
   * 让两条路各自独立，用户就有一次反悔的机会；直接物理删等于把两件事做成一件。
   *
   * <p>★ 为什么不是"删库里的行就完了"：分段与向量还在时，文档正文仍可被检索到（引用里出现一篇已不存在的文档）—— 所以这里与软删一样先删分段与向量，再删文档行。★
   * 两者顺序不能反：先删文档行会让分段变成孤儿， 而孤儿分段在检索里<strong>照样命中</strong>（谓词按 kb_segment 查，不看文档是否还在）。
   *
   * <p>★ 审计记录<strong>不删</strong>：`pf_audit_log` 只允许追加（`13 §7.2` 第 3 条），
   * 而且"这份材料被彻底删除了"本身就是最该留下的一条记录。这次动作写 {@code document.purge}。
   *
   * <p>★ 备份里仍然有：`deploy/README.md` 的备份策略保留 14 天日备 + 8 周周全备，
   * 所以"彻底删除"的真实语义是"从生产库与检索里消失"，不是"从所有介质上不可恢复"。
   */
  @Transactional
  public void purge(UUID documentId, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    if (document.deletedAt() == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "这份文档还在使用中，不能彻底删除", "先从文档列表里删除它（会进入待清理状态），确认不再需要之后再来彻底删除");
    }
    segments.deleteByDocumentId(documentId);
    segmentEmbeddings.deleteByDocumentId(documentId);
    documents.delete(document);
    audit.record(
        AuditAction.DOCUMENT_PURGE,
        "DOCUMENT",
        documentId.toString(),
        currentUserId,
        Map.of("name", document.name(), "kbId", document.kbId().toString()));
  }

  @Transactional
  public void delete(UUID documentId, UUID currentUserId) {
    Document document = requireVisibleDocument(documentId);
    requireNotEmbedding(document, "删除");
    segments.deleteByDocumentId(documentId);
    segmentEmbeddings.deleteByDocumentId(documentId);
    document.markDeleted(Instant.now(), currentUserId);
    documents.save(document);
    // ★ 留痕（`#257`）：删除是"让内容从用户视野里消失"，属等保 2.0 要追溯的动作。
    //   与业务同事务 —— 删掉了却没有记录，是最不该出现的一种静默。
    audit.record(
        AuditAction.DOCUMENT_DELETE,
        "DOCUMENT",
        documentId.toString(),
        currentUserId,
        Map.of("name", document.name(), "kbId", document.kbId().toString()));
  }

  /** 拒绝在「向量化中」做会与在途任务打架的操作（改名 / 删除）。 */
  private static void requireNotEmbedding(Document document, String action) {
    if (document.status() == DocumentStatus.EMBEDDING) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文档正在向量化中，不能" + action, "等它跑完（进度会自己往前走）再操作；若卡住不动，可等它落到「向量化失败」后重试");
    }
  }

  /**
   * 按文档当前的 文种/部门/名 重写全部分段的 {@code searchText} 与 {@code contentHash}。
   *
   * <p>★ 与 {@code SegmentService#update} 共用 {@link SearchText} 的拼装 —— 前缀形态只有一处真源，各拼一遍迟早漂移。
   * 正文原样保留，只换前缀。
   *
   * @throws BusinessException 新名称让某个分段的检索文本超出向量上限（宁可拒绝改名，也不写一条注定超预算的检索文本）
   */
  private void reprefixSegments(Document document, UUID currentUserId) {
    List<Segment> all = segments.findByDocumentId(document.id());
    if (all.isEmpty()) {
      return;
    }
    int maxChars = maxCharsResolver.resolve(document.kbId(), segmentationProperties.maxChars());
    // ★ 责任部门的 code 只查一次（每段都拼前缀，但部门是文档级的）：N+1 查询在 200 段时就是 200 次往返
    String orgCode = orgCodes.codeOf(document);
    for (Segment segment : all) {
      String prefix =
          SearchText.prefix(document.docType(), orgCode, document.name(), segment.titlePath());
      String searchText;
      try {
        searchText = SegmentContent.ofSearchText(prefix, segment.content(), maxChars).text();
      } catch (BusinessException e) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "改名被拒绝：新名称会让第 " + (segment.ordinal() + 1) + " 段的检索文本超出上限（" + e.getMessage() + "）",
            "把名称改短一些再试 —— 前缀会挤占向量预算（12 §4.2）");
      }
      String contentHash =
          SearchText.contentHash(
              document.docType(), orgCode, document.name(), segment.titlePath(), segment.content());
      // ★ 语义是「只换前缀」：正文原样传回；updateContent 会把 embeddingStatus 置回待向量化（前缀变了向量就旧了）
      segment.updateContent(segment.content(), searchText, contentHash, currentUserId);
    }
    segments.saveAll(all);
    document.markVectorsStale();
    documents.save(document);
  }

  // ────────────────────────── 库级重新向量化（契约 reembedKnowledgeBase） ──────────────────────────

  /**
   * 整库重新向量化（契约 {@code reembedKnowledgeBase}）—— 例如换了向量模型之后。回执只给 {@code queued} 数。
   *
   * <p>★ 只对<strong>有向量可作废</strong>的文档排队（{@code SUCCEEDED} / {@code PENDING_EMBEDDING} / {@code
   * EMBEDDING_FAILED} 且分段非空）。把未解析、分段失败、零分段的文档算进 {@code queued}， 会让用户对着一个永远不动的大数字等 —— 那个数字还与 {@code
   * ingestion-progress} 对不上。
   *
   * <p>★ 正在向量化中的文档<strong>跳过</strong>而不是报错：这是整库的批量动作，一篇在途文档不该让整库失败； 它本来就在跑，跑完就是最新的。
   *
   * <p>★ 刻意<strong>不加 {@code @Transactional}</strong>（与 {@link #reembed} 同一条判断）：
   * 向量化任务在后台线程里读文档状态，把它包进一个长事务，后台任务就可能读到<strong>还没提交的旧状态</strong> 而被状态机守卫拒掉 ——
   * 症状是"点了整库重算，回执说排队了，但一篇都没动"。
   */
  public ReembedKbView reembedKnowledgeBase(UUID kbId, UUID currentUserId) {
    KnowledgeBaseView base = knowledgeBases.get(kbId);
    if (base.embeddingModelId() == null || base.embeddingModelId().isBlank()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "知识库未绑定向量模型，无法重新向量化", "请在知识库设置中绑定一个 EMBEDDING 类型的模型");
    }
    int queued = 0;
    for (Document document : documents.findAllByKbId(kbId)) {
      if (document.status() == DocumentStatus.EMBEDDING
          || document.status() == DocumentStatus.PENDING_PARSE
          || document.status() == DocumentStatus.SEGMENTING
          || document.status() == DocumentStatus.SEGMENT_FAILED) {
        continue;
      }
      List<Segment> all = segments.findByDocumentId(document.id());
      if (all.isEmpty()) {
        continue;
      }
      // ★ 分段也要退回待向量化：向量化器只处理未完成的分段，不退回的话"重算"会一段都不做
      all.forEach(Segment::markPendingEmbedding);
      segments.saveAll(all);
      document.markVectorsStale();
      documents.save(document);
      runner.startAsync(document.id());
      queued++;
    }
    return new ReembedKbView(queued);
  }

  /** 按 id 取文档并做可见性判定（不可见与不存在同一个 404 —— 不泄漏存在性）。 */
  private Document requireVisibleDocument(UUID documentId) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在", "它可能已被删除，或你没有访问权限"));
    // ★ 可见性继承库（07 §3.3）：库不可见 ⇒ 文档也不可见
    knowledgeBases.get(document.kbId());
    return document;
  }

  // ────────────────────────── 建档 ──────────────────────────

  /**
   * 建档（最小可用形态：按文件名清单建行）。
   *
   * <p>★ 三项必填 + ceiling 校验见类注释。 任一缺失 ⇒ 400 并指出是哪一项；密级超 ceiling ⇒ 400 并给出可读原因。
   *
   * <p>★ 责任部门是<strong>组织树外键</strong>（{@code 07 §11.2}，issue #37）：入参是 {@code orgId}， 不再接受自由文本 ——
   * 自由文本既进不了数据权限，又会因为<strong>可变、可重名</strong>而在按名字比较的 地方静默越权。★ 这里额外做一次**存在性校验**：不校验就等于让 {@code
   * org_id} 指向一个从未存在过的 uuid，而外键报错会变成一次 500（"我崩了"），真正的原因却是"你传错了"。
   *
   * <p>★ 库不可见 ⇒ <strong>404</strong>（写也一样）—— 与 {@link #list} 同一判定。
   *
   * @param names 文件名清单（一批文档的显示名）；空清单 ⇒ 400
   * @param contents 与 {@code names} 等长的文件字节（multipart 上传时给）；null 或不给 ⇒ 只登记元数据，不解析
   * @param docType 文种（批次级）★ 必填
   * @param orgId 责任部门（组织树里的一个部门）★ 必填 —— 它决定召回前缀里那段稳定 code
   * @param classification 密级（批次级）★ 必填
   * @param labels 密级标签（批次级，{@code text[]}）—— 可空：空 = 不受标签限制（{@code #26} 写侧）。
   *     一次导入把这批文档打成同一套标签；逐篇覆盖等单篇编辑端点
   */
  @Transactional
  public List<DocumentView> register(
      UUID kbId,
      List<String> names,
      List<byte[]> contents,
      String docType,
      UUID orgId,
      ClassificationLevel classification,
      List<String> labels,
      UUID currentUserId) {

    // ★ 1 · 可见性继承库（写也 404）
    KnowledgeBaseView base = knowledgeBases.get(kbId);

    // ★ 2 · 建档必填三项 —— 逐项说清缺的是哪个
    if (docType == null || docType.isBlank()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文种必填", "选择这份文档的文种（合同/制度/报销规定…）—— 它决定召回前缀，不能有默认值（12 §5.1）");
    }
    if (orgId == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "责任部门必填",
          "请从组织树选责任部门 —— 它决定召回前缀里那段稳定标识（org code），不能有默认值，也不再接受自由文本（07 §11.2）");
    }
    if (orgDirectory.findById(orgId).isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "责任部门不存在：" + orgId,
          "请从组织树重新选择负责这份文档的部门 —— 它可能已被删除，或你传的是一个不属于任何部门的 id");
    }
    if (classification == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "密级必填", "为这份文档选择密级（公开/内部/秘密/机密）—— 它进安全过滤，不能有默认值（12 §5.1）");
    }

    if (names == null || names.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "至少要有一份文档", "传入文件名清单后再建档");
    }

    // ★ 3 · ceiling 校验（13 §6）：文档密级 > 库上限 ⇒ 拒绝
    ClassificationLevel ceiling = base.maxClassification();
    if (!classification.within(ceiling)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "文档密级（%s）高于知识库的密级上限（%s）—— 请提高库的上限，或改用更合适的库"
              .formatted(label(classification), label(ceiling)),
          "库的密级上限是入库校验的依据（13 §6）：只有不高于上限的文档才能进这个库");
    }

    List<DocumentView> created = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      if (name == null || name.isBlank()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "文档名称不能为空");
      }
      Document document =
          new Document(
              UUID.randomUUID(),
              kbId,
              name,
              "local",
              docType,
              orgId,
              classification,
              labels,
              currentUserId);
      // ★ 建档即解析 —— 但这一步**从同步改成了异步**（第 9 周 · 0.3.1）。
      //   为什么：扫描件的 OCR 实测每页约 3.6 秒（3 页约 11 秒），同步跑会把上传请求挂住十几秒到几分钟
      //   —— 网关超时、用户以为卡死，而且进度根本看不见（0/M 与 M/M 同时返回）。
      //   判据 ⑤ 要的是「OCR 异步、不阻塞其它文档」。
      //   ⇒ 这里只落行 + 发事件；解析由 ParseRunner 在**本事务提交后**起（见 DocumentUploaded）。
      //   带字节的文档落在「待解析」，前端按状态显示；不带字节的（只登记元数据）保持待解析不变。
      Document saved = documents.save(document);
      created.add(DocumentView.from(saved));
      if (contents != null && i < contents.size() && contents.get(i) != null) {
        events.publishEvent(new DocumentUploaded(saved.id(), contents.get(i), name, currentUserId));
      }
    }
    return created;
  }

  // ────────────────────────── 解析落库（由 ParseRunner 在异步任务里调） ──────────────────────────

  /**
   * 解析完成后落库（第 9 周 · 0.3.1）—— 解析结果 + 分块，**一个事务**。
   *
   * <p>★ 为什么收尾要独立一个事务方法、而解析本身不进来：解析跑分钟级，把它关进事务等于长时间占着连接， 而且逐页进度写在未提交的事务里**别人看不见**（"已识别"会永远停在 0/M）。
   * 所以边界是 「快扫+解析（无事务，进度各自短事务）」→「收尾（一个短事务）」。
   *
   * <p>★ 成功 ⇒ 待向量化 + 已解析正文 + 分块落库；失败 ⇒ 分段失败 + 可读原因 + 已缓冲部分（{@code DOC-I2}）。
   */
  @Transactional
  public void finishParse(UUID documentId, ParseResult parsed, UUID currentUserId) {
    Document document =
        documents
            .findById(documentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
    if (parsed.success()) {
      document.recordParseSuccess(parsed.markdown(), parsed.ocrPageCount());
      documents.save(document);
      // ★ 入库即分块（批 2b）：解析成功时立刻分块并落库
      segmentAndPersist(document, currentUserId);
      documents.save(document);
    } else {
      // ★ 下一步建议**由解析器给**：同一个"解析不出内容"，扫描件缺 OCR 引擎要的是"装引擎"，
      //   真空文档要的是"换文件" —— 硬编码一句通用建议等于把两种情况揉成一句谁也用不上的话
      document.recordParseFailure(parsed.failReason(), parsed.failHint(), parsed.markdown());
      documents.save(document);
    }
  }

  /**
   * 解析**抛异常**时的收尾（第 9 周 · 0.3.1）—— 不许让文档停在「分段中」等一个永远不会来的结果。
   *
   * <p>★ 只在文档仍然处于「分段中」时才写失败：它可能已经被删掉或已经走到了别处， 那时把状态按回去就是**用过期结论覆盖现状**。这种情况只记日志。
   */
  @Transactional
  public void failParse(UUID documentId, RuntimeException cause) {
    Document document = documents.findById(documentId).orElse(null);
    if (document == null) {
      return;
    }
    if (document.status() != DocumentStatus.SEGMENTING) {
      log.warn(
          "解析任务异常，但文档 {} 当前状态是 {}（不是分段中）⇒ 不覆盖它的状态。原因：{}",
          documentId,
          document.status(),
          cause.toString());
      return;
    }
    String reason = "解析失败：" + String.valueOf(cause.getMessage());
    document.recordParseFailure(reason, "请确认文件可正常打开；若为扫描件，确认服务端已装好 OCR 引擎与中文语言包", "");
    documents.save(document);
  }

  // ────────────────────────── 入库即分块（批 2b） ──────────────────────────

  /**
   * 上下文窗口并入量留痕（{@code #65} 判据 3）。
   *
   * <p>★ 为什么要这一行：窗口会让"同一句话被章节劈开"从召回不到变成召回得到，代价是进向量的文本变长 ——
   * 两件事都会改变检索读数。没有这条日志时，"这周答案变长了/变准了"只能靠猜；有了它，能对着文档与周次解释。
   *
   * <p>★ 它是<b>日志</b>而不是 Prometheus 指标，这是如实记账：指标那边只有三条业务指标（{@code #73}）， 加一条"窗口字数"的分布会把{@code 08
   * §4.10.1} 定的"只报结果指标"这条纪律搅浑。代价是它不能画曲线， 只能在排查时 grep（`上下文窗口：文档`）。
   */
  private void logWindowUsage(Document document, List<PlannedSegment> plan) {
    if (plan.isEmpty()) {
      return;
    }
    long withWindow =
        plan.stream().filter(p -> p.searchText() != null && p.searchText().contains("\n")).count();
    if (withWindow == 0) {
      return;
    }
    log.info(
        "上下文窗口：文档 {}（{}）{} 段中有 {} 段并入了邻段上下文",
        document.id(),
        document.name(),
        plan.size(),
        withWindow);
  }

  /**
   * 入库即分块：调 {@link Segmenter#plan} 分块并落库（第 7 周 · 0.2.2 批 2b）。
   *
   * <p>★ 参数口径（领域配置，不是常量）：
   *
   * <ul>
   *   <li>{@code segmentLength} 默认 500、{@code overlap} 默认 60 —— 与前端向导 {@code CreateWizard.vue} 的
   *       {@code segForm} 默认值一致，否则预览与入库结果不同
   *   <li>{@code maxChars}：① KB 绑定的向量模型有 {@code contextWindow} 则用它（近似）；② 否则回落配置 {@code
   *       app.segmentation.max-chars}，默认 1200 —— 与前端 mock 的 {@code MODEL_LIMIT} 一致
   * </ul>
   *
   * <p>★ 成功 ⇒ 落 segments + 回填 {@code segmentCount}/{@code charCount} + 状态 {@code PENDING_EMBEDDING}
   * （不是 {@code SUCCEEDED} —— 向量化还没做，那是下一周）。 失败 ⇒ {@code SEGMENT_FAILED} + 可读原因 （{@code
   * DOC-I2}：不许堆栈、不许静默）。
   *
   * <p>★ 重入幂等：先删后写 —— 同一文档重复分块不产生重复行（{@code (document_id, ordinal)} 唯一约束兜底）。
   *
   * <p>★ 与预览同源（判据 ④）：两边都只调 {@link Segmenter#plan}，不允许各自再拼一遍 {@code search_text}。
   */
  private void segmentAndPersist(Document document, UUID currentUserId) {
    try {
      int maxChars = maxCharsResolver.resolve(document.kbId(), segmentationProperties.maxChars());
      // ★ 按来源选分块规则集（12 §11.5）：判据是文档级的 OCR 事实，不是"环境里开着 OCR"
      // ★ 责任部门取**稳定 code**（07 §11.2）：放 name 会让一次改名把该部门下所有向量判废
      SegmentPlan planned =
          segmenter.plan(
              document.parsedContent(),
              segmentationProperties.segmentLength(),
              segmentationProperties.overlap(),
              maxChars,
              document.docType(),
              orgCodes.codeOf(document),
              document.name(),
              document.ocrDerived() ? ChunkSource.OCR : ChunkSource.TEXT,
              segmentationProperties.contextWindow());
      List<PlannedSegment> plan = planned.segments();
      // ★ 窗口并入量要留痕（#65 判据 3）：否则"这周答案变长了"没人能解释 —— 记段数与字符数
      logWindowUsage(document, plan);
      if (planned.removedNoiseLines() > 0) {
        // ★ 剥离掉的噪声要**留痕**：否则"为什么这份文档少了一页内容"没人能解释（12 §2.4）
        log.info(
            "噪声剥离：文档 {}（{}）剔除了 {} 行页眉/页脚/页码/目录",
            document.id(),
            document.name(),
            planned.removedNoiseLines());
      }

      // ★ 重入幂等：先删后写 —— 同一文档重复分块不产生重复行
      segments.deleteByDocumentId(document.id());
      // ★ 向量也要跟着清：旧分段的向量留着的话，检索会命中已经不存在于分段表里的内容
      segmentEmbeddings.deleteByDocumentId(document.id());

      List<Segment> toSave = new ArrayList<>(plan.size());
      for (PlannedSegment p : plan) {
        toSave.add(
            new Segment(
                UUID.randomUUID(),
                document.id(),
                document.kbId(),
                // ★ 域从文档继承（V34，13 §11.3）：分段不允许有自己独立的归属 ——
                //   域错了的后果不是报错，而是归属换了（会话附件被当成库文档）
                document.scopeKind(),
                document.scopeId(),
                document.ownerId(),
                p,
                currentUserId,
                // ★ 密级强制继承文档（13 §2.1 ②）：分段不允许有自己独立的密级
                document.classification(),
                // ★ 标签同理（#26）：谓词作用在分段表上，所以它必须冗余到这一列
                document.labels()));
      }
      segments.saveAll(toSave);

      // ★ 回填进度列 + 状态置 PENDING_EMBEDDING（不是 SUCCEEDED —— 向量化还没做）
      document.recordSegmentSuccess(
          plan.size(), toSave.stream().mapToInt(Segment::charCount).sum());
    } catch (BusinessException e) {
      // ★ Segmenter 抛的可读异常 ⇒ SEGMENT_FAILED + 可读原因（不许堆栈、不许静默）
      metrics.ingestionFailed(BusinessMetrics.IngestionStage.SEGMENT);
      document.recordFailure(DocumentStatus.SEGMENT_FAILED, e.getMessage(), e.hint());
    } catch (IllegalArgumentException e) {
      metrics.ingestionFailed(BusinessMetrics.IngestionStage.SEGMENT);
      document.recordFailure(DocumentStatus.SEGMENT_FAILED, e.getMessage(), "请检查文档正文是否可正常解析");
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 密级的<strong>展示名</strong>（公开/内部/秘密/机密）—— 只进错误文案，不进标识符、不进线上值、不进库（{@code 14} §1 规则 2）。
   *
   * <p>为什么不用机器码：错误消息是给人看的（{@code 08
   * §4.1}）。「文档密级（CONFIDENTIAL）高于上限（SECRET）」能读懂，但不如「文档密级（机密）高于知识库的密级上限（秘密）」照着改来得快。
   */
  private static String label(ClassificationLevel level) {
    return switch (level) {
      case PUBLIC -> "公开";
      case INTERNAL -> "内部";
      case SECRET -> "秘密";
      case CONFIDENTIAL -> "机密";
    };
  }
}
