package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityAliasRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import cn.lincoln.aiplatform.platform.retrieval.EntityRecallPort;
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
  private final EntityAliasRepository aliasRepository;

  public JdbcEntityRecallAdapter(
      EntityRecognizer recognizer,
      EntityRepository entityRepository,
      EntityMentionRepository mentionRepository,
      EntityAliasRepository aliasRepository) {
    this.recognizer = recognizer;
    this.entityRepository = entityRepository;
    this.mentionRepository = mentionRepository;
    this.aliasRepository = aliasRepository;
  }

  @Override
  public List<UUID> segmentsMentionedIn(UUID kbId, String question) {
    return recall(kbId, question).segments();
  }

  @Override
  public Recall recall(UUID kbId, String question) {
    if (kbId == null || question == null || question.isBlank()) {
      return new Recall(List.of(), List.of());
    }
    // ★ 抽不出来 ⇒ 空列表，这是正常结果（问题里本来就没有机构名），不是错误。
    List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, question);
    if (found == null || found.isEmpty()) {
      return new Recall(List.of(), List.of());
    }
    Set<UUID> segments = new LinkedHashSet<>();
    // ★ 明细与召回同一次产出：界面要念「问句里认出了信息中心、运维科」，
    //   另跑一遍换个识别器/换次截断就会和实际用的那批对不上。
    List<Entity> entities = new ArrayList<>();
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
      // ★ 先按名字精确找；找不到再问一次别名表（§11.36.1 的 A 项）。
      //   顺序不能反：别名是"同一个实体的另一个叫法"，精确名命中时不该再走一次查询。
      Optional<UUID> entityId = entityRepository.findIdByName(kbId, r.type(), r.name());
      if (entityId.isEmpty()) {
        // ★ 别名表按名字（不分类型）索引 —— 别名天然不带类型信息，
        //   “财务部”既是 ORG 的别名，也不该因为识别器把它标成别的类型就丢掉。
        entityId =
            Optional.ofNullable(
                aliasRepository.canonicalIds(kbId, List.of(r.name())).get(r.name()));
        if (entityId.isPresent()) {
          log.debug("#实体扩召回 名字「{}」经别名命中规范实体 {}", r.name(), entityId.get());
        }
      }
      if (entityId.isEmpty()) {
        continue;
      }
      List<UUID> mentioned = mentionRepository.segmentIdsOf(entityId.get());
      if (mentioned.isEmpty()) {
        continue;
      }
      hitEntities++;
      entities.add(new Entity(r.name(), r.type(), mentioned.size()));
      segments.addAll(mentioned);
    }
    if (segments.isEmpty()) {
      // ★ 认出了实体、但它们在这个库里一段都没被提到 —— 这是「认出来了但没命中」，
      //   与「一个都没认出来」是两件事，明细必须带出去，否则界面只能显示"没有"。
      return new Recall(List.of(), List.copyOf(entities));
    }
    log.debug("#实体扩召回 命中 {} 个实体、{} 段（kb={}）", hitEntities, segments.size(), kbId);
    // ★ 按 id 升序：名次交给 RRF，不在这里预排（在这里排序会让"一路候选"偷偷带上权重）。
    List<UUID> ordered = new ArrayList<>(segments);
    ordered.sort(null);
    return new Recall(ordered, List.copyOf(entities));
  }
}
