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
    if (kbId == null || question == null || question.isBlank()) {
      return List.of();
    }
    List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, question);
    if (found == null || found.isEmpty()) {
      return List.of();
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
      return List.of();
    }
    List<UUID> segments = relationRepository.segmentIdsOf(kbId, entityIds);
    if (segments.isEmpty()) {
      log.debug("#关系扩召回 实体有 {} 个，但没有任何关系陈述段（kb={}）", entityIds.size(), kbId);
      return List.of();
    }
    log.debug("#关系扩召回 命中 {} 个实体、{} 段（kb={}）", entityIds.size(), segments.size(), kbId);
    // ★ 按 id 升序：名次交给 RRF，不在这里预排。
    List<UUID> ordered = new ArrayList<>(new LinkedHashSet<>(segments));
    ordered.sort(null);
    return ordered;
  }
}
