package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.UUID;

/**
 * 实体库的写端口（对应表 {@code kb_entity} / {@code kb_entity_alias}）。
 *
 * <p>★ 只有"写"与"按名字读"——查询侧（按别名找实体、扩召回）等 0.6.2 再接。 今天这个端口存在的唯一理由是**让 {@code kb_entity} 不再是一张没人写的表**：
 * `b664259` 建了表、`e3f7d49` 让字典能读，但没有任何代码往里写，那是本项目反复记账的 同一个形状（"留了缝，缝后面是空的"）。
 *
 * <p>★ 写的方法全部 **幂等**（{@code on conflict do nothing}）：一段文档被重新解析时会再抽 一遍，同库同名实体不该因此变成两行 ——
 * 也不能靠调用方"记得先查一下"。
 */
public interface EntityRepository {

  /**
   * 记一批实体。已存在的（同 {@code kb_id} + 同 {@code name} + 同 {@code type}）跳过。
   *
   * @return 真正新插入的条数（0 表示这一批全都在库里）
   */
  int insertAll(UUID kbId, List<Candidate> candidates);

  /** 按名字找实体 id（同库同名同类型）。找不到返回 {@code Optional.empty()}。 */
  java.util.Optional<UUID> findIdByName(UUID kbId, String type, String name);

  /** 一个库里的实体数（给探针与测试用）。 */
  long countOf(UUID kbId);

  /** 一条待写入的实体。 */
  record Candidate(String type, String name, String source, double confidence) {}
}
