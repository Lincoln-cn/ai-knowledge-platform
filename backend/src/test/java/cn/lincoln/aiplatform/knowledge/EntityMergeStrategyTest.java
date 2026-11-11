package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.EntityMergeService;
import cn.lincoln.aiplatform.knowledge.infrastructure.JdbcEntityMergeService;
import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
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
 * 合并策略第一档（强键相同 ⇒ 自动归并）的判据，<b>跑在真库上</b>。
 *
 * <p>★★ 这一批的判据被真库推翻过一次，值得记下来：第一版实现是「扫全库找同 {@code code} 的组再合并」， 跑集成测试直接撞 {@code duplicate key value
 * violates unique constraint "uq_kb_entity_code"} —— 库里<b>不可能</b>存在两行同 code。唯一约束是对的，错的是把合并想成事后清理。
 *
 * <p>现在的判据围的是「写入前问一句」这条路径：
 *
 * <ol>
 *   <li>{@code resolve} 在 code 已存在时返回 {@code insertNew = false} 且给出已有行的 id。
 *   <li>{@code resolve} 在 code 不存在（或为空）时返回 {@code insertNew = true}。
 *   <li>{@code recordAliases} 幂等：写两次只新增一行。
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EntityMergeStrategyTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired JdbcTemplate jdbc;

  @Autowired EntityMergeService mergeService;

  private UUID insertOrg(UUID kbId, String name, String code) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into kb_entity (id, kb_id, type, name, code, source, confidence, created_at, updated_at)
        values (?, ?, 'ORG', ?, ?, 'IMPORT', 1.0, now(), now())
        """,
        id,
        kbId,
        name,
        code);
    return id;
  }

  private UUID insertOrgWithoutCode(UUID kbId, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into kb_entity (id, kb_id, type, name, code, source, confidence, created_at, updated_at)
        values (?, ?, 'ORG', ?, null, 'RULE', 0.8, now(), now())
        """,
        id,
        kbId,
        name);
    return id;
  }

  private UUID insertKb() {
    UUID kbId = UUID.randomUUID();
    UUID modelId = UUID.randomUUID();
    // admin 的 id（V4__identity_seed_demo_accounts.sql）；owner_id 是 not null。
    UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    jdbc.update(
        """
        insert into kb_knowledge_base
            (id, name, category, import_type, max_classification, embedding_model_id, owner_id,
             created_at, updated_at)
        values (?, ?, '部门制度', 'LOCAL', 'INTERNAL', ?, ?, now(), now())
        """,
        kbId,
        "合并策略测试库-" + kbId.toString().substring(0, 8),
        modelId,
        ownerId);
    return kbId;
  }

  /** 同 {@code code} 已存在时不插新行，返回已有那一行的 id。 */
  @Test
  void strongKeyHitReusesExistingRow() {
    UUID kbId = insertKb();
    UUID existing = insertOrg(kbId, "财务资产部", "FIN-001");

    var decision = mergeService.resolve(kbId, "FIN-001");

    assertThat(decision.insertNew()).as("同 code 已存在 ⇒ 不该插新行").isFalse();
    assertThat(decision.mergeInto()).as("要并进已有的那一行").isEqualTo(existing);
    assertThat(decision.matchedCode()).isEqualTo("FIN-001");
  }

  /** 强键在库里查不到时照常插入 —— 第一档只处理「已经有了」这种情况。 */
  @Test
  void strongKeyMissInsertsNewRow() {
    UUID kbId = insertKb();
    assertThat(mergeService.resolve(kbId, "FIN-999").insertNew()).isTrue();
  }

  /** 实体没有 {@code code}（抽取出来的都没有）时照常插入，交给后两档判定。 */
  @Test
  void missingStrongKeyInsertsNewRow() {
    UUID kbId = insertKb();
    insertOrgWithoutCode(kbId, "财务资产部");
    // 抽取出的实体没有 code ⇒ 走正常插入，交给后两档判定。
    assertThat(mergeService.resolve(kbId, null).insertNew()).isTrue();
    assertThat(mergeService.resolve(kbId, "  ").insertNew()).isTrue();
  }

  /** 同一组别名写两次只留一行：{@code unique(kb_id, alias_name)} 挡第二次。 */
  @Test
  void aliasWriteIsIdempotent() {
    UUID kbId = insertKb();
    UUID existing = insertOrg(kbId, "财务资产部", "FIN-002");

    int first = mergeService.recordAliases(kbId, existing, List.of("财务部"));
    int second = mergeService.recordAliases(kbId, existing, List.of("财务部"));

    assertThat(first).as("第一次真的写入一行").isEqualTo(1);
    assertThat(second).as("第二次被唯一约束挡下，不新增").isZero();
    List<String> aliases =
        jdbc.queryForList(
            "select alias_name from kb_entity_alias where entity_id = ?", String.class, existing);
    assertThat(aliases).containsExactly("财务部");
  }

  /** 第二档只出候选清单，别名表一个字都不动 —— 等人确认。 */
  @Test
  void attributeCandidatesDoNotWriteAliases() {
    UUID kbId = insertKb();
    UUID a = insertOrg(kbId, "财务资产部", "FIN-004");
    UUID b = insertOrg(kbId, "财务部", "FIN-005");
    jdbc.update(
        "update kb_entity set attributes = '{\"department\": \"财务\"}'::jsonb where id = ?", a);
    jdbc.update(
        "update kb_entity set attributes = '{\"department\": \"财务\"}'::jsonb where id = ?", b);

    var candidates = mergeService.candidatesByAttributes(kbId);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).reason()).contains("department=财务");
    // ★ 第二档只出候选，别名表一个字都不该动。
    Integer aliases =
        jdbc.queryForObject(
            "select count(*) from kb_entity_alias where kb_id = ?", Integer.class, kbId);
    assertThat(aliases).as("候选阶段不许写别名").isZero();
  }

  /** 共同属性判定只认字符串对：数字与布尔不参与，类型差异会把「不同」判成「相同」。 */
  @Test
  void sharedAttributeIgnoresNumbersAndBooleans() {
    // 数字/布尔不参与 —— 类型差异会让"相同"变成误判。
    assertThat(JdbcEntityMergeService.sharedAttribute("{\"a\": 1}", "{\"a\": 1}")).isNull();
    assertThat(JdbcEntityMergeService.sharedAttribute("{\"a\": \"x\"}", "{\"a\": \"x\"}"))
        .isEqualTo("a=x");
    assertThat(JdbcEntityMergeService.sharedAttribute(null, "{}")).isNull();
  }

  /**
   * 第二档确认合并的判据（{@code §11.36.1} 的 D 项）。
   *
   * <p>★ 这是「点确认之后发生了什么」的完整断言，三件事一起断：① 别名表多一行、{@code source} 是 {@code MANUAL}（能区分机器认的还是人认的）②
   * 原实体行<b>还在</b>（合并不许删行，否则不可拆） ③ 再次确认同一组是幂等的（返回 0，不是报错）。
   */
  @Test
  void manualMergeWritesAliasAndKeepsOriginalRow() {
    UUID kbId = insertKb();
    UUID keep = insertOrg(kbId, "财务资产部", "FIN-006");
    UUID merge = insertOrg(kbId, "财务部", "FIN-007");

    int added = mergeService.recordManualAliases(kbId, keep, List.of("财务部"));
    assertThat(added).as("第一次确认必须真的写进去").isEqualTo(1);

    var row =
        jdbc.queryForMap(
            "select entity_id, source, confidence from kb_entity_alias where kb_id = ?", kbId);
    assertThat(row.get("entity_id")).isEqualTo(keep);
    assertThat(row.get("source")).as("人工确认的来源必须是 MANUAL").isEqualTo("MANUAL");
    assertThat(((Number) row.get("confidence")).doubleValue()).isEqualTo(1.0);

    // ★ 关键：原实体行还在。删了它，这条别名就无从回溯"当初是不是合错了"。
    Integer entities =
        jdbc.queryForObject(
            "select count(*) from kb_entity where kb_id = ? and deleted_at is null",
            Integer.class,
            kbId);
    assertThat(entities).as("合并只写别名，不删原实体").isEqualTo(2);
    assertThat(merge).as("被并入的那行依然存在").isNotNull();
    Integer stillThere =
        jdbc.queryForObject("select count(*) from kb_entity where id = ?", Integer.class, merge);
    assertThat(stillThere).isEqualTo(1);

    // 幂等：同一组再确认一次是 0，不是异常。
    assertThat(mergeService.recordManualAliases(kbId, keep, List.of("财务部")))
        .as("重复确认返回 0")
        .isZero();
  }
}
