package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.application.EntityMergeService;
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

  @Test
  void 强键已存在时不插新行_返回已有行的id() {
    UUID kbId = insertKb();
    UUID existing = insertOrg(kbId, "财务资产部", "FIN-001");

    var decision = mergeService.resolve(kbId, "FIN-001");

    assertThat(decision.insertNew()).as("同 code 已存在 ⇒ 不该插新行").isFalse();
    assertThat(decision.mergeInto()).as("要并进已有的那一行").isEqualTo(existing);
    assertThat(decision.matchedCode()).isEqualTo("FIN-001");
  }

  @Test
  void 强键不存在时照常插入() {
    UUID kbId = insertKb();
    assertThat(mergeService.resolve(kbId, "FIN-999").insertNew()).isTrue();
  }

  @Test
  void 没有强键时照常插入() {
    UUID kbId = insertKb();
    insertOrgWithoutCode(kbId, "财务资产部");
    // 抽取出的实体没有 code ⇒ 走正常插入，交给后两档判定。
    assertThat(mergeService.resolve(kbId, null).insertNew()).isTrue();
    assertThat(mergeService.resolve(kbId, "  ").insertNew()).isTrue();
  }

  @Test
  void 别名写入是幂等的() {
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

  @Test
  void 属性候选只出清单不写库() {
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

  @Test
  void 共同属性判定不吃数字与布尔值() {
    // 数字/布尔不参与 —— 类型差异会让"相同"变成误判。
    assertThat(JdbcEntityMergeService.sharedAttribute("{\"a\": 1}", "{\"a\": 1}")).isNull();
    assertThat(JdbcEntityMergeService.sharedAttribute("{\"a\": \"x\"}", "{\"a\": \"x\"}"))
        .isEqualTo("a=x");
    assertThat(JdbcEntityMergeService.sharedAttribute(null, "{}")).isNull();
  }
}
