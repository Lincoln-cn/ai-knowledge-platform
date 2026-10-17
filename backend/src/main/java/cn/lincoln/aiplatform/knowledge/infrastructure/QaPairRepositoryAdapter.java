package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.QaPair;
import cn.lincoln.aiplatform.knowledge.domain.QaPairRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Data 的问答对仓储适配成 domain 的 {@link QaPairRepository}。
 *
 * <p>★ 存在的理由与其它适配器相同：<strong>框架类型不许进领域层</strong>。领域接口用 offset/limit 表达查询意图， Spring Data 要的是 {@code
 * Pageable} / {@code Specification}。
 *
 * <p>★ <strong>没有数据范围谓词</strong>是刻意的，理由写在 {@link QaPairRepository} 的类注释里： 本批两个入口（导入 /
 * 列出）都先做知识库的可见性判定，形状与 {@code DocumentService#list} 一致； 而<strong>将来接入检索时绝不能沿用这个形状</strong> —— 那必须是
 * pre-filter（可见性 + 密级一条 SQL）。
 */
@Component
public class QaPairRepositoryAdapter implements QaPairRepository {

  private final JpaQaPairRepository jpa;

  public QaPairRepositoryAdapter(JpaQaPairRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public QaPair save(QaPair pair) {
    return jpa.save(pair);
  }

  @Override
  public List<QaPair> saveAll(List<QaPair> pairs) {
    return jpa.saveAll(pairs);
  }

  @Override
  public Optional<QaPair> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public List<QaPair> listByKb(UUID kbId, int offset, int limit) {
    // limit 恒 > 0（调用方已归一化），所以这里的整除是安全的。
    // ★ 排序取「创建时间倒序 + id 兜底」：只按 created_at 排时，同一毫秒导入的整批会得到一个
    //   不稳定的相对次序，而"刚导入的那三条"正是分页最容易翻车的形状（第 2 页可能重复出现第 1 页的行）。
    return jpa.findAll(
            live(kbId),
            PageRequest.of(
                offset / limit,
                limit,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"))))
        .getContent();
  }

  @Override
  public long countByKb(UUID kbId) {
    // ★ 与 listByKb 共用同一份谓词（live）—— 两处各拼一遍必然漂移（见领域接口的类注释）
    return jpa.count(live(kbId));
  }

  /**
   * 唯一的谓词来源：按库 + 未软删。
   *
   * <p>★ 未软删是<strong>恒定</strong>谓词（与 {@code KnowledgeBaseRepositoryAdapter.filter} 同一条纪律）：
   * 软删过的问答对不该再被任何列表看见 —— 它已经不是这套机制的材料了。
   */
  private static Specification<QaPair> live(UUID kbId) {
    return (root, query, cb) ->
        cb.and(cb.equal(root.get("kbId"), kbId), cb.isNull(root.get("deletedAt")));
  }
}
