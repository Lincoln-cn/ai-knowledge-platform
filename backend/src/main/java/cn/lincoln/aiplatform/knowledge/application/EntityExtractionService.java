package cn.lincoln.aiplatform.knowledge.application;

import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把"一批段文本"抽成实体写进库。
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

  public EntityExtractionService(
      EntityRecognizer recognizer,
      EntityRepository repository,
      EntityDictionaryPort dictionaryPort) {
    this.recognizer = recognizer;
    this.repository = repository;
    this.dictionaryPort = dictionaryPort;
  }

  /**
   * 抽取并写入。
   *
   * @param kbId 知识库 id
   * @param texts 段正文（一次传一批，内部去重）
   * @return 真正新插入的实体条数
   */
  public int extractAndStore(UUID kbId, List<String> texts) {
    if (kbId == null || texts == null || texts.isEmpty()) {
      return 0;
    }
    try {
      Set<String> seen = new LinkedHashSet<>();
      List<EntityRepository.Candidate> batch = new ArrayList<>();
      for (String text : texts) {
        if (text == null || text.isBlank()) {
          continue;
        }
        // ★ 必须把 kbId 传下去：词表在 kb_entity_dict 里是 per-库的（#328），
        //   不带库标识就只能吃全局默认表 —— 那样数据库里配的字典一个字都不生效。
        List<EntityRecognizer.Recognized> found = recognizer.recognize(kbId, text);
        if (found == null) {
          continue;
        }
        int taken = 0;
        for (EntityRecognizer.Recognized r : found) {
          if (r == null || r.name() == null || r.name().isBlank()) {
            continue;
          }
          // 同一批里同名同类型只写一次：INSERT 是逐条的，重复传只会白跑 SQL
          String key = r.type() + "\u0000" + r.name();
          if (!seen.add(key)) {
            continue;
          }
          if (++taken > MAX_PER_SEGMENT) {
            log.warn("#实体抽取 单段命中超过 {} 个实体，已截断（kb={}, 段长={}）", MAX_PER_SEGMENT, kbId, text.length());
            break;
          }
          batch.add(new EntityRepository.Candidate(r.type(), r.name(), "RULE", r.confidence()));
        }
      }
      if (batch.isEmpty()) {
        return 0;
      }
      int inserted = repository.insertAll(kbId, batch);
      log.info("#实体抽取 抽出 {} 个候选、新写入 {} 个（kb={}）", batch.size(), inserted, kbId);
      return inserted;
    } catch (RuntimeException e) {
      // ★ 抽取失败不回滚文档：这件事与"文档能不能用"无关。但必须留痕 + 返回 0，
      //   不许吞成"成功写了 0 个"。
      log.warn("#实体抽取 失败，本次不写实体（kb={}, 段数={}）：{}", kbId, texts.size(), e.toString());
      return 0;
    }
  }

  /** 某个库的字典（透出去给探针/测试查"这个库配了什么"）。 */
  public EntityExtractor.Dictionary dictionaryOf(UUID kbId) {
    return dictionaryPort.dictionaryOf(kbId);
  }
}
