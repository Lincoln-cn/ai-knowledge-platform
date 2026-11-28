package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import cn.lincoln.aiplatform.platform.retrieval.RelationRecallPort;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link RelationRecallPort} 的实现：抽问题里的实体 → 找实体 id → 找这些实体参与过关系的段。
 *
 * <p>与 {@code JdbcEntityRecallAdapter} 的结构几乎一样，但走的是不同的表、算的是不同的语义：
 * 那个找「实体被提到的段」，这个找「实体参与关系陈述的段」。合并成一个类会把两种语义糊在一起， 而它们的召回效果差别很大（后者精确得多，但只覆盖有关系的那一小部分段）。
 */
@Component
public class JdbcRelationRecallAdapter implements RelationRecallPort {

  private static final Logger log = LoggerFactory.getLogger(JdbcRelationRecallAdapter.class);

  /** 一个问题最多认这么多个实体（与 {@code JdbcEntityRecallAdapter} 同口径，防止 IN 列表被撑爆）。 */
  private static final int MAX_ENTITIES = 8;

  /** 明细最多念几条：命中测试是给人看的，念 200 条与念 20 条对判断"抽得对不对"没有区别。 */
  private static final int DETAIL_LIMIT = 20;

  private final EntityRecognizer recognizer;
  private final EntityRepository entityRepository;
  private final EntityRelationRepository relationRepository;

  public JdbcRelationRecallAdapter(
      EntityRecognizer recognizer,
      EntityRepository entityRepository,
      EntityRelationRepository relationRepository) {
    this.recognizer = recognizer;
    this.entityRepository = entityRepository;
    this.relationRepository = relationRepository;
  }

  @Override
  public List<UUID> segmentsWithRelations(UUID kbId, String question) {
    return recall(kbId, question).segments();
  }

  @Override
  public Recall recall(UUID kbId, String question) {
    if (kbId == null || question == null || question.isBlank()) {
      return new Recall(List.of(), List.of());
    }
    List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, question);
    if (found == null || found.isEmpty()) {
      return new Recall(List.of(), List.of());
    }
    Set<UUID> entityIds = new LinkedHashSet<>();
    int taken = 0;
    for (EntityRecognizer.Recognized r : found) {
      if (r == null || r.name() == null || r.name().isBlank()) {
        continue;
      }
      if (++taken > MAX_ENTITIES) {
        log.debug("#关系扩召回 问题里的实体超过 {} 个，已截断（kb={}）", MAX_ENTITIES, kbId);
        break;
      }
      // ★ 实体表里没有这个名字 ⇒ 这个库还没抽到过它，跳过（不是错误）。
      Optional<UUID> entityId = entityRepository.findIdByName(kbId, r.type(), r.name());
      entityId.ifPresent(entityIds::add);
    }
    if (entityIds.isEmpty()) {
      // 认出来了但实体表里都没有 —— 这一路没有起点，明细也如实为空。
      return new Recall(List.of(), List.of());
    }
    // ★ 第二跳（`§11.36.1` 的 C 项）：问题问 A、答案写在「B 与 C 的关系」那一段时，
    //   只查一跳永远找不到。先把 A 的邻居 B 也加进来，再统一查段。
    //   邻居只扩一跳、不递归 —— 三跳以上在这个规模上没有证据支持吃回本，见 `§11.27.5`。
    Set<UUID> withNeighbors = new LinkedHashSet<>(entityIds);
    List<UUID> neighbors = relationRepository.neighborEntityIds(kbId, entityIds);
    if (!neighbors.isEmpty()) {
      withNeighbors.addAll(neighbors);
      log.debug("#关系扩召回 第二跳：起点实体 {} 个，邻居 {} 个（kb={}）", entityIds.size(), neighbors.size(), kbId);
    }
    List<UUID> segments = relationRepository.segmentIdsOf(kbId, withNeighbors);
    if (segments.isEmpty()) {
      log.debug("#关系扩召回 实体有 {} 个，但没有任何关系陈述段（kb={}）", withNeighbors.size(), kbId);
      return new Recall(List.of(), List.of());
    }
    // ★ 明细：这次命中的关系陈述本身（含取证段）。界面上要能念出
    //   「信息中心 —隶属→ 运维科」，而不是只说"命中了 3 段" —— 后者无法让人判断抽得对不对。
    List<Relation> relations = new ArrayList<>();
    for (EntityRelationRepository.RelationRow row :
        relationRepository.rowsOfEntities(kbId, withNeighbors, DETAIL_LIMIT)) {
      if (row.segmentId() == null) {
        // 存量回填的边没有段落 —— 无法核对，把它念给用户看等于编一个出处。
        continue;
      }
      relations.add(new Relation(row.type(), row.fromName(), row.toName(), row.segmentId()));
    }
    log.debug(
        "#关系扩召回 命中 {} 个实体（含邻居 {}）、{} 段（kb={}）",
        withNeighbors.size(),
        neighbors.size(),
        segments.size(),
        kbId);
    // ★ 按 id 升序：名次交给 RRF，不在这里预排。
    List<UUID> ordered = new ArrayList<>(new LinkedHashSet<>(segments));
    ordered.sort(null);
    return new Recall(ordered, List.copyOf(relations));
  }
}
