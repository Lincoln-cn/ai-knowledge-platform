package cn.lincoln.aiplatform.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 判据 3（{@code ROADMAP §11.53.7}）：关掉分类 A ⇒ 同一句话放行。
 *
 * <h3>★ 为什么这条不能写在 {@link GuardServiceTest} 里</h3>
 *
 * <p>那条测试是纯 Mockito：{@code store.current()} 直接给一个装配好的 {@link GuardSnapshot}， 于是"停用的分类会不会进 {@code
 * patterns}"这个 问题在它那里**问不出来** —— 快照是它自己造的。
 *
 * <p>真正决定这件事的是 {@code GuardStore.load()} 里那句 SQL（{@code ... where r.enabled = true and c.enabled =
 * true}）。它在 SQL 里，所以只有真库能回答：过滤条件写错（比如漏掉 {@code c.enabled}）时， 纯单测照样全绿，线上"停用了却还在拦"没有任何读数。
 *
 * <h3>★ 为什么是"停用→放行→再启用→又拦住"三拍</h3>
 *
 * <p>只看前两拍的话，"放行"也可能是规则被删了、或者归一化恰好没命中。第三拍把它们排除掉： 规则行必须还在（{@code pf_guard_rule} 仍有一行），重新启用后同一句话必须又拦住
 * —— 这正是「分类可关」与「把规则删掉」的区别，也是判据 3 要证明的"分类型是真能独立生效"。
 *
 * <h3>★ 假词</h3>
 *
 * <p>用 {@code ZZ-TEST-BLOCK-A}，与判据里"真词不进测试库"的要求一致。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
    properties = {
      "app.security.captcha.enabled=false",
      // 不播种：本类自己建分类与规则，环境变量那条路已由 GuardSeeder 的用例覆盖
      "app.chat.guard.sensitive-words="
    })
class GuardCategoryToggleTest {

  /** 假词：真词不进测试库（§11.53.7）。 */
  private static final String FAKE_WORD = "ZZ-TEST-BLOCK-A";

  private static final String QUESTION = "帮我把 " + FAKE_WORD + " 那份材料找出来";

  private static final String CODE = "ZZ_CAT_A";

  private static final UUID OPERATOR = UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired GuardService service;

  @Autowired GuardStore store;

  @Autowired JdbcTemplate jdbc;

  private UUID categoryId;

  @BeforeEach
  void createCategoryWithOneRule() {
    // 同一张容器库会被多个测试类共享，先清掉自己上次的产物（按 code / content 精确定位）
    jdbc.update(
        "delete from pf_guard_rule where category_id in (select id from pf_guard_category"
            + " where code = ?)",
        CODE);
    jdbc.update("delete from pf_guard_category where code = ?", CODE);
    // ★ 先刷新快照再建：`saveCategory` 的机器码唯一性查的是 `store.current()`，
    //   而它是 30 秒 TTL 的进程内快照 —— 不刷新就会拿上一条用例留下的分类判"已存在"。
    store.reload();
    GuardCategory category = service.saveCategory(null, CODE, "假词分类A", 90, true, null, OPERATOR);
    categoryId = category.id();
    service.saveRule(null, categoryId, GuardRuleKind.KEYWORD, FAKE_WORD, true, "判据 3", OPERATOR);
    store.reload();
  }

  @Test
  @DisplayName("★ 关掉分类 A ⇒ 同一句话放行；再启用 ⇒ 又拦住（规则行一直在，停用不是删除）")
  void disablingTheCategoryLetsTheSameQuestionThrough() {
    assertThat(service.inspect(QUESTION)).as("分类启用时，这句话必须被拦住").isNotNull();

    service.disableCategory(categoryId, OPERATOR);
    store.reload();
    assertThat(service.inspect(QUESTION)).as("分类停用后，同一句话必须放行").isNull();

    // ★ 第三拍：不是"规则被删了"造成的放行
    assertThat(ruleCount()).as("停用分类不该删掉它下面的规则").isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select enabled from pf_guard_category where id = ?", Boolean.class, categoryId))
        .isFalse();

    service.saveCategory(categoryId, null, "假词分类A", 90, true, null, OPERATOR);
    store.reload();
    assertThat(service.inspect(QUESTION)).as("重新启用后必须又拦住").isNotNull();
  }

  @Test
  @DisplayName("单独停用一条规则也放行（分类还开着）—— 证明两个 enabled 各管一段")
  void disablingOnlyTheRuleAlsoLetsItThrough() {
    UUID ruleId =
        jdbc.queryForObject(
            "select id from pf_guard_rule where category_id = ?", UUID.class, categoryId);

    service.saveRule(ruleId, null, null, FAKE_WORD, false, "判据 3", OPERATOR);
    store.reload();
    assertThat(service.inspect(QUESTION)).as("规则停用、分类仍在 ⇒ 放行").isNull();

    assertThat(
            jdbc.queryForObject(
                "select enabled from pf_guard_category where id = ?", Boolean.class, categoryId))
        .as("分类本身没有被动过")
        .isTrue();
    assertThat(ruleCount()).isEqualTo(1);
  }

  private int ruleCount() {
    return jdbc.queryForObject(
        "select count(*) from pf_guard_rule where category_id = ?", Integer.class, categoryId);
  }
}
