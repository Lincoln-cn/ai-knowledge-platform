package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一个知识库里「抽到了什么」的只读视图（实体 / 关系 / 图谱）。
 *
 * <p>★ 2026-11-29 起分成三个查询而不是"一次取齐"：真实语料库里 20 篇法规能抽出 1400+ 个实体，
 * 一次全量取回再让浏览器切页，等于把「库里有多少」变成「浏览器一次能吃多少」。现在：
 *
 * <ul>
 *   <li>{@link #graph} —— 只给图谱，节点是<b>出现在关系里的实体</b>（有界、且每条边都在图上）；
 *   <li>{@link #entities} —— 实体表格，库里分页 + 类型筛选 + 名字搜索；
 *   <li>{@link #relations} —— 关系表格，库里分页。
 * </ul>
 *
 * <p>★ 图谱为什么不画"被提到最多的 N 个实体"：那个排序与"有没有关系"无关。一个 1400 实体的库里 只有 18 条边，取 Top 80 画出来是一堆孤岛 ——
 * 用户看到的是"系统抽了一堆没头没脑的名字"。 以边为中心取节点，图里每一个点都至少连着一条边，图才回答得了"谁和谁有关系"。
 *
 * <p>★ 两个数要分开显示：「被 N 段提到」与「图里有几条边」。一个实体可以被 200 段提到而没有 任何关系（关系靠标记词规则，抽不到就没有边）—— 合成一个数会让"0
 * 条边"被读成"这个实体不重要"。
 *
 * <p>★ 每条边都要能回到原文那一段（{@code segmentId} 是取证位置）。没有它，用户只能相信 "系统说这俩有关系"—— 那是另一类产品。
 */
@Service
public class EntityCatalogService {

  /** 图谱边数上限：一次画几千条边已经读不动了。 */
  private static final int DEFAULT_RELATION_LIMIT = 2000;

  private static final int DEFAULT_PAGE_SIZE = 50;

  private static final int MAX_PAGE_SIZE = 500;

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
   * 图谱：节点只取<b>出现在关系里的实体</b>，边取全量（有上限）。
   *
   * <p>★ 这样图里不会出现"孤立的点"：每一个节点都至少有一条边。孤立的点在图上既不能回答问题， 又会让人以为"这个实体很重要但它没关系"——
   * 那其实是"它没有被规则抽到边"，属于另一个问题， 该在实体表格里用「关系数」这一列说清，而不是画成点。
   *
   * @param kbId 知识库 id
   * @param relationLimit 关系上限（{@code <=0} 用默认 2000）
   */
  @Transactional(readOnly = true)
  public GraphView graph(UUID kbId, int relationLimit) {
    int relationCap = relationLimit <= 0 ? DEFAULT_RELATION_LIMIT : relationLimit;
    Map<UUID, Integer> counts = mentions.countsOf(kbId);
    Map<UUID, EntityRepository.Entity> byId = new LinkedHashMap<>();
    for (EntityRepository.Entity e : entities.listOf(kbId)) {
      byId.put(e.id(), e);
    }

    // ★ 先取总数再取行：`truncated` 要的是"库里有 2500 条、图只画了 2000 条"，
    //   不是"这一页返回了几条" —— 拿 rows.size() 与 rows.size() 比永远为假（0.9.9 就是那样）。
    long relTotal = relations.countOf(kbId);
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

    // 边两端的实体：保持出现顺序，去重。两端都要 —— 只取 from 会让一半节点没有名字。
    Set<UUID> involved = new LinkedHashSet<>();
    for (EntityRelationRepository.RelationRow r : rows) {
      involved.add(r.fromId());
      involved.add(r.toId());
    }
    List<NodeView> nodes = new ArrayList<>(involved.size());
    for (UUID id : involved) {
      EntityRepository.Entity e = byId.get(id);
      if (e == null) {
        // 实体被合并/删除了但边还在：不画这个点，边会在前端因为"端点不在画面里"被丢掉。
        // 这不是数据错误，是合并之后还没来得及重抽，值得留一条痕而不是静默。
        continue;
      }
      nodes.add(
          new NodeView(
              e.id(),
              e.name(),
              e.type(),
              e.source(),
              e.confidence(),
              counts.getOrDefault(e.id(), 0)));
    }
    nodes.sort(Comparator.<NodeView>comparingInt(n -> -n.mentions()).thenComparing(NodeView::name));
    return new GraphView(
        nodes,
        edges,
        relTotal > rows.size(),
        byId.size(),
        (int) relTotal,
        involved.size(),
        relationCap);
  }

  /**
   * 实体表格的一页。
   *
   * @param type 只看某一类（{@code null}/空 = 不限）
   * @param keyword 名字包含（{@code null}/空 = 不限）
   * @param page 页码，从 1 开始（{@code <1} 按 1）
   * @param size 每页条数（{@code <=0} 用默认 50，上限 500）
   */
  @Transactional(readOnly = true)
  public PageView<NodeView> entities(UUID kbId, String type, String keyword, int page, int size) {
    int pageNo = Math.max(page, 1);
    int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    long total = entities.countMatching(kbId, type, keyword);
    Map<UUID, Integer> counts = mentions.countsOf(kbId);
    List<NodeView> items =
        entities.pageOf(kbId, type, keyword, (pageNo - 1) * pageSize, pageSize).stream()
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
    return new PageView<>(items, total, pageNo, pageSize);
  }

  /**
   * 关系表格的一页。
   *
   * @param page 页码，从 1 开始（{@code <1} 按 1）
   * @param size 每页条数（{@code <=0} 用默认 50，上限 500）
   */
  @Transactional(readOnly = true)
  public PageView<RelationRowView> relations(UUID kbId, int page, int size) {
    int pageNo = Math.max(page, 1);
    int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    long total = relations.countOf(kbId);
    List<RelationRowView> items =
        relations.pageOf(kbId, (pageNo - 1) * pageSize, pageSize).stream()
            .map(
                r ->
                    new RelationRowView(
                        r.id(),
                        r.type(),
                        r.fromId(),
                        r.fromName(),
                        r.fromType(),
                        r.toId(),
                        r.toName(),
                        r.toType(),
                        r.documentId(),
                        r.segmentId(),
                        r.confidence()))
            .toList();
    return new PageView<>(items, total, pageNo, pageSize);
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
   * 关系表格的一行（带两端名字与类型，界面直接渲染，不用再查一遍实体）。
   *
   * <p>★ 与 {@link EdgeView} 的差别：{@code EdgeView} 只带 id，因为图上有节点列表可以查名字； 表格里每一行要能独立念出来「谁 —什么关系→
   * 谁」，所以名字与类型都带上。
   */
  public record RelationRowView(
      UUID id,
      String type,
      UUID fromId,
      String fromName,
      String fromType,
      UUID toId,
      String toName,
      String toType,
      UUID documentId,
      UUID segmentId,
      double confidence) {}

  /**
   * 一页数据。
   *
   * @param list 这一页的行。★ 字段名是 {@code list} 不是 {@code items} —— 与全项目的分页响应一致， 前端只有一个 {@code
   *     PageResult} 类型，多一个同义字段会让人以为这是另一种分页。
   * @param total 满足条件的总条数（不是这一页的条数）
   */
  public record PageView<T>(List<T> list, long total, int page, int size) {}

  /**
   * 图谱快照。
   *
   * @param nodes 出现在关系里的实体（没有孤立点）
   * @param totalEntities 库里实体总数 —— 与 {@code nodes.size()} 的差就是"没有任何关系的实体有多少"
   * @param involvedEntities 图上的节点数
   * @param relationLimit 关系上限（界面要说明"只画了前 N 条边"）
   */
  public record GraphView(
      List<NodeView> nodes,
      List<EdgeView> edges,
      boolean truncated,
      int totalEntities,
      int totalRelations,
      int involvedEntities,
      int relationLimit) {}
}
