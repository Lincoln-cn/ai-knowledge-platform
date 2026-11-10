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
 */
class JdbcEntityDictionaryAdapterTest {

  private static final UUID KB = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  @DisplayName("三类行分别进对的表：后缀、具名机构、标记词")
  void splitsRowsByKind() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(KB)))
        .thenReturn(
            List.of(
                Map.of("kind", "ORG_SUFFIX", "value", "部"),
                Map.of("kind", "ORG_SUFFIX", "value", "中心"),
                Map.of("kind", "ORG_NAME", "value", "财务资产部"),
                Map.of("kind", "RELATION_MARK", "value", "隶属", "relation", "BELONGS_TO"),
                Map.of("kind", "TERM", "value", "三重一大")));

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(KB);

    // ORG_SUFFIX 与 ORG_NAME 都进 orgSuffixes：抽取器的正则只认"以某后缀结尾"，
    // 具名机构本身就是"以部结尾"的，能自然通过。
    assertThat(d.orgSuffixes()).containsExactly("部", "中心", "财务资产部");
    assertThat(d.relationMarks()).containsExactly(Map.entry("隶属", "BELONGS_TO"));
    // 这一版抽取器不用 TERM（术语靠引号抽取），跳过是登记过的行为
    assertThat(d.personTitles()).isEmpty();
  }

  @Test
  @DisplayName("库里一行都没有时返回空字典，不是 null")
  void emptyRowsGiveEmptyDictionary() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(KB))).thenReturn(List.of());

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(KB);

    // ★ 返回 null 会让调用方在第一个 if 上分叉，而"没配过"与"配了但是空的"在抽取器看来
    // 是同一件事（都抽不出实体）—— 让它们读数一致，问题就只有一个。
    assertThat(d).isNotNull();
    assertThat(d.orgSuffixes()).isEmpty();
    assertThat(d.relationMarks()).isEmpty();
  }

  @Test
  @DisplayName("kbId 为 null 直接返回空字典，不查库")
  void nullKbDoesNotHitDatabase() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    EntityExtractor.Dictionary d = new JdbcEntityDictionaryAdapter(jdbc).dictionaryOf(null);

    assertThat(d.orgSuffixes()).isEmpty();
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
    // 宁可漏一条边，也不要把 null 写进图里。
    assertThat(d.relationMarks()).isEmpty();
  }
}
