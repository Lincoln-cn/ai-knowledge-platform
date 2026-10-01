package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.PlannedSegment;
import cn.lincoln.aiplatform.knowledge.domain.SearchText;
import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentContent;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.knowledge.domain.Segmenter;
import cn.lincoln.aiplatform.knowledge.infrastructure.SegmentationProperties;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分段用例层（第 7 周 · 0.2.2 批 2b）—— 切分预览 / 分段列表 / 分段编辑。
 *
 * <h3>★ 判据 ④：预览与入库同源（{@code 12 §3.5}）</h3>
 *
 * <p>预览与入库<strong>都只调 {@link Segmenter#plan}</strong>，不允许各自再拼一遍 {@code search_text}。 同一文档 + 同一参数 ⇒
 * 两边产出逐项相同（ordinal / content / charCount / oversized）。
 *
 * <h3>★ maxChars 取值顺序（领域配置，不是常量）</h3>
 *
 * <ol>
 *   <li>KB 绑定的向量模型（{@code KnowledgeBase.embeddingModelId} → {@code
 *       Model.contextWindow}）<strong>有则用它</strong> （口径：把模型窗口数当字数上限，近似）
 *   <li>否则回落配置 {@code app.segmentation.max-chars}，默认 <strong>1200</strong> —— 与前端 mock 的 {@code
 *       MODEL_LIMIT = 1200} 一致
 * </ol>
 *
 * <h3>★ 数据范围</h3>
 *
 * <p>列表查询把 {@code dataScope} 拼进查询（{@code 08 §4.4.3} 纪律 1），不可见库的分段不得返回。
 */
@Service
public class SegmentService {

  private static final int MAX_PAGE_SIZE = 100;

  private final SegmentRepository segments;
  private final DocumentRepository documents;
  private final KnowledgeBaseService knowledgeBases;
  private final AccessScopePort accessScope;
  private final SegmentationProperties properties;
  private final MaxCharsResolver maxCharsResolver;
  private final Segmenter segmenter = new Segmenter();

  public SegmentService(
      SegmentRepository segments,
      DocumentRepository documents,
      KnowledgeBaseService knowledgeBases,
      AccessScopePort accessScope,
      SegmentationProperties properties,
      MaxCharsResolver maxCharsResolver) {
    this.segments = segments;
    this.documents = documents;
    this.knowledgeBases = knowledgeBases;
    this.accessScope = accessScope;
    this.properties = properties;
    this.maxCharsResolver = maxCharsResolver;
  }

  // ────────────────────────── 切分预览 ──────────────────────────

  /**
   * 切分预览（契约 {@code POST /segmentation/preview}）。
   *
   * <p>★ 三条路径（{@code 12 §3.5}）：
   *
   * <ol>
   *   <li>{@code documentId}：读文档的 {@code parsedContent} 调 {@link Segmenter#plan}。文档不可见/不存在 ⇒ 404；
   *       解析尚未完成 ⇒ 400（不静默返回空 segments）
   *   <li>{@code segmentationPlan}（用户编辑过的计划）：仍必须跑 SEG-I1 校验（用户可能粘进超长内容）⇒ 超长 400； 通过则原样作为结果返回
   *   <li>{@code uploadIds}（暂存上传）：明确 400，文案与建档路径一致 —— 绝对不「收下但不用」
   * </ol>
   *
   * <p>★ {@code segmentLength > modelLimit} ⇒ 400 + hint（不要静默夹紧）。
   *
   * <p>★ 不加 {@code @Transactional}：本方法只读，且内部 {@code resolveMaxChars} 需要独立事务边界 —— 自调用绕过代理时 {@code
   * NOT_SUPPORTED} 不生效，会把外层事务标记为 rollback-only。
   */
  public PreviewResult preview(PreviewCommand command) {
    // ★ uploadIds（暂存上传）：明确 400，不静默忽略（与建档路径同文案口径）
    if (command.uploadIds() != null && !command.uploadIds().isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "暂存上传尚未支持",
          "uploadIds 是暂存上传形态（stageUploads 的产物），后端尚未消费——收下但不用会让人以为预览生效了。当前请先建档再用 documentId 预览");
    }

    // ★ strategy（12 §3.2）：本版只实现 AUTO（四层智能分段）。
    //   CUSTOM 必须**明确拒绝**——静默按 AUTO 处理会让界面那句「按固定长度切分」变成谎话
    //   （「收下但不用」是本项目最贵的一类病：用户以为选了固定长度，实际按语义边界切）
    String strategy = command.strategy() == null ? "AUTO" : command.strategy();
    if (!"AUTO".equals(strategy)) {
      String hint =
          "CUSTOM".equals(strategy)
              ? "CUSTOM（按固定长度切分）本版尚未实现；请使用 AUTO（按段落与语义边界切分）"
              : "可选值是 AUTO（本版唯一已实现的切分策略）";
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的切分策略：" + strategy, hint);
    }

    // ── 参数校验 ──
    if (command.segmentLength() <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "分段长度必须为正数");
    }
    if (command.overlap() < 0 || command.overlap() >= command.segmentLength()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "重叠必须满足 0 ≤ overlap < segmentLength，当前 overlap="
              + command.overlap()
              + "、segmentLength="
              + command.segmentLength());
    }

    // ── segmentationPlan 路径：用户编辑过的计划，仍必须跑 SEG-I1 ──
    if (command.segmentationPlan() != null && !command.segmentationPlan().isEmpty()) {
      return previewFromPlan(command);
    }

    // ── documentId 路径：读文档正文调 Segmenter.plan ──
    if (command.documentId() == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "请提供 documentId 或 segmentationPlan",
          "预览需要已解析的文档正文（documentId），或用户确认过的切分计划（segmentationPlan）");
    }

    Document document = requireVisibleDocument(command.documentId());
    // ★ 解析尚未完成 ⇒ 可读 400，不是空 segments 静默
    if (document.parsedContent() == null || document.parsedContent().isBlank()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文档解析尚未完成，无法预览切分", "请等待文档解析完成后再试；若解析失败，请查看文档的失败原因并修复后重新上传");
    }

    int maxChars = maxCharsResolver.resolve(document.kbId(), properties.maxChars());
    // ★ segmentLength > modelLimit ⇒ 400 + hint（不要静默夹紧）
    if (command.segmentLength() > maxChars) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "分段长度 " + command.segmentLength() + " 超过当前向量模型上限 " + maxChars + " 字",
          "请调小分段长度至 " + maxChars + " 字以内，或切换至长上下文向量模型");
    }

    List<PlannedSegment> plan =
        segmenter.plan(
            document.parsedContent(),
            command.segmentLength(),
            command.overlap(),
            maxChars,
            document.docType(),
            document.department(),
            document.name());

    List<PreviewSegment> result = new ArrayList<>(plan.size());
    for (PlannedSegment p : plan) {
      result.add(new PreviewSegment(p.ordinal(), p.content(), p.charCount(), p.oversizedRisk()));
    }
    long charCount = result.stream().mapToLong(PreviewSegment::charCount).sum();
    return new PreviewResult(result.size(), charCount, maxChars, result);
  }

  // ────────────────────────── 分段列表 ──────────────────────────

  /**
   * 分页列分段（契约 {@code GET /segments}）。
   *
   * <p>★ 数据范围拼进查询（{@code 08 §4.4.3} 纪律 1）：不可见库的分段不得返回。
   */
  @Transactional(readOnly = true)
  public PageResult<SegmentView> list(
      UUID documentId, Boolean onlyOversized, UUID kbId, Integer page, Integer size) {
    // ★ 限定了文档/库时先做可见性判定：不可见 ⇒ 404（与批 1 同一判定）
    if (documentId != null) {
      requireVisibleDocument(documentId);
    } else if (kbId != null) {
      knowledgeBases.get(kbId);
    }
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    var scope = accessScope.currentScope();
    List<Segment> found =
        segments.search(
            scope, documentId, kbId, onlyOversized, (safePage - 1) * safeSize, safeSize);
    long total = segments.countSearch(scope, documentId, kbId, onlyOversized);
    return PageResult.of(found.stream().map(SegmentView::from).toList(), total, safePage, safeSize);
  }

  // ────────────────────────── 分段编辑 ──────────────────────────

  /**
   * 编辑分段（契约 {@code PUT /segments/:id}）。
   *
   * <p>★ 改 {@code content} ⇒ 重跑 SEG-I1（超长 400）、重算 {@code searchText} 与 {@code contentHash}
   * （前缀仍用该文档的文种/部门/名），并把 {@code embeddingStatus} 置回 {@code PENDING_EMBEDDING} （内容改了向量就旧了）。
   *
   * <p>★ 越权/不可见 ⇒ 404（不泄漏存在性）。
   */
  @Transactional
  public SegmentView update(UUID id, String title, String content, UUID currentUserId) {
    Segment segment =
        segments
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分段不存在"));
    // ★ 可见性继承库：库不可见 ⇒ 其分段也不可见（404 而不是 403）
    Document document = requireVisibleDocument(segment.documentId());

    if (content != null) {
      // ★ 重跑 SEG-I1（12 §3.5：用户可能粘进超长内容）
      int maxChars = maxCharsResolver.resolve(document.kbId(), properties.maxChars());
      String prefix =
          SearchText.prefix(
              document.docType(), document.department(), document.name(), segment.titlePath());
      // 校验与落库必须是同一个字符串：直接用被校验过的那个 ——
      // 「我验的」与「我存的」若各自拼一遍，它们的差异只会在别的边界上暴露
      String searchText = SegmentContent.ofSearchText(prefix, content, maxChars).text();
      String contentHash =
          SearchText.contentHash(
              document.docType(),
              document.department(),
              document.name(),
              segment.titlePath(),
              content);
      segment.updateContent(content, searchText, contentHash, currentUserId);
    }
    if (title != null) {
      segment.updateTitle(title, currentUserId);
    }
    return SegmentView.from(segments.save(segment));
  }

  // ────────────────────────── 单条取数与删除 ──────────────────────────

  /**
   * 按 id 取一条分段（契约 {@code getSegment}，{@code conversations.ts} 的引用回溯用）。
   *
   * <p>★ 不可见/不存在 ⇒ 404（不泄漏存在性）—— 与单条取数同口径。
   */
  @Transactional(readOnly = true)
  public SegmentView get(UUID id) {
    Segment segment =
        segments
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分段不存在"));
    requireVisibleDocument(segment.documentId());
    return SegmentView.from(segment);
  }

  /**
   * 删除分段（契约 {@code deleteSegment}）。
   *
   * <p>★ 分段没有软删列（子实体，{@code 02 §5.1}）⇒ 真删。
   *
   * <p>★ 删完必须回填文档的进度列、并把状态置回 {@code PENDING_EMBEDDING}：少了一段，向量就旧了。 不回填的话界面会一直显示「5 段 / 已完成」，而库里只剩 4
   * 段 —— 又一处「看起来是对的」。
   */
  @Transactional
  public void delete(UUID id) {
    Segment segment =
        segments
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分段不存在"));
    Document document = requireVisibleDocument(segment.documentId());
    segments.delete(segment);
    List<Segment> remaining = segments.findByDocumentId(document.id());
    document.recordSegmentSuccess(
        remaining.size(), remaining.stream().mapToInt(Segment::charCount).sum());
    documents.save(document);
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 用户编辑过的切分计划路径：SEG-I1 校验通过则原样返回。 */
  private PreviewResult previewFromPlan(PreviewCommand command) {
    List<PreviewSegment> result = new ArrayList<>();
    // 前缀需要文档元数据；plan 路径可能没有 documentId
    String docType = "";
    String department = "";
    String title = "";
    int maxChars = properties.maxChars();
    boolean hasDocument = command.documentId() != null;
    if (hasDocument) {
      Document document = requireVisibleDocument(command.documentId());
      docType = document.docType();
      department = document.department();
      title = document.name();
      maxChars = maxCharsResolver.resolve(document.kbId(), properties.maxChars());
    }

    for (PlanItem item : command.segmentationPlan()) {
      if (item.content() == null || item.content().isEmpty()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "分段正文不能为空（序号 " + item.ordinal() + "）");
      }
      // ★ SEG-I1 硬校验（12 §3.5：用户可能粘进超长内容）。
      //   无文档上下文时按**最坏前缀长度**校验，而不是拿空前缀放宽 —— 否则「这里通过、入库因前缀更长而失败」
      String prefix =
          hasDocument
              ? SearchText.prefix(
                  docType, department, title, item.title() == null ? "" : item.title())
              : worstCasePrefix();
      SegmentContent.ofSearchText(prefix, item.content(), maxChars);
      result.add(
          new PreviewSegment(item.ordinal(), item.content(), item.content().length(), false));
    }
    long charCount = result.stream().mapToLong(PreviewSegment::charCount).sum();
    return new PreviewResult(result.size(), charCount, maxChars, result);
  }

  /**
   * 无文档上下文时的校验前缀 —— 取<strong>最坏长度</strong>（{@link SearchText#MAX_PREFIX_LENGTH}，前缀由构造保证不超它）。
   *
   * <p>★ 字符串内容毫无意义，<strong>只用它的长度</strong>：拿空前缀去校验等于把限制放宽（用户在预览里改通过的内容， 入库时因真实前缀含章节路径而失败）。这与 {@code
   * Segmenter.plan} 的预算口径是同一个原则。
   */
  private static String worstCasePrefix() {
    return SearchText.prefix("文种", "责任部门", "文档名", "章节".repeat(SearchText.MAX_PREFIX_LENGTH));
  }

  /** 按 id 取文档，并做可见性判定。★ 不可见与不存在同一个 NOT_FOUND。 */
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

  // ────────────────────────── 命令与结果 ──────────────────────────

  /** 预览命令（契约 {@code PreviewSegmentationBody} 的后端形态）。 */
  public record PreviewCommand(
      UUID documentId,
      int segmentLength,
      int overlap,
      String strategy,
      List<String> uploadIds,
      List<PlanItem> segmentationPlan) {}

  /** 切分计划条目（契约 {@code SegmentationPlanItem}）。 */
  public record PlanItem(int ordinal, String title, String content) {}

  /** 预览结果（契约 {@code PreviewSegmentationResult}）。 */
  public record PreviewResult(
      int segmentCount, long charCount, int modelLimit, List<PreviewSegment> segments) {}

  /** 预览分段条目（契约 {@code PreviewSegmentationResult.segments[]}）。 */
  public record PreviewSegment(int ordinal, String content, int charCount, boolean oversized) {}
}
