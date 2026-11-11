package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把"一批段文本"抽成实体写进库，并记下每个实体出现在哪一段。
 *
 * <p>★ 这是 {@code kb_entity} 的**第一个写入方**。在此之前（`b664259` 建表、`e3f7d49` 能读字典） 表是一张只有 schema 没有数据的空表 ——
 * 本项目反复记账的同一个形状："留了缝，缝后面是空的"。
 *
 * <p>★ 抽取就地容错、**失败不影响文档入库**：这件事的价值是"以后能被检索用到"，而文档 能不能用与它无关。所以整批抽失败只记日志、返回 0，不让调用方回滚已经写好的文档与分段。 ★
 * 但这不等于吞掉问题 —— 失败一律 {@code log.warn} 带库 id 与段数，且返回 0 而不是"假装成功"。
 */
@Service
public class EntityExtractionService {

  private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

  /** 单段最多抽这么多（防止一段异常文本撑出上千个实体）。 */
  private static final int MAX_PER_SEGMENT = 20;

  private final EntityRecognizer recognizer;
  private final EntityRepository repository;
  private final EntityDictionaryPort dictionaryPort;
  private final EntityMentionRepository mentionRepository;
  private final EntityRelationRepository relationRepository;

  /**
   * 跑异步抽取的执行器（虚拟线程）。
   *
   * <p>★ 为什么抽取要异步：一次 1 万字的文档会产生几十段，BERT 每段几百毫秒 —— 同步就意味 着用户上传后要盯着转圈等到抽取完， 而抽取**不影响文档可用性**（{@link
   * #extractAndStore} 的注释已经说了）。所以把它移出请求线程。
   *
   * <p>★ 用虚拟线程而不是固定线程池：抽取是 IO（读库）+ CPU（前向）混合，且一次可能同时有多个 文档在上传。固定池在并发上传时会排队，而排队会让"抽取延迟"随上传量线性变长 ——
   * 这正是 {@code 文档入库} 不该付出的代价。
   */
  private final ExecutorService extractionExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /** 已提交、未完成的任务数 —— 关停时要能等它们跑完。 */
  private final AtomicLong inFlight = new AtomicLong();

  public EntityExtractionService(
      EntityRecognizer recognizer,
      EntityRepository repository,
      EntityDictionaryPort dictionaryPort,
      EntityMentionRepository mentionRepository,
      EntityRelationRepository relationRepository) {
    this.recognizer = recognizer;
    this.repository = repository;
    this.dictionaryPort = dictionaryPort;
    this.mentionRepository = mentionRepository;
    this.relationRepository = relationRepository;
  }

  /**
   * 抽取并写入（不记出处）。
   *
   * @param kbId 知识库 id
   * @param texts 段正文（一次传一批，内部去重）
   * @return 真正新插入的实体条数
   */
  public int extractAndStore(UUID kbId, List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      return 0;
    }
    List<Segment> wrapped = new ArrayList<>(texts.size());
    for (String text : texts) {
      wrapped.add(new Segment(null, text));
    }
    return extract(kbId, null, wrapped);
  }

  /**
   * 抽取并写入，同时记下「实体出现在哪一段」（{@code kb_entity_mention}）。
   *
   * <p>★ 为什么要把段 id 传进来：扩召回的整个动作是「问题里提到财务资产部 → 找出提到它的段」， 没有段 id 这一步连不上。与 {@code #51}
   * 同族：一条命中如果不可溯源，排错比没有更糟。
   *
   * @param kbId 知识库 id
   * @param documentId 文档 id
   * @param segments 段（id 可以为 null —— 没有 id 的段只抽实体、不记出处）
   * @return 真正新插入的实体条数
   */
  public int extractAndStore(UUID kbId, UUID documentId, List<Segment> segments) {
    return extract(kbId, documentId, segments);
  }

  /**
   * 异步版：提交后就返回，抽取在虚拟线程上跑。
   *
   * <p>★ 返回 {@link CompletableFuture} 而不是 {@code void}：测试要能等它跑完再断言，
   * 关停时也要能等它们收尾。**没有返回值就等于"提交了但不知道跑没跑"** —— 这正是本项目反复记账的 silent-failure 形状。
   *
   * @param kbId 知识库 id
   * @param documentId 文档 id
   * @param segments 段（已在主线程算好，捕获的是不可变快照）
   * @return 完成时给出真正新插入的实体条数；期间失败为 0（不抛，{@link #extract} 内部已容错）
   */
  public CompletableFuture<Integer> extractAndStoreAsync(
      UUID kbId, UUID documentId, List<Segment> segments) {
    if (kbId == null || segments == null || segments.isEmpty()) {
      return CompletableFuture.completedFuture(0);
    }
    // ★ 先拷一份：调用方传的 list 可能是主线程接着要复用的，异步读它就是在读可变状态。
    List<Segment> snapshot = List.copyOf(segments);
    inFlight.incrementAndGet();
    return CompletableFuture.supplyAsync(
            () -> extract(kbId, documentId, snapshot), extractionExecutor)
        .whenComplete((ignored, error) -> inFlight.decrementAndGet());
  }

  /**
   * 关停时等正在跑的抽取收尾。
   *
   * <p>★ 不等的后果：进程退出时正在写的实体被截断在事务中间，库里有半批实体、`kb_entity` 与 `kb_entity_mention` 对不上，而下一次启动不会有任何提示。
   *
   * <p>★ 上限 30 秒而不是无限等：抽取再慢也不该拖住关停；超时就记日志说清还有多少没跑完 —— 「等了一会儿没等到」和「已经全写完」必须能分开。
   */
  @PreDestroy
  void shutdown() {
    extractionExecutor.shutdown();
    try {
      if (!extractionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("#实体抽取 关停时仍有 {} 个抽取任务未完成，已放弃等待", inFlight.get());
        extractionExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      extractionExecutor.shutdownNow();
      log.warn("#实体抽取 关停被中断，未完成的抽取任务已放弃（{} 个）", inFlight.get(), e);
    }
  }

  private int extract(UUID kbId, UUID documentId, List<Segment> segments) {
    if (kbId == null || segments == null || segments.isEmpty()) {
      return 0;
    }
    try {
      Set<String> seen = new LinkedHashSet<>();
      List<EntityRepository.Candidate> batch = new ArrayList<>();
      // ★ 每段抽到哪些实体名 —— 用来在写完实体之后补 mention。
      List<Pending> pendings = new ArrayList<>();
      // ★ 关系与实体同一批收集，但**分开存**：关系的两端要等实体写完才有 id。
      List<PendingRelation> pendingRelations = new ArrayList<>();
      for (Segment segment : segments) {
        String text = segment.content();
        if (text == null || text.isBlank()) {
          continue;
        }
        // ★ 必须把 kbId 传下去：词表在 kb_entity_dict 里是 per-库的（#328），
        //   不带库标识就只能吃全局默认表 —— 那样数据库里配的字典一个字都不生效。
        List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, text);
        // 关系用同一个识别器抽（接口给了 default 空实现，所以不是所有实现都有关系）
        List<EntityRecognizer.Relation> relations = recognizer.relations(text);
        if (relations != null && !relations.isEmpty() && segment.id() != null) {
          pendingRelations.add(new PendingRelation(segment.id(), relations));
        }
        if (found == null) {
          continue;
        }
        int taken = 0;
        List<EntityRepository.Candidate> perSegment = new ArrayList<>();
        for (EntityRecognizer.Recognized r : found) {
          if (r == null || r.name() == null || r.name().isBlank()) {
            continue;
          }
          if (++taken > MAX_PER_SEGMENT) {
            log.warn("#实体抽取 单段命中超过 {} 个实体，已截断（kb={}, 段长={}）", MAX_PER_SEGMENT, kbId, text.length());
            break;
          }
          // 同一批里同名同类型只写一次：INSERT 是逐条的，重复传只会白跑 SQL
          String key = r.type() + "\u0000" + r.name();
          if (!seen.add(key)) {
            // ★ 不 continue 到外层：这一段仍然「提到了这个实体」，mention 要记。
            perSegment.add(
                new EntityRepository.Candidate(r.type(), r.name(), "RULE", r.confidence()));
            continue;
          }
          batch.add(new EntityRepository.Candidate(r.type(), r.name(), "RULE", r.confidence()));
          perSegment.add(
              new EntityRepository.Candidate(r.type(), r.name(), "RULE", r.confidence()));
        }
        if (!perSegment.isEmpty() && segment.id() != null) {
          pendings.add(new Pending(segment.id(), perSegment));
        }
      }

      int inserted = batch.isEmpty() ? 0 : repository.insertAll(kbId, batch);

      // ★ 实体写完再记出处：mention 需要实体 id，而 id 是插入时生成的。
      if (documentId != null && !pendings.isEmpty()) {
        int mentioned = 0;
        List<EntityMentionRepository.Mention> mentions = new ArrayList<>();
        for (Pending pending : pendings) {
          for (EntityRepository.Candidate c : pending.candidates()) {
            Optional<UUID> entityId = repository.findIdByName(kbId, c.type(), c.name());
            if (entityId.isPresent()) {
              mentions.add(
                  new EntityMentionRepository.Mention(
                      entityId.get(), kbId, documentId, pending.segmentId()));
            }
          }
        }
        // ★ 关系放在 mention 之后：两端实体必须是**已经入库**的，
        //   否则这条边在图里没有端点（kb_relation 的两个外键会直接拒绝）。
        if (!pendingRelations.isEmpty()) {
          int related = 0;
          List<EntityRelationRepository.Candidate> edges = new ArrayList<>();
          for (PendingRelation pr : pendingRelations) {
            for (EntityRecognizer.Relation rel : pr.relations()) {
              if (rel == null || rel.type() == null) {
                continue;
              }
              // 两端都要能解析成 id。抽到名字但库里没有这个实体 ⇒ 这条关系只记日志不落库：
              // 落一条缺端点的边比不落更糟，下游任何按实体的查询都会撞空。
              Optional<UUID> from = repository.findIdByName(kbId, "ORG", rel.from());
              Optional<UUID> to = repository.findIdByName(kbId, "ORG", rel.to());
              if (from.isEmpty() || to.isEmpty()) {
                log.debug("#实体关系 端点不在库里，跳过（{} -> {}）", rel.from(), rel.to());
                continue;
              }
              edges.add(
                  new EntityRelationRepository.Candidate(
                      from.get(), to.get(), rel.type(), documentId, pr.segmentId(), 0.8));
            }
          }
          if (!edges.isEmpty()) {
            related = relationRepository.insertAll(kbId, edges);
          }
          log.info("#实体关系 写入 {} 条（kb={}，候选 {}）", related, kbId, edges.size());
        }

        if (!mentions.isEmpty()) {
          mentioned = mentionRepository.insertAll(mentions);
        }
        log.info(
            "#实体抽取 抽出 {} 个候选、新写入 {} 个、记下 {} 条出处（kb={}）", batch.size(), inserted, mentioned, kbId);
      } else {
        log.info("#实体抽取 抽出 {} 个候选、新写入 {} 个（kb={}）", batch.size(), inserted, kbId);
      }
      return inserted;
    } catch (RuntimeException e) {
      // ★ 抽取失败不回滚文档：这件事与"文档能不能用"无关。但必须留痕 + 返回 0，
      //   不许吞成"成功写了 0 个"。
      log.warn("#实体抽取 失败，本次不写实体（kb={}, 段数={}）：{}", kbId, segments.size(), e.toString());
      return 0;
    }
  }

  /** 某个库的字典（透出去给探针/测试查"这个库配了什么"）。 */
  public EntityExtractor.Dictionary dictionaryOf(UUID kbId) {
    return dictionaryPort.dictionaryOf(kbId);
  }

  /** 一段待抽取的正文。{@code id} 为 null 表示只抽实体、不记出处。 */
  public record Segment(UUID id, String content) {}

  private record Pending(UUID segmentId, List<EntityRepository.Candidate> candidates) {}

  /** 一段读出来的关系。两端还是名字，落库前才解析成 id。 */
  private record PendingRelation(UUID segmentId, List<EntityRecognizer.Relation> relations) {}
}
