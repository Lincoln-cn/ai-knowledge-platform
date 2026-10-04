package cn.lincoln.aiplatform.knowledge.domain;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 文档（聚合根）—— 第 5 周 · 0.2.0 批 2：建档元数据。
 *
 * <p>★ <b>建档必填三项</b>（{@code DOC-I6} / {@code 12 §5.1}）： {@link #docType}（文种）· {@link
 * #department}（责任部门）· {@link #classification}（密级）。 这三项分别决定「召回前缀」与「安全过滤」，<strong>不能有默认值</strong> ——
 * 密级默认「未知」要么全放行（泄密）要么全拦（不可用）。
 *
 * <p>★ <b>ceiling 校验</strong>（{@code 13 §6}）： 文档密级必须不高于所属库的 {@code maxClassification}。
 * 判定在建档用例层做（要给出可读原因）， 这里只负责把值存住。
 *
 * <p>★ <b>可见性继承库</strong>：文档不是 {@code OwnedResource} —— 谁能看它由所属知识库决定。 判定走库那一套（{@code
 * KnowledgeBaseService.requireVisible}），<strong>不另写一套</strong>。
 *
 * <p>★ 解析/分块是第 6–7 周的事：{@link #charCount} / {@link #segmentCount} / {@link #processedSegments}
 * 本批只落列、恒为 0。
 */
@Entity
@Table(name = "kb_document")
public class Document {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属知识库。可见性继承它 —— 库不可见 ⇒ 其文档也不可见。 */
  @Column(name = "kb_id", nullable = false)
  private UUID kbId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /** 来源（契约 {@code DocumentItem.source}）。建档路径是本地上传，取 {@code local}。 */
  @Column(name = "source", nullable = false, length = 32)
  private String source;

  /** 文种（合同/制度/报销规定…）。★ 建档必填。 */
  @Column(name = "doc_type", nullable = false, length = 32)
  private String docType;

  /** 责任部门。★ 建档必填。 */
  @Column(name = "department", nullable = false, length = 64)
  private String department;

  /** 文档密级。★ 建档必填；入库时必须 ≤ 库的 ceiling。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "classification", nullable = false, length = 16)
  private ClassificationLevel classification;

  /** 加工状态。初值 {@link DocumentStatus#PENDING_PARSE}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private DocumentStatus status;

  /** 失败的可读原因（{@code DOC-I2}）。状态为 {@code *FAILED} 时必填。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "fail_reason")
  private String failReason;

  /** 失败时的下一步建议（{@code 08 §4.1}）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "fail_hint")
  private String failHint;

  /**
   * 解析出的 Markdown（第 6 周 · 0.2.1）。
   *
   * <p>★ 失败时也可能是<strong>部分内容</strong>（{@code 12 §2.1}：解析中途失败保留 handler 已缓冲的片段） —— 「解析失败 =
   * 整篇丢弃」会让用户对着空文档猜内容。
   */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "parsed_content")
  private String parsedContent;

  /** 总字符数。本批恒 0，随加工链回填。 */
  @Column(name = "char_count", nullable = false)
  private long charCount;

  /** 总分段数。本批恒 0。 */
  @Column(name = "segment_count", nullable = false)
  private int segmentCount;

  /** 已处理分段数（真实进度）。本批恒 0。 */
  @Column(name = "processed_segments", nullable = false)
  private int processedSegments;

  /**
   * 需要 OCR 的页数 —— 进度分母（{@code 12 §11.8} 的「已识别 N/M 页」里的 M）。
   *
   * <p>★ 由一次**关掉 OCR 的快扫**得出（{@code pdf:charsPerPage} 每页字符数 &lt; 10 的页数）， 与全量解析不是一个量级（实测 0.3 秒 vs
   * 11 秒）；判据取的是 Tika 自己用的同一份数据。 非 PDF / 纯文本 PDF 恒 0（= 不涉及 OCR）。
   */
  @Column(name = "ocr_total_pages", nullable = false)
  private int ocrTotalPages;

  /** 已完成 OCR 的页数（进度，覆盖写、不回退）。 */
  @Column(name = "ocr_done_pages", nullable = false)
  private int ocrDonePages;

  /** 其中真的走了 OCR 的页数（Tika 元数据 {@code pdf:ocrPageCount}）—— 见 {@link #ocrDerived()}。 */
  @Column(name = "ocr_page_count", nullable = false)
  private int ocrPageCount;

  /** 停用后不参与检索。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected Document() {}

  /**
   * 建档。
   *
   * <p>★ 三项必填在这里再校验一遍（应用层已经拦过）： 领域不变量不能只靠调用方自觉 —— 库层的 NOT NULL 是最后一道闸，这里是给人看的那道。
   *
   * @param source 来源；null 时取 {@code local}（本地上传建档）
   */
  public Document(
      UUID id,
      UUID kbId,
      String name,
      String source,
      String docType,
      String department,
      ClassificationLevel classification,
      UUID createdBy) {
    if (id == null || kbId == null) {
      throw new IllegalArgumentException("文档 id 与所属知识库 id 不能为空");
    }
    if (name == null || name.isBlank() || name.length() > 255) {
      throw new IllegalArgumentException("文档名称必须是 1–255 字");
    }
    // ★ DOC-I6：三项缺一不可，且没有默认值
    if (docType == null || docType.isBlank()) {
      throw new IllegalArgumentException("文种必填");
    }
    if (department == null || department.isBlank()) {
      throw new IllegalArgumentException("责任部门必填");
    }
    if (classification == null) {
      throw new IllegalArgumentException("密级必填");
    }
    this.id = id;
    this.kbId = kbId;
    this.name = name.trim();
    this.source = source == null || source.isBlank() ? "local" : source.trim();
    this.docType = docType.trim();
    this.department = department.trim();
    this.classification = classification;
    // ★ 初值 = 未解析那一态，不是「成功」
    this.status = DocumentStatus.PENDING_PARSE;
    this.charCount = 0;
    this.segmentCount = 0;
    this.processedSegments = 0;
    this.ocrTotalPages = 0;
    this.ocrDonePages = 0;
    this.ocrPageCount = 0;
    this.enabled = true;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
  }

  // ────────────────────────── 行为（失败记录；状态机随加工链补全） ──────────────────────────

  /**
   * 记录一次加工失败（{@code DOC-I2}）。
   *
   * <p>★ 失败必须留下<strong>可读原因</strong>——「加载失败」四个字等于让用户停在原地（{@code 08 §4.1}）。
   *
   * @param reason 可读原因（必填）
   * @param hint 下一步建议（可空）
   */
  public void recordFailure(DocumentStatus failedStatus, String reason, String hint) {
    if (failedStatus != DocumentStatus.SEGMENT_FAILED
        && failedStatus != DocumentStatus.EMBEDDING_FAILED) {
      throw new IllegalArgumentException("只有失败态可以记录失败原因");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("失败原因必填 —— 不许静默失败（DOC-I2）");
    }
    DocumentStatus.requireLegal(this.status, failedStatus, "记录加工失败");
    this.status = failedStatus;
    this.failReason = reason;
    this.failHint = hint;
  }

  /**
   * 开始解析 —— {@code PENDING_PARSE → SEGMENTING}（第 9 周 · 0.3.1）。
   *
   * <p>★★ 为什么解析要单独一个"开始"：扫描件的解析是**分钟级**的（实测每页约 3.6 秒）， 所以它必须异步 —— 上传接口立刻返回、文档落 {@code SEGMENTING}
   * 在途态，OCR 在后台跑并逐页回写进度。 于是"解析与分块在同一事务里"这件旧事实不再成立，两个转移要分开走。
   *
   * <p>★ 状态机**没改**：{@code SEGMENTING} 的允许来源本来就含 {@code PENDING_PARSE}， 而且 {@link DocumentStatus}
   * 的注释早就写明「等改成异步流水线时它就是在途态，届时不必改状态机」。
   *
   * @param ocrTotalPages 需要 OCR 的页数（快扫得出，做进度分母）；不涉及 OCR 时传 0
   */
  public void startParse(int ocrTotalPages) {
    DocumentStatus.requireLegal(status, DocumentStatus.SEGMENTING, "开始解析");
    this.status = DocumentStatus.SEGMENTING;
    this.ocrTotalPages = Math.max(0, ocrTotalPages);
    this.ocrDonePages = 0;
    this.ocrPageCount = 0;
    this.failReason = null;
    this.failHint = null;
  }

  /**
   * 回写 OCR 进度（每页处理完一次）—— <strong>覆盖写</strong>，不是累加。
   *
   * <p>★ 与 {@link #reportEmbeddingProgress(int)} 同一条纪律：传进来的是「已完成多少页」这个**事实**； 重投/乱序（比当前值小）是**无害
   * no-op**，不报错也不回退 —— 否则一次乱序回写就会让进度倒退。
   *
   * @param done 已完成的 OCR 页数
   * @throws IllegalStateException 不在解析中（"没开始就报进度"不许静默生效）
   */
  public void reportOcrPageDone(int done) {
    DocumentStatus.requireInState(status, DocumentStatus.SEGMENTING, "回写 OCR 进度");
    if (done < 0) {
      throw new IllegalArgumentException("OCR 进度不能为负：" + done);
    }
    if (done < this.ocrDonePages) {
      return;
    }
    this.ocrDonePages = done;
  }

  /**
   * 解析成功 —— {@code SEGMENTING（或 PENDING_PARSE） → 待向量化}。
   *
   * <p>★ 成功路径要<strong>清掉上一次的失败原因</strong>， 否则界面会把旧错误挂在已恢复的文档上（与 {@code Model#markLoaded} 同一条纪律）。
   *
   * <p>★ 允许来源含 {@code PENDING_PARSE}：没有文件字节的"只登记元数据"路径不走 {@link #startParse}。
   *
   * @param markdown 解析出的 Markdown 正文
   * @param ocrPageCount 其中走了 OCR 的页数（{@code pdf:ocrPageCount}；非 PDF 传 0）
   */
  public void recordParseSuccess(String markdown, int ocrPageCount) {
    DocumentStatus.requireLegal(status, DocumentStatus.PENDING_EMBEDDING, "记录解析成功");
    this.status = DocumentStatus.PENDING_EMBEDDING;
    this.parsedContent = markdown == null ? "" : markdown;
    this.charCount = this.parsedContent.length();
    this.failReason = null;
    this.failHint = null;
    // ★ 最终以 Tika 报的事实为准（快扫只是估算分母）：两者不一致时取大的那个，
    //   否则进度会出现"已完成 3 / 共 2"这种自相矛盾。真出现差异意味着两处口径漂了，
    //   由 ParseRunner 打 WARN（不静默）。
    this.ocrPageCount = Math.max(0, ocrPageCount);
    this.ocrTotalPages = Math.max(this.ocrTotalPages, this.ocrPageCount);
    this.ocrDonePages = this.ocrPageCount;
  }

  /**
   * 分块成功（第 7 周 · 0.2.2 批 2b）—— 回填分段进度列，状态保持「待向量化」。
   *
   * <p>★ 状态是 {@code PENDING_EMBEDDING} 而不是 {@code SUCCEEDED} —— 向量化还没做，那是下一周。
   * 界面会把「已分段、待向量化」与「全部完成」区分开。
   *
   * @param segmentCount 分段数
   * @param charCount 分段正文总字符数
   */
  public void recordSegmentSuccess(int segmentCount, long charCount) {
    DocumentStatus.requireLegal(this.status, DocumentStatus.PENDING_EMBEDDING, "回填分段结果");
    this.segmentCount = segmentCount;
    this.charCount = charCount;
    this.status = DocumentStatus.PENDING_EMBEDDING;
    // ★ 分段结果变了 ⇒ 进度必须归零：旧进度是上一轮的段数口径，留着就是错的
    this.processedSegments = 0;
    this.failReason = null;
    this.failHint = null;
  }

  // ────────────────────────── 行为：向量化阶段（第 8 周 · 0.3.0） ──────────────────────────

  /**
   * 开始向量化 —— {@code PENDING_EMBEDDING → EMBEDDING}，并把进度归零。
   *
   * <p>★ 在这里归零的理由：进度是「本轮向量化」的事实，不是文档的终身累计。重新向量化时它必须回到 0/N， 否则界面会出现「已经 100%
   * 但还在跑」的自相矛盾。同时这也把「进度不回退」的规则限定在**同一轮**内 —— 否则跨轮的重置会被单调性挡住。
   */
  public void startEmbedding() {
    DocumentStatus.requireLegal(status, DocumentStatus.EMBEDDING, "开始向量化");
    this.status = DocumentStatus.EMBEDDING;
    this.processedSegments = 0;
    this.failReason = null;
    this.failHint = null;
  }

  /**
   * 回写向量化进度（{@code 03 §3.4} 真实进度）—— <strong>覆盖写</strong>，不是累加。
   *
   * <p>★ {@code 03 §3.3} 的原话：{@code processedSegments} 必须覆盖写而不是 {@code += 1}，
   * 「单次执行看起来完全正确，只有在事件重投时才暴露为进度变成 200%」。所以本方法**不接受增量参数** —— 传进来的就是「已经完成多少段」这个<strong>事实</strong>。
   *
   * <p>★ 回退（比当前值小）<strong>不报错、也不改变进度</strong>：模块间是「至少一次投递」（{@code 03 §3.3}）， 重投的旧事件必须是无害的 no-op ——
   * 报错会让一次本来无害的重投变成一次失败。
   *
   * @param processed 已完成的段数（0 ≤ processed ≤ segmentCount）
   * @throws IllegalStateException 不在向量化中（别让"没开始就报进度"静默生效）
   * @throws IllegalArgumentException 越界
   */
  public void reportEmbeddingProgress(int processed) {
    // ★ 就地更新（状态不变）⇒ 校验的是「当前是不是向量化中」，不是一次转移
    DocumentStatus.requireInState(status, DocumentStatus.EMBEDDING, "回写向量化进度");
    if (processed < 0 || processed > segmentCount) {
      throw new IllegalArgumentException(
          "向量化进度越界：" + processed + " 不在 0.." + segmentCount + "（分段总数）");
    }
    if (processed < this.processedSegments) {
      return;
    }
    this.processedSegments = processed;
  }

  /**
   * 向量化完成 —— {@code EMBEDDING → SUCCEEDED}。
   *
   * <p>★ <strong>只有 {@code processedSegments == segmentCount} 才允许置成功</strong>（{@code 02} 的库约束
   * {@code chk_ready} 也钉了这条）：否则界面显示「已完成」，而库里有分段根本没进向量 —— 「看起来在做、实际没生效」最贵的一种。
   */
  public void markEmbedded() {
    DocumentStatus.requireLegal(status, DocumentStatus.SUCCEEDED, "标记向量化完成");
    if (processedSegments != segmentCount) {
      throw new IllegalStateException(
          "不能标记向量化完成：已完成 "
              + processedSegments
              + " 段、共 "
              + segmentCount
              + " 段 —— 还有 "
              + (segmentCount - processedSegments)
              + " 段没进向量（状态未改变）");
    }
    this.status = DocumentStatus.SUCCEEDED;
    this.failReason = null;
    this.failHint = null;
  }

  /** 向量化失败：{@code EMBEDDING → EMBEDDING_FAILED} + 可读原因（{@code DOC-I2}）。 */
  public void markEmbeddingFailed(String reason, String hint) {
    recordFailure(DocumentStatus.EMBEDDING_FAILED, reason, hint);
  }

  /** 重试向量化：{@code EMBEDDING_FAILED → PENDING_EMBEDDING}，进度归零、清失败原因。 */
  public void retryEmbedding() {
    DocumentStatus.requireLegal(status, DocumentStatus.PENDING_EMBEDDING, "重试向量化");
    this.status = DocumentStatus.PENDING_EMBEDDING;
    this.processedSegments = 0;
    this.failReason = null;
    this.failHint = null;
  }

  /**
   * 重试分段：{@code SEGMENT_FAILED → SEGMENTING}（重新解析与分块）。
   *
   * <p>保留已解析出的正文（{@code 12 §2.1}：中途失败的部分内容要留下，便于定位与人工兜底）。
   */
  public void retrySegmentation() {
    DocumentStatus.requireLegal(status, DocumentStatus.SEGMENTING, "重试分段");
    this.status = DocumentStatus.SEGMENTING;
    this.failReason = null;
    this.failHint = null;
    // ★ OCR 进度一起归零：重新解析会产生新的一轮页数口径，旧进度留着就是错的
    //   （与 startEmbedding 归零 processedSegments 同一条理由）
    this.ocrTotalPages = 0;
    this.ocrDonePages = 0;
    this.ocrPageCount = 0;
  }

  /**
   * 向量已过期（分段被编辑、删除或文档改名后）—— {@code SUCCEEDED → PENDING_EMBEDDING}（{@code SEG-I7}）。
   *
   * <p>★ 内容变了向量就旧了：不回到「待向量化」的话，检索会用旧向量答新内容，界面上什么都看不出来。 已经在待向量化时是 no-op（重复编辑不该报错）。
   *
   * <p>★ 那三个「本来就没有新鲜向量」的状态同样是 no-op（未解析 / 分段中 / 分段失败）： 它们身上没有需要作废的向量， 硬套转移矩阵会给一次合法操作抛「不允许转到待向量化」——
   * 那是<strong>把"无事可做"报成"做错了"</strong>。 真正要拦的是 {@code EMBEDDING}（向量化正在跑，此刻作废会与在途任务打架），调用方应先显式拒绝它。
   */
  public void markVectorsStale() {
    switch (status) {
      case PENDING_EMBEDDING, PENDING_PARSE, SEGMENTING, SEGMENT_FAILED -> {
        return;
      }
      default -> {}
    }
    DocumentStatus.requireLegal(status, DocumentStatus.PENDING_EMBEDDING, "标记向量已过期");
    this.status = DocumentStatus.PENDING_EMBEDDING;
    this.processedSegments = 0;
  }

  /**
   * 解析失败（第 6 周 · 0.2.1）—— 状态到「分段失败」+ 可读原因（{@code DOC-I2}）。
   *
   * <p>★ {@code partialMarkdown} 是解析中途已缓冲的片段，<strong>必须留下</strong>（{@code 12 §2.1}）。
   *
   * @param reason 可读原因（必填，不是堆栈）
   * @param hint 下一步建议（可空）
   * @param partialMarkdown 已解析出的部分正文（可为空串，但不许丢掉非空片段）
   */
  public void recordParseFailure(String reason, String hint, String partialMarkdown) {
    recordFailure(DocumentStatus.SEGMENT_FAILED, reason, hint);
    this.parsedContent = partialMarkdown == null ? "" : partialMarkdown;
    if (!this.parsedContent.isEmpty()) {
      this.charCount = this.parsedContent.length();
    }
  }

  public void markDeleted(Instant at, UUID by) {
    this.deletedAt = at;
    this.updatedBy = by;
  }

  /** 文档名长度上限。★ 与库列 {@code kb_document.name varchar(255)} 一致 —— 领域不管住它就会变成一次插入失败。 */
  public static final int MAX_NAME_LENGTH = 255;

  /**
   * 改名（契约 {@code PATCH /documents/:id}）。
   *
   * <p>★ 超长<strong>明确拒绝</strong>、不静默截断：名字是用户输入，而且它还是结构化前缀的一段 （{@code 12 §4.2}）， 截断会悄悄改变检索文本。
   *
   * <p>★ 只改聚合根本身。<strong>分段的 {@code searchText}/{@code contentHash} 里含文档名</strong>，
   * 重写它们属于跨聚合的编排，由应用层（{@code DocumentService#rename}）负责。
   */
  public void rename(String newName) {
    if (newName == null || newName.isBlank() || newName.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("文档名称必须是 1–" + MAX_NAME_LENGTH + " 字");
    }
    this.name = newName.trim();
  }

  /** 启用（停用的文档不参与检索，数据保留）。 */
  public void enable() {
    this.enabled = true;
  }

  /** 停用（同上）。 */
  public void disable() {
    this.enabled = false;
  }

  public void setUpdatedBy(UUID by) {
    this.updatedBy = by;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID kbId() {
    return kbId;
  }

  public String name() {
    return name;
  }

  public String source() {
    return source;
  }

  public String docType() {
    return docType;
  }

  public String department() {
    return department;
  }

  public ClassificationLevel classification() {
    return classification;
  }

  public DocumentStatus status() {
    return status;
  }

  public String failReason() {
    return failReason;
  }

  public String failHint() {
    return failHint;
  }

  /** 解析出的 Markdown（失败时可能是部分内容）。 */
  public String parsedContent() {
    return parsedContent;
  }

  public long charCount() {
    return charCount;
  }

  public int segmentCount() {
    return segmentCount;
  }

  public int processedSegments() {
    return processedSegments;
  }

  /** 需要 OCR 的页数（进度分母；0 = 不涉及 OCR）。 */
  public int ocrTotalPages() {
    return ocrTotalPages;
  }

  /** 已完成 OCR 的页数（进度）。 */
  public int ocrDonePages() {
    return ocrDonePages;
  }

  /** 其中真的走了 OCR 的页数（最终事实）。 */
  public int ocrPageCount() {
    return ocrPageCount;
  }

  /**
   * 这份文档是不是 OCR 产物 —— 分块器据此走两套规则（{@code 12 §11.5}）。
   *
   * <p>★ 判据取「真的走了 OCR 的页数 &gt; 0」而不是「配置里开着 OCR」：混合型 PDF 里可能一页都没走 OCR，
   * 那时它就该按文本型规则切（不该因为环境装了引擎就换规则集）。
   */
  public boolean ocrDerived() {
    return ocrPageCount > 0;
  }

  public boolean enabled() {
    return enabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }
}
