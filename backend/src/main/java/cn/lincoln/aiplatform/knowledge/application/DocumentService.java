package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档建档（第 5 周 · 0.2.0 批 2）—— 元数据必填 + ceiling 校验。
 *
 * <h3>★ 建档必填三项（{@code 12 §5.1} / {@code DOC-I6}）</h3>
 *
 * <p>文种 / 责任部门 / 密级分别决定「召回前缀」与「安全过滤」，<strong>不能有默认值</strong>。 缺任一 ⇒ 400 且说清缺的是哪一项 ——
 * 笼统的「参数不合法」会让调用方停在原地。
 *
 * <h3>★ ceiling 校验（{@code 13 §6}）</h3>
 *
 * <p>文档密级<strong>高于</strong>库的 {@code maxClassification} ⇒ 拒绝 + 可读原因（含两个密级值）。 等于/低于 ⇒ 放行。
 * 原因必须能照着改：「请提高库的上限，或改用更合适的库」。
 *
 * <h3>★ 可见性继承库</h3>
 *
 * <p>文档<strong>不是</strong> {@code OwnedResource}：谁能看它由所属知识库决定。 判定走批 1 的 {@link
 * KnowledgeBaseService#get}（同一个 {@code accessScope.canSee}）—— <strong>不另写一套</strong>。
 * 两套判定迟早分叉，而分叉点不会报错。
 *
 * <h3>★ 本批不做</h3>
 *
 * <p>解析 / 分块 / 向量化是第 6–7 周的事。本批只负责「把文档建出来、元数据收准」。 建档形态是<strong>最小可用</strong>：按文件名清单建行（真文件暂存与
 * multipart 随上传链补），批次级三项标签一次给定 —— 逐篇覆盖是后续（契约 {@code ImportDocumentsBody} 注释里记了一笔）。
 */
@Service
public class DocumentService {

  private static final int MAX_PAGE_SIZE = 100;

  private final DocumentRepository documents;
  private final KnowledgeBaseService knowledgeBases;
  private final cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser parser;

  public DocumentService(
      DocumentRepository documents,
      KnowledgeBaseService knowledgeBases,
      cn.lincoln.aiplatform.knowledge.infrastructure.TikaMarkdownParser parser) {
    this.documents = documents;
    this.knowledgeBases = knowledgeBases;
    this.parser = parser;
  }

  // ────────────────────────── 查询 ──────────────────────────

  /**
   * 按库分页列文档。★ 库不可见 ⇒ <strong>404</strong>（继承库的可见性，与批 1 同一判定）。
   *
   * <p>★ {@code total} 是本库过滤之后的数，与页内条数同口径。
   */
  @Transactional(readOnly = true)
  public PageResult<DocumentView> list(UUID kbId, Integer page, Integer size) {
    // ★ 先做库的可见性判定：库不可见 ⇒ 其文档也不可见（读 404）
    knowledgeBases.get(kbId);
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    List<Document> found = documents.findByKbId(kbId, (safePage - 1) * safeSize, safeSize);
    long total = documents.countByKbId(kbId);
    return PageResult.of(
        found.stream().map(DocumentView::from).toList(), total, safePage, safeSize);
  }

  // ────────────────────────── 建档 ──────────────────────────

  /**
   * 建档（最小可用形态：按文件名清单建行）。
   *
   * <p>★ 三项必填 + ceiling 校验见类注释。 任一缺失 ⇒ 400 并指出是哪一项；密级超 ceiling ⇒ 400 并给出可读原因。
   *
   * <p>★ 库不可见 ⇒ <strong>404</strong>（写也一样）—— 与 {@link #list} 同一判定。
   *
   * @param names 文件名清单（一批文档的显示名）；空清单 ⇒ 400
   * @param contents 与 {@code names} 等长的文件字节（multipart 上传时给）；null 或不给 ⇒ 只登记元数据，不解析
   * @param docType 文种（批次级）★ 必填
   * @param department 责任部门（批次级）★ 必填
   * @param classification 密级（批次级）★ 必填
   */
  @Transactional
  public List<DocumentView> register(
      UUID kbId,
      List<String> names,
      List<byte[]> contents,
      String docType,
      String department,
      ClassificationLevel classification,
      UUID currentUserId) {

    // ★ 1 · 可见性继承库（写也 404）
    KnowledgeBaseView base = knowledgeBases.get(kbId);

    // ★ 2 · 建档必填三项 —— 逐项说清缺的是哪个
    if (docType == null || docType.isBlank()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "文种必填", "选择这份文档的文种（合同/制度/报销规定…）—— 它决定召回前缀，不能有默认值（12 §5.1）");
    }
    if (department == null || department.isBlank()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "责任部门必填", "填写这份文档的责任部门 —— 它决定召回前缀，不能有默认值（12 §5.1）");
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
              department,
              classification,
              currentUserId);
      // ★ 带了文件字节就**建档即解析**：成功 ⇒ 待向量化 + parsedContent；
      //   失败 ⇒ 分段失败 + 可读原因 + 已缓冲部分（12 §2.1）。不给字节 ⇒ 只登记元数据。
      if (contents != null && i < contents.size() && contents.get(i) != null) {
        var parsed = parser.parse(contents.get(i), name);
        if (parsed.success()) {
          document.recordParseSuccess(parsed.markdown());
        } else {
          document.recordParseFailure(parsed.failReason(), "修复文件后重新上传，或改用其它格式。", parsed.markdown());
        }
      }
      created.add(DocumentView.from(documents.save(document)));
    }
    return created;
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
