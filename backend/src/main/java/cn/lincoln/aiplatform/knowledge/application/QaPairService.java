package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.QaPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin;
import cn.lincoln.aiplatform.knowledge.domain.QaPairRepository;
import cn.lincoln.aiplatform.knowledge.domain.Segment;
import cn.lincoln.aiplatform.knowledge.domain.SegmentRepository;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问答对用例层（Issue {@code #78} 数据层留缝 · 第 16 周）—— <strong>只有两个方法</strong>：导入 / 列出。
 *
 * <h3>★★ 这个类的形状是"刻意最小"，读之前先读这一条</h3>
 *
 * <p>{@code #78} 拍的是「<strong>数据层留缝</strong>」：只建表 + 服务层最小入口（导入 / 列出），
 * <strong>检索侧一行不接</strong>，而且<strong>不加 HTTP 端点、不改契约</strong>。
 *
 * <p>★ 为什么不加端点的理由（这是本批最容易做错的一步）：契约闸门 {@code ContractDriftTest} 是 <strong>双向</strong>的 —— "后端有、契约没有
 * ⇒ 直接失败"；而把端点登记进契约之后，没有任何页面消费它， 就变成"零消费"的三缺一（{@code #79} 扫的正是这个）。<strong>后端有 &gt; 契约有 &gt;
 * 页面消费</strong> 三格里的空格，比"这里还缺个接口"更难被发现。所以本批的"最小管理入口" = <strong>Java 服务层的方法</strong>。
 *
 * <h3>★ 两个方法各自必须做的判定（少一个就是静默出错）</h3>
 *
 * <ol>
 *   <li><strong>可见性</strong>：两个方法都先走 {@link KnowledgeBaseService#get}（库不可见 / 不存在 ⇒
 *       <strong>404</strong>，不是 403 —— 403 等于确认"这个 id 存在"，是可被枚举的信息泄漏，与 {@code KnowledgeBaseService}
 *       三条纪律的第 2 条同口径）。
 *   <li><strong>ceiling</strong>：问答对密级必须 ≤ 库的 {@code maxClassification}（{@code 13 §6}）——
 *       与文档建档（{@code DocumentService#register}）<strong>同一条校验</strong>。★ 少了它，一个"秘密"级的
 *       问答对可以进一个「最高只允许公开」的库，而它<strong>将来是要进检索的</strong> ⇒ 那是一次跨密级越权。
 *   <li><strong>归属一致</strong>：{@code segmentId} 有值时，那一段必须真的属于这个库。★ 归属错位的问答对
 *       将来会<strong>静默</strong>污染检索（拿 A 库的问题去 B 库的语料里作答），而它在库里长得完全正常。
 * </ol>
 *
 * <h3>★ 明确没做的（避免被读成"已经接入"）</h3>
 *
 * <ul>
 *   <li><strong>没有任何检索侧调用</strong>：{@code RetrievalService} / {@code JdbcSegmentSearchQuery}
 *       一个字都没动（判据 ③ 的断言钉的就是这件事）；
 *   <li><strong>没有删除 / 更新入口</strong>：领域层连 {@code markDeleted} 都不提供 —— 一个没有调用方的领域方法
 *       会被下一个人读成"删除功能已经在"；
 *   <li><strong>没有命中回写</strong>：{@link QaPair#hitCount()} 恒 0（只有检索能写它）。
 * </ul>
 */
@Service
public class QaPairService {

  private static final int MAX_PAGE_SIZE = 100;

  private final QaPairRepository pairs;
  private final SegmentRepository segments;
  private final KnowledgeBaseService knowledgeBases;

  public QaPairService(
      QaPairRepository pairs, SegmentRepository segments, KnowledgeBaseService knowledgeBases) {
    this.pairs = pairs;
    this.segments = segments;
    this.knowledgeBases = knowledgeBases;
  }

  // ────────────────────────── 导入 ──────────────────────────

  /**
   * 导入一批问答对（第 16 周 · 0.4.4 留缝）。
   *
   * <p>★ 整个批次在<strong>一个事务</strong>里：一批导入要么全进、要么一条都不进。半批成功会让调用方 无法判断"重试会不会造出重复行" ——
   * 而问答对没有业务唯一键（同一个人可以合法地问两次同一个问题）， 所以重试的代价是<strong>静默的重复数据</strong>，不是一次报错。
   *
   * <p>★ <strong>不做</strong>"尽力而为 + 逐条回执"（{@code SegmentService#batchDelete} 是那个形状）：
   * 那是给"用户勾了一屏、其中一条已被别人删掉"的场景用的。这里是<strong>程序化导入</strong>， 半批成功等于把判断责任推给调用方，而调用方手里没有逐条原因。
   *
   * @param kbId 归属（所属知识库）★ 必填 —— 不可见 / 不存在 ⇒ 404
   * @param items 要导入的问答对 ★ 空清单 ⇒ 400（"什么都不做"不该返回成功）
   * @param currentUserId 建档人（由 controller 从 JWT 的 {@code sub} 取；服务层不接受前端传）
   * @return 落库后的那些行（按传入顺序），供调用方核对写入结果
   * @throws BusinessException 库不可见（404）· 空清单 / 三项必填缺失 / 密级越 ceiling / 出处不属于该库（400）
   */
  @Transactional
  public List<QaPairView> importPairs(UUID kbId, List<ImportPair> items, UUID currentUserId) {

    // ★ 1 · 可见性继承库（写也 404，不泄漏存在性）—— 与 DocumentService#register 第一步同一判定
    KnowledgeBaseView base = knowledgeBases.get(kbId);

    if (items == null || items.isEmpty()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "没有要导入的问答对",
          "传入至少一条 (question, answer) 再提交 —— 空清单成功返回会让调用方以为写进去了");
    }

    // ★ 2 · 静态校验全走完再落库：任何一条不合格都不许留下半批
    //   日期前缀的三项（问题 / 答案 / 密级）在领域构造器里还有一道 —— 库层的 NOT NULL 是最后一道闸
    ClassificationLevel ceiling = base.maxClassification();
    for (int index = 0; index < items.size(); index++) {
      ImportPair item = items.get(index);
      if (item == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "第 " + (index + 1) + " 条问答对为空");
      }
      if (item.question() == null || item.question().isBlank()) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "第 " + (index + 1) + " 条缺少问题", "问题和答案都必填 —— 半条问答对参与不了召回");
      }
      if (item.answer() == null || item.answer().isBlank()) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "第 " + (index + 1) + " 条缺少答案", "问题和答案都必填 —— 半条问答对参与不了召回");
      }
      if (item.origin() == null) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "第 " + (index + 1) + " 条缺少来源",
            "来源（会话沉淀 / 赞踩沉淀 / 人工导入）决定这条凭什么可信、被质疑时怎么追溯，不能有默认值");
      }
      // ★ 密级缺省**不取库的上限**：那会把"忘传"变成"按库上限放行"，而库上限通常是较高的那一档
      if (item.classification() == null) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "第 " + (index + 1) + " 条缺少密级",
            "问答对将来要进检索 ⇒ 密级是它的安全过滤依据，不能有默认值（13 §6.3）");
      }
      // ★ 与文档建档同一条 ceiling 校验（13 §6）
      if (!item.classification().within(ceiling)) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "第 %d 条问答对的密级（%s）高于知识库的密级上限（%s）—— 请提高库的上限，或改用更合适的库"
                .formatted(index + 1, item.classification(), ceiling),
            "库的密级上限是入库校验的依据（13 §6）：只有不高于上限的内容才能进这个库");
      }
    }

    // ★ 3 · 归属一致：出处（segmentId）必须真的属于这个库。
    //   一次批量取（不是每条一次查询）—— 而"取回来再比对 kbId"是唯一能判这件事的地方：
    //   kb_segment 的归属列就是 kb_id，找不到 / kbId 不等都是同一种拒绝。
    verifySegmentOwnership(items, kbId);

    List<QaPair> toSave = new ArrayList<>(items.size());
    for (ImportPair item : items) {
      try {
        toSave.add(
            new QaPair(
                UUID.randomUUID(),
                kbId,
                item.question(),
                item.answer(),
                item.segmentId(),
                item.classification(),
                item.origin(),
                item.sourceRef(),
                currentUserId));
      } catch (IllegalArgumentException e) {
        // 领域层是给人看的那道闸（库层是最后一道）：把它的中文原因原样带出来
        throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
      }
    }
    return pairs.saveAll(toSave).stream().map(QaPairView::from).toList();
  }

  // ────────────────────────── 列出 ──────────────────────────

  /**
   * 按库分页列问答对（第 16 周 · 0.4.4 留缝）。
   *
   * <p>★ 库不可见 / 不存在 ⇒ <strong>404</strong>（与 {@link #importPairs} 同一判定、同一个 404）——
   * 而不是"空列表"：空列表会被读成"这个库还没有问答对"，两者是不同的结论。
   *
   * <p>★ {@code total} 是过滤<strong>之后</strong>的数（与页内条数同口径）：只查页、不查总数时界面上的 "共 N
   * 条"只能靠猜，而那是本项目的既有口径（{@code 08 §4.4.3} 纪律 1）。
   */
  @Transactional(readOnly = true)
  public PageResult<QaPairView> list(UUID kbId, Integer page, Integer size) {
    knowledgeBases.get(kbId);
    int safeSize = size == null ? 20 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int safePage = page == null ? 1 : Math.max(1, page);
    List<QaPair> found = pairs.listByKb(kbId, (safePage - 1) * safeSize, safeSize);
    long total = pairs.countByKb(kbId);
    return PageResult.of(found.stream().map(QaPairView::from).toList(), total, safePage, safeSize);
  }

  // ────────────────────────── 建 / 改名 / 删（FR-03-12 的 web 入口）──────────────────────────

  /**
   * 新建一条问答对（需求 {@code FR-03-12-1} 的单条形态）。
   *
   * <p>与 {@link #importPairs} 的关系：<strong>同一条落库规则，不同的入口</strong> ——
   * 批量导入的语义是"全进或全不进"，而这里就是一条，所以校验失败只有一条要解释。
   *
   * <p>★ 来源固定为 {@link QaPairOrigin#MANUAL}：从 web 手输的必然是人工导入。 会话沉淀（{@code CONVERSATION}）/
   * 赞踩沉淀（{@code FEEDBACK}）由各自那条链写入， 让调用方传来源等于允许"人手写的一条自称来自会话" —— 那会让追溯失效。
   *
   * @param kbId 归属（不可见 ⇒ 404）
   * @param question 问题 ★ 必填
   * @param answer 答案 ★ 必填
   * @param classification 密级 ★ 必填且不得高于库上限
   * @param segmentId 出处（可空；有值时校验它属于 {@code kbId}）
   * @param currentUserId 建档人（controller 从 JWT 取）
   * @return 落库后的那条
   */
  @Transactional
  public QaPairView create(
      UUID kbId,
      String question,
      String answer,
      ClassificationLevel classification,
      UUID segmentId,
      UUID currentUserId) {
    KnowledgeBaseView base = knowledgeBases.get(kbId);
    requireClassificationWithin(classification, base.maxClassification(), "问答对");
    ImportPair item =
        new ImportPair(question, answer, segmentId, classification, QaPairOrigin.MANUAL, null);
    verifySegmentOwnership(List.of(item), kbId);
    QaPair saved =
        pairs.save(
            new QaPair(
                UUID.randomUUID(),
                kbId,
                item.question(),
                item.answer(),
                item.segmentId(),
                item.classification(),
                item.origin(),
                item.sourceRef(),
                currentUserId));
    return QaPairView.from(saved);
  }

  /**
   * 改问题（需求 {@code FR-03-12-2} 的行内改名）。
   *
   * @param pairId 问答对 id（不存在 / 已删 / 库不可见 ⇒ 404，同一段文案）
   * @param newQuestion 新问题 ★ 空白 ⇒ 400
   * @param currentUserId 操作人
   * @return 落库后的那条
   */
  @Transactional
  public QaPairView rename(UUID pairId, String newQuestion, UUID currentUserId) {
    QaPair pair = requireVisible(pairId);
    try {
      pair.rename(newQuestion, currentUserId);
    } catch (IllegalArgumentException invalid) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, invalid.getMessage(), "改成一个非空的问题再提交");
    }
    return QaPairView.from(pairs.save(pair));
  }

  /**
   * 删除问答对（需求 {@code FR-03-12-3}）—— <strong>软删</strong>，且幂等。
   *
   * @param pairId 问答对 id（不存在 / 库不可见 ⇒ 404；已删 ⇒ 幂等返回）
   * @param currentUserId 操作人
   */
  @Transactional
  public void delete(UUID pairId, UUID currentUserId) {
    // ★★ 这里**不能**用 requireVisible：它对"已软删"也抛 404，而删除必须**幂等**
    //   （删两次不报错、且 deleted_at 不被刷新）。两者冲突 —— 本条判据
    //   `deleteIsSoftAndIdempotent` 当场抓到：第二次 delete 抛了"问答对不存在"。
    //   ⇒ 单独一条取数路径：只判「存在 + 库可见」，不判 deleted。
    QaPair pair =
        pairs
            .findById(pairId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "问答对不存在", "它可能已被删除，或者不在你的可见范围内"));
    // ★ 归属的权威仍是**知识库**（问答对没有独立可见性，见 V31 注释）
    knowledgeBases.get(pair.kbId());
    if (pair.deleted()) {
      return;
    }
    pair.softDelete(currentUserId);
    pairs.save(pair);
  }

  /**
   * 取一条可见的问答对。
   *
   * <p>★ "不存在"、"已软删"、"所属知识库不可见" 三者<strong>同一段文案</strong>： 与 {@code DocumentService} / {@code
   * SegmentService} 逐字同一口径 —— 分辨它们等于确认了 id 的存在性。
   */
  private QaPair requireVisible(UUID pairId) {
    QaPair pair =
        pairs
            .findById(pairId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "问答对不存在", "它可能已被删除，或者不在你的可见范围内"));
    if (pair.deleted()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "问答对不存在", "它可能已被删除，或者不在你的可见范围内");
    }
    // ★ 归属的权威是**知识库**（问答对没有独立可见性，见 V31 注释）
    knowledgeBases.get(pair.kbId());
    return pair;
  }

  /**
   * 密级不得高于知识库上限（`13 §6` 的 ceiling 口径）。
   *
   * <p>★ 抽成一个方法而不是在三个入口各写一遍：这是**同一条安全规则**， 复制三次之后必然有一处写漏，而写漏的那一处是静默的越权。
   */
  private void requireClassificationWithin(
      ClassificationLevel classification, ClassificationLevel ceiling, String what) {
    if (classification == null) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, what + "缺少密级", "问答对将来要进检索 ⇒ 密级是它的安全过滤依据，不能有默认值（13 §6.3）");
    }
    if (!classification.within(ceiling)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          what + "的密级 " + classification + " 高于所属知识库的上限 " + ceiling,
          "密级要跟随所属知识库的上限（13 §6）—— 请降低密级，或提高该库的密级上限");
    }
  }

  // ────────────────────────── 内部 ──────────────────────────

  /**
   * 校验所有出处（{@code segmentId}）都真的属于 {@code kbId}。
   *
   * <p>★ 为什么这一条必须做，而不是"将来接入时再说"：出处的用途是「这条问答对指向哪一段原文」。归属错位时 <strong>没有任何地方会报错</strong> ——
   * 库里的行长得完全正常，症状要等到检索接入后才出现，而且看起来 像"检索串了库"。★ 与 {@code SegmentService#batchMove} 里"密级要跟着搬"是同一条理由：
   * 冗余的归属列一旦与实际不符，就是一次<strong>静默</strong>的越权 / 串库。
   *
   * <p>★ 失败原因<strong>逐类说清</strong>（不存在 vs 属于别的库）：两者的处置不同 —— 前者多半是 id 写错，
   * 后者是拿错了库。合并成一句"出处不合法"会让调用方停在原地。
   */
  private void verifySegmentOwnership(List<ImportPair> items, UUID kbId) {
    Set<UUID> referenced = new HashSet<>();
    for (ImportPair item : items) {
      if (item.segmentId() != null) {
        referenced.add(item.segmentId());
      }
    }
    if (referenced.isEmpty()) {
      return;
    }
    Map<UUID, UUID> segmentKb = new LinkedHashMap<>();
    for (Segment segment : segments.findAllById(List.copyOf(referenced))) {
      segmentKb.put(segment.id(), segment.kbId());
    }
    for (UUID id : referenced) {
      UUID actualKb = segmentKb.get(id);
      if (actualKb == null) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST, "出处分段不存在：" + id, "它可能已被删除（文档被删时分段会一起走）—— 请去掉这条出处，或改指向现存的段落");
      }
      if (!actualKb.equals(kbId)) {
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "出处分段 %s 属于另一个知识库（%s），不属于 %s".formatted(id, actualKb, kbId),
            "★ 归属错位不会在任何地方报错，却会污染检索（拿一个库的问题去另一个库的语料里作答）—— 请改指本库内的段落");
      }
    }
  }

  // ────────────────────────── 命令 ──────────────────────────

  /**
   * 一条待导入的问答对（{@link #importPairs} 的入参形态）。
   *
   * <p>★ 密级与来源是<strong>逐条</strong>的，不是批次级的：文档建档可以整批一个密级（同一批上传的通常是同一类文件）， 而问答对的来源天然是混的 ——
   * 一次导入里可以既有"人工导入"的、也有从会话里沉淀出来的， 而它们的密级取决于各自指向的那段原文<strong>可能不同</strong>。
   *
   * @param question 问题 ★ 必填
   * @param answer 答案 ★ 必填
   * @param segmentId 命中来源（可空；★ 有值时必须属于同一个 {@code kbId}）
   * @param classification 密级 ★ 必填，且必须 ≤ 库的 {@code maxClassification}
   * @param origin 来源 ★ 必填
   * @param sourceRef 来源定位串（会话消息 id / 反馈 id / 导入批次号；可空）
   */
  public record ImportPair(
      String question,
      String answer,
      UUID segmentId,
      ClassificationLevel classification,
      QaPairOrigin origin,
      String sourceRef) {}
}
