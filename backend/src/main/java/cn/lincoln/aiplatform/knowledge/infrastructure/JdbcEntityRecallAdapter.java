package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecallPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** {@link EntityRecallPort} 的实现：抽问题里的实体 → 找实体 id → 找提到它的段。 */
@Component
public class JdbcEntityRecallAdapter implements EntityRecallPort {

  private static final Logger log = LoggerFactory.getLogger(JdbcEntityRecallAdapter.class);

  /** 一个问题最多认这么多个实体（防止长问题抽出一堆，把一次查询放大成几百次）。 */
  private static final int MAX_ENTITIES = 8;

  private final EntityRecognizer recognizer;
  private final EntityRepository entityRepository;
  private final EntityMentionRepository mentionRepository;

  public JdbcEntityRecallAdapter(
      EntityRecognizer recognizer,
      EntityRepository entityRepository,
      EntityMentionRepository mentionRepository) {
    this.recognizer = recognizer;
    this.entityRepository = entityRepository;
    this.mentionRepository = mentionRepository;
  }

  @Override
  public List<UUID> segmentsMentionedIn(UUID kbId, String question) {
    if (kbId == null || question == null || question.isBlank()) {
      return List.of();
    }
    // ★ 抽不出来 ⇒ 空列表，这是正常结果（问题里本来就没有机构名），不是错误。
    List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, question);
    if (found == null || found.isEmpty()) {
      return List.of();
    }
    Set<UUID> segments = new LinkedHashSet<>();
    int taken = 0;
    int hitEntities = 0;
    for (EntityRecognizer.Recognized r : found) {
      if (r == null || r.name() == null || r.name().isBlank()) {
        continue;
      }
      if (++taken > MAX_ENTITIES) {
        log.debug("#实体扩召回 问题里的实体超过 {} 个，已截断（kb={}）", MAX_ENTITIES, kbId);
        break;
      }
      // ★ 实体表里没有这个名字 ⇒ 这个库还没抽到过它，跳过（不是错误）。
      Optional<UUID> entityId = entityRepository.findIdByName(kbId, r.type(), r.name());
      if (entityId.isEmpty()) {
        continue;
      }
      List<UUID> mentioned = mentionRepository.segmentIdsOf(entityId.get());
      if (mentioned.isEmpty()) {
        continue;
      }
      hitEntities++;
      segments.addAll(mentioned);
    }
    if (segments.isEmpty()) {
      return List.of();
    }
    log.debug("#实体扩召回 命中 {} 个实体、{} 段（kb={}）", hitEntities, segments.size(), kbId);
    // ★ 按 id 升序：名次交给 RRF，不在这里预排（在这里排序会让"一路候选"偷偷带上权重）。
    List<UUID> ordered = new ArrayList<>(segments);
    ordered.sort(null);
    return ordered;
  }
}
