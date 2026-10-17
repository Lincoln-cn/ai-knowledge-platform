package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 问答对仓储（接口在 {@code domain}，实现在 {@code infrastructure}）。
 *
 * <h3>★ 谓词为什么只有「按库 + 未软删」这一条</h3>
 *
 * <p>本批的用例层入口只有<strong>导入 / 列出</strong>两个，而两者都<strong>先做知识库的可见性判定</strong> （{@code
 * KnowledgeBaseService#get} —— 不可见 ⇒ 404，不泄漏存在性）。所以这里的查询条件只需要按 {@link QaPair#kbId()}
 * 收窄；<strong>数据范围谓词不在这里另拼一份</strong>。
 *
 * <p>★ 这一条是刻意的，而且必须与"将来接入检索时"的口径分开看：
 *
 * <ul>
 *   <li><strong>本批（管理入口）</strong>：可见性由调用方先判一次，与 {@code DocumentService#list}
 *       （「先做库的可见性判定」）是<strong>同一形状</strong>；
 *   <li><strong>将来（检索第 3 路）</strong>：那时候绝不能沿用这个形状 —— 检索侧必须是一条 SQL 里的 pre-filter（可见性 + 密级），{@code 13
 *       §三} 闸门②。★ 也就是说本接口<strong>不是</strong>检索侧 将要用到的那个查询口，别把它当模板抄。
 * </ul>
 *
 * <p>★ {@link #listByKb} 与 {@link #countByKb} 必须共用同一份谓词（同一个 {@code deleted_at is null}
 * 口径）：两处各拼一遍必然漂移，症状是「列表 3 条、total 却是 10」这种没人能解释的分页 （与 {@code KnowledgeBaseRepository#search}/{@code
 * #countSearch} 同一条纪律）。
 */
public interface QaPairRepository {

  /** 批量保存（导入一次写一整批）。 */
  List<QaPair> saveAll(List<QaPair> pairs);

  /**
   * 保存单条（新建 / 改名 / 软删都走它）。
   *
   * <p>★ 与 {@link #saveAll} 并存而不是二选一：批量导入要「一批要么全进」， 单条改名/删除要的是一次写一行 —— 用 saveAll(List.of(x))
   * 表达单条会让调用方 误以为这里也有批次语义。
   *
   * @param pair 问答对
   * @return 落库后的那条
   */
  QaPair save(QaPair pair);

  Optional<QaPair> findById(UUID id);

  /**
   * 按库分页列出（未软删，按创建时间倒序）。
   *
   * @param kbId 归属（所属知识库）—— 调用方必须已判过它的可见性
   */
  List<QaPair> listByKb(UUID kbId, int offset, int limit);

  /** 与 {@link #listByKb} 同条件的总数。★ 过滤之后的数，不能是表行数。 */
  long countByKb(UUID kbId);
}
