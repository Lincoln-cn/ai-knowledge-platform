package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractionService;
import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import cn.lincoln.aiplatform.knowledge.domain.EntityMentionRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRecognizer;
import cn.lincoln.aiplatform.knowledge.domain.EntityRelationRepository;
import cn.lincoln.aiplatform.knowledge.domain.EntityRepository;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 重抽时旧关系边必须消失（`#379`）—— 真 PG。
 *
 * <h3>★ 要防的是什么</h3>
 *
 * <p>{@code kb_relation} 的唯一键是 {@code (from_entity, to_entity, type)}，<strong>{@code type}
 * 在键里</strong>。 抽取规则一改（`V118` 把「下属」从 {@code BELONGS_TO} 改成 {@code CONTAINS}），重抽出来的边就成了 "另一条边"，而写入是
 * {@code on conflict ... do nothing} ⇒ <strong>旧的那条永远不会消失</strong>。
 *
 * <p>后果不是"多一条数据"：图谱上会出现两条方向相反的箭头，而且两条都带 {@code segment_id}， 看起来同样可信；关系扩召回也会跟着给错方向。
 *
 * <h3>★ 为什么断言打在抽取服务上，而不只打在仓库上</h3>
 *
 * <p>仓库层的 {@code deleteByDocument} 写对了、但抽取链路忘了调它，读数完全一样（都绿）。 这正是本项目反复吃亏的形状（"端口做好了但没人接"）。所以第一条用例走真实的
 * {@link EntityExtractionService#extractAndStore}：同一篇文档抽两次、第二次换规则，
 * 断言库里的那条边<strong>换了类型</strong>而不是变成了两条。
 */
@SpringBootTest
@Testcontainers
class EntityRelationSelfHealTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  private static final UUID OWNER = UUID.fromString("11111111-2222-4333-8444-555555555555");

  /** 同一篇文档抽两次都要用它 —— 自愈的判据就是"这篇文档的边"。 */
  private static final UUID DOC = UUID.fromString("4a000000-0000-4000-8000-000000000001");

  private static final UUID OTHER_DOC = UUID.fromString("4a000000-0000-4000-8000-000000000002");

  private static final String TEXT = "信息中心下属运维科";

  /**
   * 段 id 必须给 —— 抽取服务只在 `segment.id() != null` 时才收关系（关系要能取证到段）。 这里给一个随机 id 是为了让那条分支真的走到；mention
   * 由本类的假实现接住，不碰库， 所以不需要真的造一条 `kb_segment`。
   */
  private static final UUID SEG = UUID.fromString("4a100000-0000-4000-8000-000000000001");

  @Autowired JdbcTemplate jdbc;

  @Autowired EntityRepository entities;

  @Autowired EntityRelationRepository relations;

  @Test
  void redextractionDropsTheEdgesTheOldRuleProduced() {
    UUID kb = seedKb();
    // 旧规则：BELONGS_TO（`V118` 之前「下属」的方向）
    EntityExtractionService service = service(new ScriptedRecognizer("BELONGS_TO"));
    service.extractAndStore(kb, DOC, List.of(new EntityExtractionService.Segment(SEG, TEXT)));

    assertThat(typesOf(kb)).as("第一次抽取：旧规则产出一条 BELONGS_TO").containsExactly("BELONGS_TO");

    // 重抽：同一篇文档、同一句话，规则改成 CONTAINS
    EntityExtractionService afterUpgrade = service(new ScriptedRecognizer("CONTAINS"));
    afterUpgrade.extractAndStore(kb, DOC, List.of(new EntityExtractionService.Segment(SEG, TEXT)));

    assertThat(typesOf(kb))
        .as("★★ 规则改了以后旧边必须消失 —— 留着它，图谱上就是两条方向相反的箭头，" + "两条都带取证位置、看起来同样可信（#379）")
        .containsExactly("CONTAINS");
  }

  @Test
  void deletionIsScopedToOneDocument() {
    UUID kb = seedKb();
    UUID center = seedEntity(kb, "信息中心");
    UUID ops = seedEntity(kb, "运维科");

    relations.insertAll(
        kb,
        List.of(new EntityRelationRepository.Candidate(center, ops, "BELONGS_TO", DOC, null, 0.8)));
    relations.insertAll(
        kb,
        List.of(
            new EntityRelationRepository.Candidate(center, ops, "CONTAINS", OTHER_DOC, null, 0.8)));

    assertThat(typesOf(kb)).containsExactlyInAnyOrder("BELONGS_TO", "CONTAINS");

    int removed = relations.deleteByDocument(kb, DOC);

    assertThat(removed).as("只删这篇文档产出的那条").isEqualTo(1);
    assertThat(typesOf(kb)).as("★ 另一篇文档的边一条都不许动 —— 按文档删的意义就在这里").containsExactly("CONTAINS");
  }

  @Test
  void withoutDocumentIdNothingIsDeleted() {
    UUID kb = seedKb();
    UUID center = seedEntity(kb, "信息中心");
    UUID ops = seedEntity(kb, "运维科");
    relations.insertAll(
        kb,
        List.of(new EntityRelationRepository.Candidate(center, ops, "BELONGS_TO", DOC, null, 0.8)));

    assertThat(relations.deleteByDocument(kb, null))
        .as("★ 文档 id 为 null 时不动手：删掉「这个库的全部边」会把别的文档的成果一起清掉")
        .isZero();
    assertThat(relations.deleteByDocument(null, DOC)).as("库 id 为 null 同理").isZero();
    assertThat(typesOf(kb)).containsExactly("BELONGS_TO");
  }

  // ────────────────────────── 夹具 ──────────────────────────

  private List<String> typesOf(UUID kbId) {
    return jdbc.queryForList(
        "select type from kb_relation where kb_id = ? order by type", String.class, kbId);
  }

  private EntityExtractionService service(EntityRecognizer recognizer) {
    return new EntityExtractionService(
        recognizer, entities, new EmptyDictionary(), new NoMentions(), relations);
  }

  private UUID seedKb() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_knowledge_base (id, name, category, import_type, embedding_model_id,"
            + " max_classification, owner_id, visibility, created_at, updated_at)"
            + " values (?, ?, '制度规范', 'LOCAL', ?, ?, ?, 'PUBLIC', now(), now())",
        id,
        "关系自愈夹具库-" + id.toString().substring(0, 8),
        UUID.randomUUID(),
        "CONFIDENTIAL",
        OWNER);
    return id;
  }

  private UUID seedEntity(UUID kbId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into kb_entity (id, kb_id, type, name, source) values (?, ?, 'ORG', ?, 'RULE')",
        id,
        kbId,
        name);
    return id;
  }

  /** 按脚本给固定结果的识别器：只换关系类型，实体固定两个。 */
  private static final class ScriptedRecognizer implements EntityRecognizer {
    private final String relationType;

    ScriptedRecognizer(String relationType) {
      this.relationType = relationType;
    }

    @Override
    public List<Recognized> recognize(UUID kbId, String text) {
      return List.of(new Recognized("ORG", "信息中心", 0.8), new Recognized("ORG", "运维科", 0.8));
    }

    @Override
    public List<Relation> relations(String text, List<Recognized> found) {
      return List.of(new Relation(relationType, "信息中心", "运维科"));
    }

    @Override
    public List<String> supportedTypes() {
      return List.of("ORG");
    }

    @Override
    public String name() {
      return "scripted";
    }
  }

  /** 空词表（识别器自己给结果，这里不会被用到 —— 但仍要合法：机构后缀为空会直接抛错）。 */
  private static final class EmptyDictionary implements EntityDictionaryPort {
    @Override
    public EntityExtractor.Dictionary dictionaryOf(UUID kbId) {
      return new EntityExtractor.Dictionary(List.of("部"), List.of(), Map.of());
    }
  }

  /** 本类不验出处，段 id 传 null 已经让抽取服务跳过 mention；实现仍要能构造。 */
  private static final class NoMentions implements EntityMentionRepository {
    private final Map<UUID, List<UUID>> byEntity = new LinkedHashMap<>();

    @Override
    public int insertAll(List<Mention> mentions) {
      if (mentions == null) {
        return 0;
      }
      for (Mention mention : mentions) {
        byEntity
            .computeIfAbsent(mention.entityId(), ignored -> new ArrayList<>())
            .add(mention.segmentId());
      }
      return mentions.size();
    }

    @Override
    public List<UUID> segmentIdsOf(UUID entityId) {
      return Optional.ofNullable(byEntity.get(entityId)).orElseGet(List::of);
    }

    @Override
    public Map<UUID, Integer> countsOf(UUID kbId) {
      Map<UUID, Integer> counts = new LinkedHashMap<>();
      byEntity.forEach((entityId, segments) -> counts.put(entityId, segments.size()));
      return counts;
    }
  }
}
