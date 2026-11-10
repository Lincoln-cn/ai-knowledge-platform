package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 字典从库到抽取器的那一段。
 *
 * <p>不连数据库：{@link JdbcTemplate} 换成替身，只验"行 → 三张表"的映射对不对。 真正的 SQL 是否写对由 {@code
 * MigrationIntegrationTest} 之外的真库演练覆盖 —— 这里要断的是**装配**，不是 SQL 方言。
 *
 * <p>★★ 这一组的期望值在 2026-11-11 变过一次：以前"空表 ⇒ 空字典"，现在是"空表 ⇒ 内置默认"。 原因见 {@link
 * JdbcEntityDictionaryAdapter#builtin()} —— 空字典的后果不是"抽得少"，是**一条关系都 抽不出来而没有任何读数异常**（真栈上就撞到了：实体表 263
 * 行、关系表 0 行）。
 */
class JdbcEntityDictionaryAdapterTest {

  private static final UUID KB = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  @DisplayName("三类行分别进对的表，且叠在内置默认之上")
  void splitsRowsByKind() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(KB)))
        .thenReturn(
            List.of(
                Map.of("kind", "ORG_SUFFIX", "value", "分部"),
                Map.of("kind", "ORG_SUFFIX", "value", "研究院"),
                Map.of("kind", "ORG_NAME", "value", "财务资产部"),
                Map.of("kind", "RELATION_MARK", "value", "挂靠", "relation", "BELONGS_TO"),
                Map.of("kind", "TERM", "value", "三重一大")));

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(KB);

    // ORG_SUFFIX 与 ORG_NAME 都进 orgSuffixes：抽取器的正则只认"以某后缀结尾"，
    // 具名机构本身就是"以某结尾"的，能自然通过。
    assertThat(d.orgSuffixes()).contains("分部", "研究院", "财务资产部").contains("部", "中心");
    // 库里的标记词在内置的那十个之外：两者都在
    assertThat(d.relationMarks())
        .containsEntry("挂靠", "BELONGS_TO")
        .containsEntry("隶属", "BELONGS_TO");
    // 这一版抽取器不用 TERM（术语靠引号抽取），跳过是登记过的行为
    assertThat(d.personTitles()).isEmpty();
  }

  @Test
  @DisplayName("库里一行都没有时给的是内置默认字典，不是空字典")
  void emptyRowsFallBackToBuiltin() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(KB))).thenReturn(List.of());

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(KB);

    // ★ 这条以前断的是 isEmpty()，改了。判据：空表时抽取器必须仍然能用，
    //   否则"新建的库"在管理员逐条录入之前一个实体、一条关系都抽不出来，
    //   而外部看不出任何异常（实体表空、关系表空，都像"这份文档没提机构"）。
    assertThat(d).isNotNull();
    assertThat(d.orgSuffixes()).isNotEmpty().contains("部", "中心", "公司");
    assertThat(d.relationMarks())
        .containsEntry("隶属", "BELONGS_TO")
        .containsEntry("负责", "RESPONSIBLE_FOR");
  }

  @Test
  @DisplayName("kbId 为 null 时给内置默认、且不查库")
  void nullKbDoesNotHitDatabase() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(null);

    // 无库上下文仍给内置默认：抽取器构造时要求"后缀表非空"，给空的等于让它抛错。
    assertThat(d.orgSuffixes()).isNotEmpty();
    org.mockito.Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("RELATION_MARK 缺 relation 时跳过这一行，不把 null 当关系名")
  void markerWithoutRelationIsSkipped() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(KB)))
        .thenReturn(List.of(Map.of("kind", "RELATION_MARK", "value", "隶属")));

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(KB);

    // 表上有 check 约束保证不会出现，这里再判一次是防御：约束被绕过时，
    // 宁可漏一条边，也不要把 null 写进图里。内置默认里的"隶属"还在，所以断言的是
    // "没有任何一个值是 null"，而不是"映射表为空"。
    assertThat(d.relationMarks().values()).doesNotContainNull().doesNotContain("");
  }
}
