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

  /**
   * 列一个库里的实体（**只看未合并、未删除的**）。
   *
   * <p>★ 为什么现在就要这个方法：`kb_entity` 到 `7a0600a` 为止只有「写」和「按名读」， 抽取到底抽到了什么，除了直接连库看以外没有任何途径 ——
   * 那等于这一步仍然是黑箱。 0.6.2 的扩召回也要从这里取候选。
   *
   * <p>★ 过滤条件写死在这里（而不是让调用方传参）：`merged_into is not null` 的行是「已经被合到 别的实体上」的，列出来会让同一个名字出现两次。
   */
  List<Entity> listOf(UUID kbId);

  /**
   * 分页列实体（界面上「实体」页签用）。
   *
   * <p>★ 为什么不能像以前那样一次取全量：真实语料库里一个 20 篇法规的库能抽出 1400+ 个实体，
   * 一次全取再在界面上切页，等于把「库里有多少」变成了「浏览器一次能吃多少」。分页必须在库里做。
   *
   * <p>★ 与 {@link #listOf} 的分工：那个是「一个库的实体全量」，消费方是图谱与统计（要有全貌才能算
   * 精度/去重）；这个是「翻到第几页」，消费方是表格。两者都存在是因为它们的上限不同 —— 一个要求完整、一个要求有界。
   *
   * @param kbId 知识库 id（{@code null} 返回空列表）
   * @param type 只看某一类（{@code null} 或空串表示不限）
   * @param keyword 实体名包含（{@code null} 或空串表示不限，<b>区分大小写地不区分</b>：用 ilike）
   * @param offset 跳过多少行（负数按 0）
   * @param limit 取多少行（{@code <=0} 用默认 50，上限 500）
   */
  List<Entity> pageOf(UUID kbId, String type, String keyword, int offset, int limit);

  /**
   * 满足 {@link #pageOf} 过滤条件的总行数（分页控件要它才能算页数）。
   *
   * <p>★ 参数必须与 {@code pageOf} 完全一致 —— 不一致会出现「总数说 3 页、翻到第 3 页是空的」这种 最难查的一类分页 bug。
   */
  long countMatching(UUID kbId, String type, String keyword);

  /** 一条已入库的实体。 */
  record Entity(UUID id, String type, String name, String source, double confidence) {}

  /** 一条待写入的实体。 */
  record Candidate(String type, String name, String source, double confidence) {}
}
