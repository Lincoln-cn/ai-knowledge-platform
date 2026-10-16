package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <p>★ 列表查询把「所属库 + 软删」拼进查询，{@code total} 与页内条数<strong>同口径</strong> —— 与 {@code
 * KnowledgeBaseRepository} 同一条纪律（{@code 08 §4.4.3}）。
 *
 * <p>★ 本批不按 dataScope 筛文档：文档的可见性<strong>继承库</strong>（库不可见 ⇒ 文档不可见）， 所以范围判定发生在「找到库」那一步，不在文档列表上再写一遍。
 */
public interface DocumentRepository {

  Optional<Document> findById(UUID id);

  Document save(Document document);

  /**
   * 按库分页查文档（创建时间倒序）。★ 软删的不进列表。
   *
   * <p>★★ {@code orgFilter} 是 {@code #56} 的出口：{@link OrgAssignment#UNASSIGNED} 把
   * "回填时匹配不上责任部门"的文档列出来（它们 {@code org_id} 为空、原值留在 {@link Document#departmentLegacy()}）。★
   * 三态而不是布尔：调用点上能读出意图。
   *
   * @param kbId 知识库
   * @param offset 偏移（0 起）
   * @param limit 条数
   * @param orgFilter 归属过滤（{@link OrgAssignment#ANY} = 不过滤，与加这一档之前的行为一致）
   */
  List<Document> findByKbId(UUID kbId, int offset, int limit, OrgAssignment orgFilter);

  /**
   * 按库查<strong>全部</strong>文档（不分页）。★ 给「库级重新向量化」这类要遍历整库的动作用。
   *
   * <p>★ 为什么另开一个方法而不是「用一个很大的 limit 调 {@link #findByKbId}」：那样在文档数超过那个 limit 时
   * 会<strong>静默漏掉剩余文档</strong>，而回执上的数字看起来很正常（"已排队 100 篇"）。 「静默截断」在本项目是一条明确的禁令（{@code SEG-I1} 同族）。
   */
  List<Document> findAllByKbId(UUID kbId);

  /**
   * 与 {@link #findByKbId} 同条件的总数。★ 过滤之后的数，不能是表行数。
   *
   * <p>★ 必须与列表<strong>同一份谓词、同一个 orgFilter</strong>：两处各拼一遍会出现 "页里有 3 条、total 说 7 条"那种没人会怀疑的读数（{@code
   * 08 §4.4.3} 纪律 1）。
   */
  long countByKbId(UUID kbId, OrgAssignment orgFilter);

  /**
   * 按状态分组计数（库级进度聚合，{@code 03 §3.4}）。
   *
   * <p>★ 聚合必须发生在<strong>查询里</strong>：把文档取回来在内存里数，会在分页/上限处静默算错 （页大小 100、库里 340 篇 ⇒ 进度条永远停在
   * 100/340，而没人会怀疑那个数字）。
   *
   * @param kbId 知识库
   * @return 状态 → 篇数（不含软删文档；某状态没有文档时该键缺席）
   */
  Map<DocumentStatus, Long> countByStatus(UUID kbId);
}
