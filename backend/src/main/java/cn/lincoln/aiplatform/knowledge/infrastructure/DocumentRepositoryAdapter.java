package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.access.ScopeFilter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的文档仓储适配成 domain 的 {@link DocumentRepository}。
 *
 * <p>★ 存在的理由与 {@code KnowledgeBaseRepositoryAdapter} 相同：框架类型不进领域层。
 *
 * <p>★ 「软删过滤」是恒定谓词；「按库过滤」是本批列表的主谓词。 total 与列表共用同一份谓词（{@code 08 §4.4.3} 纪律 1）。
 *
 * <h3>★★ 2026-10-16：密级谓词也收进这同一份判据（{@code #25}）</h3>
 *
 * <p>修之前列表侧只有"软删 + 所属库"两个条件 ⇒ 低 clearance 的用户**能数出**自己看不到的文档， 而且看得到它们的标题与密级本身 —— {@code 13 §5.1} 规则
 * 2 把"计数"明确列为<strong>存在性侧信道</strong> （{@code §6.2} 也写着"不显示『该库含 N 份你看不到的内容』"）。
 *
 * <p>★ 处置不是"在列表那一处补一个 where"：密级与数据范围是<strong>同一个谓词构建器</strong>消费的两条轴 （{@code ScopeFilter}
 * 的类注释逐字），所以这里读的是同一个 {@link AccessScopePort#currentScope()}， 并且<strong>列表 / 计数 /
 * 进度分母三处共用</strong>这份 {@link #filter}。
 *
 * <p>★ <strong>fail-closed</strong>：取不到身份（{@code maxClassification == null}，即 {@code nobody()}）时
 * 上限为空 ⇒ 与 {@code classificationRank <= null} 比较<strong>恒假</strong> ⇒ 一行都不返回。
 * 这与检索侧"取不到身份就一条引用都不给"是同一条口径；反过来（把 null 当成"没有上限"）会让一次身份解析失败 变成"看得见全部"。
 */
@Component
public class DocumentRepositoryAdapter implements DocumentRepository {

  private final JpaDocumentRepository jpa;
  private final AccessScopePort accessScope;

  public DocumentRepositoryAdapter(JpaDocumentRepository jpa, AccessScopePort accessScope) {
    this.jpa = jpa;
    this.accessScope = accessScope;
  }

  @Override
  public Optional<Document> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public Document save(Document document) {
    return jpa.save(document);
  }

  @Override
  public List<Document> findByKbId(UUID kbId, int offset, int limit) {
    // 领域给 offset/limit；Spring Data 要「第几页、每页几条」。limit 恒 > 0（调用方已归一化）
    return jpa.findAll(filter(kbId), PageRequest.of(offset / limit, limit, sort())).getContent();
  }

  @Override
  public long countByKbId(UUID kbId) {
    // ★ 与 findByKbId 共用同一份谓词 —— 两处各拼一遍必然漂移
    return jpa.count(filter(kbId));
  }

  @Override
  public List<Document> findAllByKbId(UUID kbId) {
    return jpa.findAll(filter(kbId), sort());
  }

  @Override
  public Map<DocumentStatus, Long> countByStatus(UUID kbId) {
    // ★ 分组计数在库里做（见 JpaDocumentRepository 的注释）：取回来再数会在分页上限处静默算错
    // ★★ 密级条件与列表**同一条**（#25 判据 3：进度条分母与列表口径必须一致）
    Map<DocumentStatus, Long> counts = new EnumMap<>(DocumentStatus.class);
    for (Object[] row : jpa.countGroupedByStatus(kbId, ceilingRank())) {
      counts.put((DocumentStatus) row[0], ((Number) row[1]).longValue());
    }
    return counts;
  }

  // ────────────────────────── 内部 ──────────────────────────

  private static Sort sort() {
    return Sort.by(Sort.Direction.DESC, "createdAt");
  }

  /**
   * ★★ 列表 / 计数 / 进度分母<strong>唯一</strong>的谓词来源：软删 + 所属库 + <strong>密级上限</strong>（{@code #25}）。
   *
   * <p>★ 为什么三处必须共用它：{@code total} 与 {@code list} 各拼一遍谓词，就会出现"页里有 3 条、 total 说 7 条"那种没人会怀疑的读数（{@code
   * 08 §4.4.3} 纪律 1 的原话）。
   *
   * <p>★ 密级那一轴读的是当前范围的上限（{@code ScopeFilter#maxClassification}），绑定值是它的 {@link
   * ClassificationLevel#ordinal()} —— 与检索侧 {@code s.classification_rank <= ?} <strong>同源</strong>。
   * 上限为 {@code null} ⇒ 比较恒假 ⇒ fail-closed。
   */
  private Specification<Document> filter(UUID kbId) {
    Short ceiling = ceilingRank();
    return (root, query, cb) ->
        cb.and(
            cb.isNull(root.get("deletedAt")),
            cb.equal(root.get("kbId"), kbId),
            // ★ 传 null 时 `<=` 恒假（SQL 三值逻辑），无需再写一条分支 —— 这正是 fail-closed 的载体
            cb.lessThanOrEqualTo(root.get("classificationRank"), ceiling));
  }

  /**
   * 当前请求的密级上限（{@code = ClassificationLevel.ordinal()}）；<strong>取不到身份 ⇒ {@code null}</strong>
   * （于是上面那条比较恒假：什么都不返回，而不是"什么都返回"）。
   */
  private Short ceilingRank() {
    ScopeFilter scope = accessScope.currentScope();
    ClassificationLevel ceiling = scope == null ? null : scope.maxClassification();
    return ceiling == null ? null : (short) ceiling.ordinal();
  }
}
