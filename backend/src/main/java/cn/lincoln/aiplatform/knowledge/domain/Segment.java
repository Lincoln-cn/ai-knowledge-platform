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
 * 文档分段（子实体，第 7 周 · 0.2.2 批 2b）—— 文档是聚合根（{@code 02 §5.1}）。
 *
 * <p>★ <b>没有 {@code deletedAt}</b>：软删仅聚合根（{@code 02 §5.1}）。 分段随文档软删级联 ——
 * 给子实体加软删列会制造「文档已删、分段还在」的孤儿态。
 *
 * <p>★ <b>content 与 searchText 分两列</b>（{@code 12 §4.2} 纪律 1）：
 *
 * <ul>
 *   <li>{@link #content} = 原文片段 —— 引用展示时用户看到的必须是干净原文，<strong>前缀绝不进这里</strong>
 *   <li>{@link #searchText} = 结构化前缀 + 分隔 + 正文 —— 向量化输入 + 全文索引源
 * </ul>
 *
 * <p>混成一列的后果：引用展示会把「制度·采购部·报销规定｜第一章」当成正文显示， 用户以为文档里真有这句话。
 *
 * <p>★ <b>contentHash</b>（{@code SEG-I7}）：sha256(文种|部门|标题|章节路径|正文)。 改了前缀组成 ⇒ hash 必须变 ⇒ 重向量判定才有依据。
 *
 * <p>★ <b>embeddingStatus</b> 四态与契约 {@code SegmentEmbeddingStatus} 一字不差。 初值 {@link
 * SegmentEmbeddingStatus#PENDING_EMBEDDING} —— 向量化还没做（那是下一周）。
 */
@Entity
@Table(name = "kb_segment")
public class Segment {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** 所属文档（聚合根）。 */
  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  /** 所属知识库（冗余列，查询时不必每次 join 文档表）。 */
  @Column(name = "kb_id", nullable = false)
  private UUID kbId;

  /**
   * 归属**种类** —— ★ 冗余列（{@code V34}，{@code 13 §6.4} / §11.3）。
   *
   * <p>★ 为什么冗余一份而不是每次 join 文档：{@code content_tsv} 就在这张表上，两条检索路的 <strong>域过滤</strong>必须能在这张表上直接生效 ——
   * 谓词只长在 {@code kb_document} 上时， "两路同谓词"就退化成"两处 join 写法必须一致"，而那种一致没人能保证。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "scope_kind", nullable = false, length = 16)
  private DocumentScopeKind scopeKind;

  /** 归属主体（冗余列）：KB ⇒ 库 id；CONVERSATION ⇒ 会话 id。与 {@link #kbId} 在库域是同一个值。 */
  @Column(name = "scope_id", nullable = false)
  private UUID scopeId;

  /** 传输者（冗余列）：只有会话域那一支拿它当附加条件（{@code owner_id = :me}）。 */
  @Column(name = "owner_id")
  private UUID ownerId;

  /** 文档内序号（从 0 起）。 */
  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  /** 分段标题（{@code 12 §3.4}）。 */
  @Column(name = "title", length = 255)
  private String title;

  /** 章节路径 {@code H1 > H2 > H3}。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "title_path")
  private String titlePath;

  /** ★ 原文片段 —— 前缀绝不进这一列（{@code 12 §4.2} 纪律 1）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "content", nullable = false)
  private String content;

  /** ★ 前缀 + 正文 —— 向量化输入 + 全文索引源（{@code 12 §4.2}）。 */
  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "search_text", nullable = false)
  private String searchText;

  /** {@code SEG-I7}：重向量判定依据（sha256 十六进制 64 字符）。 */
  @Column(name = "content_hash", nullable = false, length = 64)
  private String contentHash;

  @Column(name = "char_count", nullable = false)
  private int charCount;

  @Column(name = "is_table", nullable = false)
  private boolean isTable;

  @Column(name = "is_code", nullable = false)
  private boolean isCode;

  /** L4 硬切标记（{@code 12 §3.2} 规则 4）。 */
  @Column(name = "oversized_risk", nullable = false)
  private boolean oversizedRisk;

  /** 停用后不参与检索。 */
  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  /** 向量化状态。初值 {@link SegmentEmbeddingStatus#PENDING_EMBEDDING}。 */
  @Enumerated(EnumType.STRING)
  @Column(name = "embedding_status", nullable = false, length = 32)
  private SegmentEmbeddingStatus embeddingStatus;

  /**
   * 分段密级 —— ★ <strong>强制继承所属文档</strong>（{@code 13 §2.1} ②），不允许单独下调。
   *
   * <p>★ 为什么冗余一份而不是每次 join 文档：{@code content_tsv} 与 {@code kb_id} 都在这张表上， 两条检索路（向量 / 全文）的密级过滤必须
   * <strong>直接在这张表上生效</strong> —— 谓词只长在文档表上时，"两路同谓词"就退化成 "两处 join 写法必须一致"，而那迟早会漂（{@code 13 §6.4}）。
   *
   * <p>★ 为什么允许单独下调是危险的：那样"文档密级"就形同虚设 —— 有人把机密文档的所有分段标成公开即可绕过。 所以它<strong>只能</strong>由文档密级赋值，没有
   * {@code setClassification} 这样的行为。
   *
   * <p>★ {@code nullable = false} 且<b>没有默认值</b>：漏传是<strong>插入时的一次报错</strong>， 而不是"静默落一个
   * PUBLIC"——后者是一条不会报警的越权。
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "classification", nullable = false, length = 16)
  private ClassificationLevel classification;

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

  protected Segment() {}

  /**
   * 从分段计划落库。
   *
   * <p>★ {@code searchText} 必须是 {@link Segmenter#plan} 的产物 —— 不许调用方自己再拼一遍前缀 （判据 ④：预览与入库同源）。
   *
   * <p>★ <strong>没有"不带密级"的重载</strong>：那必然要挑一个默认值，而两个候选都是错的 —— 默认成 {@code PUBLIC} 是一次
   * <strong>静默越权</strong>，默认成最高档会让内容凭空消失。所以密级是必填参数，缺了就在编译期过不去。
   *
   * @param planned 分段计划条目（含前缀拼接与哈希）
   * @param classification ★ <strong>所属文档的密级</strong>（{@code 13 §2.1} ②：强制继承，不允许单独下调）
   * @param scopeKind ★ <strong>所属文档的归属种类</strong>（{@code V34}）。与密级同一条纪律：分段不允许 有自己独立的域 ——
   *     域错了的后果不是报错，而是<strong>归属换了</strong>（会话附件被当成库文档，
   *     凡能看那个库的人都检索得到）。所以三个域参数都由调用方从<strong>文档</strong>取。
   * @param scopeId 所属文档的归属主体（库域 = {@code kbId}；会话域 = 会话 id）
   * @param ownerId 所属文档的传输者（可空；会话域那一支要求非空）
   */
  public Segment(
      UUID id,
      UUID documentId,
      UUID kbId,
      DocumentScopeKind scopeKind,
      UUID scopeId,
      UUID ownerId,
      PlannedSegment planned,
      UUID createdBy,
      ClassificationLevel classification) {
    if (id == null || documentId == null || scopeId == null) {
      throw new IllegalArgumentException("分段 id / 文档 id / 归属主体不能为空");
    }
    if (scopeKind == null) {
      // ★ 与密级同一条理由：默认成某个域就是把归属悄悄换掉，两个候选都是错的
      throw new IllegalArgumentException("分段归属种类不能为空（13 §11.3：三域必须显式）");
    }
    if (scopeKind == DocumentScopeKind.KB && kbId == null) {
      // ★ 与库层 chk_segment_scope_shape 逐字同一条规则（Java 侧先报，给的是可读原因）
      throw new IllegalArgumentException("库域的分段必须有知识库（scope_kind = 'KB' ⇒ kb_id 非空且等于 scope_id）");
    }
    if (scopeKind == DocumentScopeKind.CONVERSATION && (kbId != null || ownerId == null)) {
      // ★ 会话域没有库行；owner_id 为空会让第三支谓词恒假 ⇒ 会话附件谁都检索不到（静默失效）
      throw new IllegalArgumentException(
          "会话域的分段必须 kb_id 为空且 owner_id 非空（13 §11.3 第三支：owner_id = :me）");
    }
    if (classification == null) {
      // ★ 缺失即拒绝：默认成 PUBLIC 是一次静默越权，默认成最高档会让内容凭空消失 —— 都不该发生
      throw new IllegalArgumentException("分段密级不能为空（13 §2.1：强制继承所属文档的密级）");
    }
    if (planned == null) {
      throw new IllegalArgumentException("分段计划不能为空");
    }
    if (planned.content() == null || planned.content().isEmpty()) {
      throw new IllegalArgumentException("分段正文不能为空");
    }
    this.id = id;
    this.documentId = documentId;
    this.kbId = kbId;
    this.scopeKind = scopeKind;
    this.scopeId = scopeId;
    this.ownerId = ownerId;
    this.classification = classification;
    this.ordinal = planned.ordinal();
    this.title = clampTitle(planned.title());
    this.titlePath = planned.titlePath();
    // ★ content 是原文，searchText 是前缀 + 正文 —— 两列各有用途（12 §4.2 纪律 1）
    this.content = planned.content();
    this.searchText = planned.searchText();
    this.contentHash = planned.contentHash();
    this.charCount = planned.charCount();
    this.isTable = planned.isTable();
    this.isCode = planned.isCode();
    this.oversizedRisk = planned.oversizedRisk();
    this.enabled = true;
    // ★ 初值 = 待向量化，不是「已成功」—— 向量化是下一周的事
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
    this.createdBy = createdBy;
    this.updatedBy = createdBy;
  }

  // ────────────────────────── 行为 ──────────────────────────

  /**
   * 标题长度上限（{@code 12 §3.4}：无标题块取首个句子截取 ≤256 字）。
   *
   * <p>★ 为什么在这里夹而不是只在分块器里夹：标题来自<strong>三处</strong>（章节标题、无标题块的首句、 表格块的表头首行），任何一处漏夹都会变成一次**插入失败**。
   * 真事故：库列是 {@code varchar(255)}，而分块器的上限是 256 ⇒ 首段稍长的文档一上传就 {@code value too long for type
   * character varying(255)}，整篇建档 500。 夹在<strong>落库的必经之路上</strong>，三处来源就都被覆盖了。
   */
  public static final int MAX_TITLE_LENGTH = 256;

  static String clampTitle(String title) {
    if (title == null) {
      return null;
    }
    return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH);
  }

  /**
   * 编辑正文（{@code PUT /segments/:id}）。
   *
   * <p>★ 内容改了向量就旧了 ⇒ {@code embeddingStatus} 置回 {@code PENDING_EMBEDDING}（契约 #10）。
   *
   * <p>★ {@code searchText} 与 {@code contentHash} 由调用方（应用层）重算后传入 —— 前缀要用该文档的 文种/部门/名，领域层拿不到文档元数据。
   *
   * @param content 新正文（SEG-I1 已在应用层校验）
   * @param searchText 重算后的前缀 + 正文
   * @param contentHash 重算后的哈希（SEG-I7）
   */
  public void updateContent(String content, String searchText, String contentHash, UUID updatedBy) {
    if (content == null || content.isEmpty()) {
      throw new IllegalArgumentException("分段正文不能为空");
    }
    this.content = content;
    this.searchText = searchText;
    this.contentHash = contentHash;
    this.charCount = content.length();
    // ★ 内容变了向量就旧了 —— 必须重新排队（契约 #10）
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
    this.updatedBy = updatedBy;
  }

  /** 改标题（不动正文 ⇒ 向量不用重算）。★ 超长<strong>明确拒绝</strong>：这是用户输入，不该被静默截断。 */
  public void updateTitle(String title, UUID updatedBy) {
    if (title != null && title.length() > MAX_TITLE_LENGTH) {
      throw new IllegalArgumentException(
          "分段标题不能超过 " + MAX_TITLE_LENGTH + " 字（当前 " + title.length() + " 字）");
    }
    this.title = title;
    this.updatedBy = updatedBy;
  }

  // ────────────────────────── 行为：向量化（第 8 周 · 0.3.0） ──────────────────────────

  /**
   * 本段的向量已写入向量表。
   *
   * <p>★ 与 {@code vec_embedding_<维度>} 的一行一一对应（{@code id = segmentId}）。
   * 「写入成功」与「状态已改」是同一件事的两面：只写向量不改状态，重试会重跑全部； 只改状态不写向量，检索会缺内容 —— 两者都看不见。
   */
  public void markEmbedded() {
    this.embeddingStatus = SegmentEmbeddingStatus.EMBEDDED;
  }

  /**
   * 本段向量化失败。
   *
   * <p>★ 失败原因记在<strong>文档级</strong> {@code failReason} 上，分段级只留状态： 一个批次里 3 段超时，可读原因是一样的，逐段复制只会让原因散开。
   * 状态是用来<strong>筛重试范围</strong>的（{@code 03 §3.5}：只重试 {@code embedding_status <> EMBEDDED} 的分段，
   * 代价从分钟级降到秒级）。
   */
  public void markEmbeddingFailed() {
    this.embeddingStatus = SegmentEmbeddingStatus.FAILED;
  }

  /**
   * 退回待向量化（重新向量化时用）。
   *
   * <p>★ 为什么必须显式退回：向量化器只处理 {@code embedding_status <> EMBEDDED} 的分段 （续跑口径，{@code 03 §3.5}）——
   * 不退回的话"重新向量化"会一个分段都不做。
   */
  public void markPendingEmbedding() {
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
  }

  /**
   * 启用/停用这一段（契约 {@code setSegmentEnabled}）。
   *
   * <p>★ 与文档级启停是<strong>两件事、互不替代</strong>：文档停用是整篇不参与检索，分段停用是这一段不参与检索。 只改 {@code enabled} 位，正文与向量都留着
   * —— 重新启用后无需重新向量化。
   */
  public void setEnabled(boolean enabled, UUID updatedBy) {
    this.enabled = enabled;
    this.updatedBy = updatedBy;
  }

  /**
   * 重编文档内序号（集合增删之后用）。
   *
   * <p>★ 为什么必须重编：删掉中间一段、或从中间搬走一段之后，剩余分段的 {@code ordinal} 会留空洞 （0/1/3/4），而 {@code segmentCount} 是 4
   * ⇒ 界面会显示「#0 #1 #3 #4」这种自相矛盾的序号。 序号<strong>不进</strong> {@code contentHash} ⇒ 重编不会让向量过期。
   */
  public void renumber(int ordinal, UUID updatedBy) {
    if (ordinal < 0) {
      throw new IllegalArgumentException("分段序号不能为负");
    }
    this.ordinal = ordinal;
    this.updatedBy = updatedBy;
  }

  /**
   * 搬到另一个文档（契约 {@code batchMoveSegments} 的搬移语义：源删除 + 目标写入）。
   *
   * <p>★ {@code searchText} 与 {@code contentHash} 必须按<strong>目标文档</strong>的 文种/部门/名 重算 （{@code 12
   * §4.2}）：搬过去之后这一段属于另一篇文档，检索文本里写着的却还是原来那篇的名字 —— 与「改名不重写前缀」是同一个坑，只是这次连 {@code kbId} 都可能变。
   *
   * <p>★ <strong>密级也要一起搬</strong>：搬过去之后它属于目标文档，留着源文档的密级就是一次 静默越权
   * ——把一段机密内容搬进公开文档、而它仍带着"机密"是保守的（看不见）；反过来才是漏洞， 而两者只差一个漏写的参数。
   *
   * <p>★ <strong>域同样要一起搬</strong>（{@code V34}，与密级逐字同一条理由）：搬过去之后它属于目标文档，
   * 留着源文档的域就是一次<strong>归属错位</strong> —— 最坏的一种是"会话附件被搬成库文档" （凡能看那个库的人都检索得到），而它不会报错。
   */
  public void moveTo(
      UUID documentId,
      UUID kbId,
      DocumentScopeKind scopeKind,
      UUID scopeId,
      UUID ownerId,
      int ordinal,
      String searchText,
      String contentHash,
      UUID updatedBy,
      ClassificationLevel classification) {
    if (documentId == null || scopeId == null) {
      throw new IllegalArgumentException("目标文档与目标归属主体不能为空");
    }
    if (scopeKind == null) {
      throw new IllegalArgumentException("目标归属种类不能为空（13 §11.3：三域必须显式）");
    }
    if (scopeKind == DocumentScopeKind.KB && kbId == null) {
      throw new IllegalArgumentException("搬进库域的分段必须有知识库（scope_id = kb_id）");
    }
    if (scopeKind == DocumentScopeKind.CONVERSATION && (kbId != null || ownerId == null)) {
      throw new IllegalArgumentException("搬进会话域的分段必须 kb_id 为空且 owner_id 非空");
    }
    if (classification == null) {
      throw new IllegalArgumentException("分段密级不能为空（搬移后它继承目标文档的密级）");
    }
    renumber(ordinal, updatedBy);
    this.documentId = documentId;
    this.kbId = kbId;
    this.scopeKind = scopeKind;
    this.scopeId = scopeId;
    this.ownerId = ownerId;
    this.searchText = searchText;
    this.contentHash = contentHash;
    this.classification = classification;
    // ★ 换了文档、换了前缀 ⇒ 向量旧了，必须重新排队（不回退的话向量化器会跳过它，而状态显示"已完成"）
    this.embeddingStatus = SegmentEmbeddingStatus.PENDING_EMBEDDING;
  }

  /**
   * ★ 重打密级（{@code 13 §2.4} 第 1 步）—— 文档升/降密时，它的全部分段必须跟着走。
   *
   * <p>★ 与 {@link #moveTo} 的关键区别：<strong>向量状态不动</strong>。密级是元数据、不进 embedding （{@code 13 §2.4}
   * 末段），所以重打密级<b>不需要重新向量化</b>；把它顺手标成 {@code PENDING_EMBEDDING} 会让一次纯元数据变更
   * 触发整篇文档的重算，看起来"很保险"，实际是在浪费配额并让检索临时缺文档。
   *
   * <p>★ 为什么这一列必须单独改：{@code kb_segment.classification} 是 {@code V22} 加的<b>冗余列</b>（不是从文档生成的）。
   * 只改文档行会留下"文档=秘密、分段=内部"，而检索谓词用的是<b>分段</b>那一列 ⇒ 低 clearance 用户照样检索得到。
   *
   * @param level 新的密级（继承自所属文档）
   * @throws IllegalArgumentException {@code level} 为 {@code null}
   */
  public void reclassify(ClassificationLevel level) {
    if (level == null) {
      throw new IllegalArgumentException("分段密级不能为空（它继承所属文档的密级，13 §2.1）");
    }
    this.classification = level;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public UUID documentId() {
    return documentId;
  }

  public UUID kbId() {
    return kbId;
  }

  public int ordinal() {
    return ordinal;
  }

  public String title() {
    return title;
  }

  public String titlePath() {
    return titlePath;
  }

  /** 原文片段（不含前缀）。 */
  public String content() {
    return content;
  }

  /** 前缀 + 正文（向量化输入）。 */
  public String searchText() {
    return searchText;
  }

  public String contentHash() {
    return contentHash;
  }

  public int charCount() {
    return charCount;
  }

  public boolean isTable() {
    return isTable;
  }

  public boolean isCode() {
    return isCode;
  }

  public boolean oversizedRisk() {
    return oversizedRisk;
  }

  public boolean enabled() {
    return enabled;
  }

  public SegmentEmbeddingStatus embeddingStatus() {
    return embeddingStatus;
  }

  /** ★ 分段密级（继承所属文档，{@code 13 §2.1} ②）。检索的两条路都用它做密级谓词。 */
  public ClassificationLevel classification() {
    return classification;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
