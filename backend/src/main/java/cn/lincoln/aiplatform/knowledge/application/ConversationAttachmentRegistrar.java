package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentEmbeddingRepository;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.knowledge.ConversationAttachmentPort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话临时附件的登记实现（{@code #187} 判据 4 · {@code 13 §11.2} 的第三域）。
 *
 * <p>★ 它是 {@link ConversationAttachmentPort} 的<strong>实现</strong>：接口住 {@code platform}（所有模块已依赖
 * platform ⇒ 依赖矩阵零改动），实现住 {@code knowledge} —— 与 {@code platform.assistant.AppBindingPort} 同一条
 * 判据（{@code conversation} 模块的 {@code package-info} 里<u>没有</u> {@code knowledge}）。
 *
 * <h3>★ 为什么解析是同步的</h3>
 *
 * <p>调用方是问答那一轮（{@code ChatController} 在 {@code startTurn} 之后、组装流之前）。用户的心理预期是"我刚传的 这份附件·这次提问就该用上"。走
 * {@code DocumentUploaded} 的异步解析会让<strong>第一次问答必然检索不到</strong>， 而且"检索不到"与"附件里确实没有"在用户眼里完全一样 —— 那是
 * {@code silent-failure}，不是延迟。
 *
 * <p>★ 代价如实记：大附件会把首字延迟往后推（解析 = Tika 抽正文 + 分块 + 落库）。本批选择"确定性优先"，
 * 把"上传即异步解析、问答时等它"留作后续（需要一条"解析中"的前置状态，否则又会静默）。
 *
 * <h3>★ 文种口径（口径 ①）</h3>
 *
 * <p>{@link #DOC_TYPE} 固定为「临时附件」，只为满足 {@code kb_document.doc_type NOT NULL}（V13）—— 文种是建档必填三项
 * 之一，而会话域没有"让用户选文种"的入口。★ <strong>它确实会进召回前缀</strong>（{@code SearchText.prefix} 的第一段）与 {@code
 * content_hash}，不能声称"不进前缀"；准确说法是：会话域<strong>只走全文一路</strong>（{@code
 * RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH = false}，即不产生向量、不参与向量侧的前缀对齐），而这个值是<u>常数</u> ⇒
 * 对排序与命中的影响是常数项。
 *
 * <p>★★ 为什么<strong>不</strong>叫「会话附件」：那四个字归 {@code SourceLabels#CONVERSATION_ATTACHMENT} 独占（引用里的
 * 显示名，且只在 {@code kbId == null} 时补），{@code
 * SourceLabelsTest#theLabelLiteralLivesInExactlyOneMainSourceFile} 钉着"带引号的这个字面量在整棵 {@code
 * src/main/java} 里只许出现一处"。文种与显示名混用会让"值只有一处"变成两处真相 —— 而两处真相互相分叉时不会报错。★
 * 这一版是被那条闸门当场抓住后改的：第一次写的正是「会话附件」，{@code SourceLabelsTest} 红。
 */
@Service
public class ConversationAttachmentRegistrar implements ConversationAttachmentPort {

  /**
   * 会话附件的文种取值（口径 ①）。
   *
   * <p>★ 为什么是中文常量而不是 {@code "ATTACHMENT"}：{@code doc_type} 在本工程里是<strong>面向用户的文种</strong> （合同 / 制度
   * / 报销规定…，见 {@code 12 §5.1}），它会出现在分段前缀与检索结果里。给会话附件一个英文机器码
   * 会让用户在自己的引用里读到一串看不懂的东西；而它又不该被当成"真文种"去参与任何分类统计（本批没有任何统计读它）。
   *
   * <p>★★ 为什么是「临时附件」而不是「会话附件」：后四个字是 {@link
   * cn.lincoln.aiplatform.platform.retrieval.SourceLabels#CONVERSATION_ATTACHMENT}
   * 独占的<u>显示名</u>，{@code SourceLabelsTest} 要求那个带引号的字面量只出现一处；两者混用等于把"值只有一处"变成两处真相（详见类注释）。
   */
  public static final String DOC_TYPE = "临时附件";

  private static final Logger log = LoggerFactory.getLogger(ConversationAttachmentRegistrar.class);

  private final DocumentService documents;
  private final DocumentRepository repository;
  private final SegmentRepository segments;
  private final SegmentEmbeddingRepository segmentEmbeddings;
  private final ParseRunner parser;

  public ConversationAttachmentRegistrar(
      DocumentService documents,
      DocumentRepository repository,
      SegmentRepository segments,
      SegmentEmbeddingRepository segmentEmbeddings,
      ParseRunner parser) {
    this.documents = documents;
    this.repository = repository;
    this.segments = segments;
    this.segmentEmbeddings = segmentEmbeddings;
    this.parser = parser;
  }

  /**
   * 登记 + <strong>同步</strong>解析（返回时 {@code kb_segment} 已落库）。
   *
   * <p>★ 已经有同名登记 ⇒ 直接复用并<strong>不重解析</strong>：让"再问一遍"变成隐式重试，会把一次解析失败（比如 扫描件缺 OCR 引擎）藏起来 ——
   * 每次问答都重跑一遍 Tika，用户只看到"还是没检索到"。
   */
  @Override
  public UUID register(
      UUID conversationId,
      UUID ownerId,
      UUID attachmentId,
      String displayName,
      byte[] content,
      ClassificationLevel classification) {

    // ★ 幂等键（`#299`）：附件 id 非空时按 (conversationId, attachmentId) 幂等，**不按名字顶掉** ——
    //   同会话两份同名不同内容（重传）是两份材料；按名字顶掉第二份会让它的 cv_file 无人引用，
    //   会话彻底删除时那个文件永远清不掉。名字键只兜 attachmentId 为空的旧行。
    Document existing =
        attachmentId != null
            ? repository
                .findConversationAttachmentByAttachmentId(conversationId, attachmentId)
                .orElse(null)
            : repository
                .findConversationAttachment(conversationId, displayName.trim())
                .orElse(null);
    if (existing != null) {
      log.info(
          "会话附件已在会话 {} 里登记过（附件 {} → 文档 {}）⇒ 复用，不重解析", conversationId, attachmentId, existing.id());
      return existing.id();
    }

    UUID documentId =
        documents.registerConversationAttachment(
            conversationId, ownerId, attachmentId, displayName, DOC_TYPE, classification);

    // ★ 同步解析：返回之后这次问答的检索才可能命中它（判据 4 的全部意义就在这一句）
    parser.run(documentId, content, displayName, ownerId);
    log.info(
        "会话附件已进入检索候选：会话 {} · 附件 {} → 文档 {}（{} 字节）",
        conversationId,
        attachmentId,
        documentId,
        content == null ? 0 : content.length);
    return documentId;
  }

  /**
   * 会话彻底删除的库内侧（{@code #299}）：删该会话全部会话域文档行 + 分段 + 向量，报告"孤儿"上传文件。
   *
   * <p>★ 顺序与 {@code DocumentService.purge} 同源（先分段向量后文档行）—— 反序会留孤儿分段，而孤儿分段在检索里照样命中。
   * 引用计数在删完行之后数（{@code countByAttachmentId}）：数到 0 的才进 {@code orphanedAttachmentIds}，调用方拿它去删 {@code
   * cv_file} 与磁盘字节 —— 同一文件跨会话复用时，另一个会话还引用着的不许删。
   */
  @Override
  public PurgeResult purgeConversationAttachments(UUID conversationId) {
    if (conversationId == null) {
      throw new IllegalArgumentException("会话 id 不能为空");
    }
    List<Document> attachments = repository.findConversationAttachments(conversationId);
    int segmentsDeleted = 0;
    int vectorsDeleted = 0;
    Set<UUID> candidateFiles = new LinkedHashSet<>();
    for (Document attachment : attachments) {
      UUID documentId = attachment.id();
      segmentsDeleted += segments.findByDocumentId(documentId).size();
      vectorsDeleted += segmentEmbeddings.countByDocumentId(documentId);
      // ★ 顺序与 DocumentService.purge 同源：先分段与向量、后文档行（反序会留孤儿分段，孤儿分段照样命中检索）
      segments.deleteByDocumentId(documentId);
      segmentEmbeddings.deleteByDocumentId(documentId);
      repository.delete(attachment);
      if (attachment.attachmentId() != null) {
        candidateFiles.add(attachment.attachmentId());
      }
    }
    // ★ 引用计数（#299）：还有别的文档行（任意会话、含软删）引用着的文件不进"可删"清单
    List<UUID> orphanedAttachmentIds =
        candidateFiles.stream()
            .filter(fileId -> repository.countByAttachmentId(fileId) == 0)
            .toList();
    log.info(
        "会话附件清理（会话 {}）：文档 {} 行 · 分段 {} 行 · 向量 {} 行 · 可删文件 {} 个",
        conversationId,
        attachments.size(),
        segmentsDeleted,
        vectorsDeleted,
        orphanedAttachmentIds.size());
    return new PurgeResult(
        attachments.size(), segmentsDeleted, vectorsDeleted, orphanedAttachmentIds);
  }
}
