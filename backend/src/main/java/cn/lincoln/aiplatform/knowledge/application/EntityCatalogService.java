package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实体与关系的<b>读</b>侧（实体页与图谱，0.9.8 ④⑤⑥）。
 *
 * <p>★ 这个服务只读，不改数据 —— 写入是 {@code EntityExtractionService}，合并是 {@code EntityMergeService}。
 * 把它单独拉出来是因为它的消费方是"人看"，而那两处的消费方是"链路算"： 一旦合在一起，"为了让界面好看"的改动就会影响抽取与合并。
 *
 * <p>★ 为什么只出一个 {@code graph} 端点而不是"实体列表 / 关系列表 / 图谱"三个： 这三样在界面上是同一个面板的三个视图，分成三个端点会变成三次往返、三份可能不一致的快照
 * （比如实体列表里有 200 个实体，关系里却有指向不在列表里的实体的边 —— 两个请求之间正好写入）。
 *
 * <p>★ 这个服务不判断知识库可见性：与 {@code EntityMergeController} 同口径， 可见性在网关/权限点一层（{@code
 * knowledge:view}），这里只负责读。
 */
@Service
public class EntityCatalogService {

  /** 默认一次画多少个实体。 */
  private static final int DEFAULT_ENTITY_LIMIT = 500;

  /** 实体上限：再多图就没法看了，需要的是筛选而不是更大的上限。 */
  private static final int MAX_ENTITY_LIMIT = 2000;

  /** 默认一次取多少条关系。 */
  private static final int DEFAULT_RELATION_LIMIT = 2000;

  private final EntityRepository entities;
  private final EntityMentionRepository mentions;
  private final EntityRelationRepository relations;

  public EntityCatalogService(
      EntityRepository entities,
      EntityMentionRepository mentions,
      EntityRelationRepository relations) {
    this.entities = entities;
    this.mentions = mentions;
    this.relations = relations;
  }

  /**
   * 一个库的实体与关系（一次取齐，界面上三个视图共用一份快照）。
   *
   * @param kbId 知识库 id
   * @param entityLimit 实体上限（{@code <=0} 用默认 500）
   * @param relationLimit 关系上限（{@code <=0} 用默认 2000）
   */
  @Transactional(readOnly = true)
  public GraphView graph(UUID kbId, int entityLimit, int relationLimit) {
    int entityCap =
        entityLimit <= 0 ? DEFAULT_ENTITY_LIMIT : Math.min(entityLimit, MAX_ENTITY_LIMIT);
    int relationCap = relationLimit <= 0 ? DEFAULT_RELATION_LIMIT : relationLimit;
    Map<UUID, Integer> counts = mentions.countsOf(kbId);

    List<EntityRepository.Entity> all = entities.listOf(kbId);
    // 排序：提得多的排前面。"哪些实体更值得先看"就是这个数，按名字排等于没排。
    List<EntityRepository.Entity> sorted = new ArrayList<>(all);
    sorted.sort(
        Comparator.<EntityRepository.Entity>comparingInt(e -> counts.getOrDefault(e.id(), 0))
            .reversed()
            .thenComparing(EntityRepository.Entity::name));

    boolean truncated = sorted.size() > entityCap;
    List<NodeView> nodes =
        sorted.stream()
            .limit(entityCap)
            .map(
                e ->
                    new NodeView(
                        e.id(),
                        e.name(),
                        e.type(),
                        e.source(),
                        e.confidence(),
                        counts.getOrDefault(e.id(), 0)))
            .toList();

    List<EntityRelationRepository.RelationRow> rows = relations.listOf(kbId, relationCap);
    List<EdgeView> edges =
        rows.stream()
            .map(
                r ->
                    new EdgeView(
                        r.id(),
                        r.type(),
                        r.fromId(),
                        r.toId(),
                        r.documentId(),
                        r.segmentId(),
                        r.confidence()))
            .toList();

    return new GraphView(nodes, edges, truncated, all.size(), rows.size(), entityCap, relationCap);
  }

  /**
   * 图上的一个实体。
   *
   * <p>{@code mentions} = 它被多少段提到过。★ 这个数<b>不是</b>"它在这张图里有几条边" —— 一个实体可以被 200
   * 段提到而没有任何关系（关系靠标记词规则，抽不到就没有边）。 界面上两个数要分开显示，否则"0 条边"会被读成"这个实体不重要"。
   */
  public record NodeView(
      UUID id, String name, String type, String source, double confidence, int mentions) {}

  /**
   * 图上的一条边。
   *
   * <p>★ {@code segmentId} 是<b>取证位置</b>：界面上每条边都要能回到原文那一段。 没有它，用户只能相信"系统说这俩有关系"—— 那是另一类产品，不是这个。
   */
  public record EdgeView(
      UUID id,
      String type,
      UUID from,
      UUID to,
      UUID documentId,
      UUID segmentId,
      double confidence) {}

  /**
   * 一次取齐的快照。
   *
   * @param truncated 实体是否被截断（超过上限）。★ 界面必须显式提示"只画了一部分"， 否则人会把"图上没有"当成"库里没有"
   */
  public record GraphView(
      List<NodeView> nodes,
      List<EdgeView> edges,
      boolean truncated,
      int totalEntities,
      int totalRelations,
      int entityLimit,
      int relationLimit) {}
}
