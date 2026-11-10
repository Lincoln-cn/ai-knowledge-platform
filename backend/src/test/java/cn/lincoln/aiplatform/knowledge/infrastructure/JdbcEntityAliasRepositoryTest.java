package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 别名表读口（0.6.3）。用替身而不是真库：断的是"别名怎么解析成规范实体"，不是 SQL 方言。 */
class JdbcEntityAliasRepositoryTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JdbcEntityAliasRepository repository = new JdbcEntityAliasRepository(jdbc);
  private final UUID kbId = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID canonical = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  @DisplayName("别名解析成规范实体 id")
  void resolvesAliasToCanonical() {
    when(jdbc.queryForList(anyString(), eq(kbId)))
        .thenReturn(List.of(Map.of("alias_name", "财务部", "entity_id", canonical)));

    assertThat(repository.canonicalIds(kbId, List.of("财务部"))).containsEntry("财务部", canonical);
  }

  @Test
  @DisplayName("查不到的别名不出现在结果里 —— 不是给一个 null 值")
  void unknownAliasIsAbsent() {
    when(jdbc.queryForList(anyString(), eq(kbId)))
        .thenReturn(List.of(Map.of("alias_name", "财务部", "entity_id", canonical)));

    // ★ 这个断言是关键：调用方会用"表里有没有这个 key"决定要不要扩召回，
    //   如果给 null 值，containsKey 会返回 true，等于把一次没命中当成命中。
    assertThat(repository.canonicalIds(kbId, List.of("技术部"))).isEmpty();
  }

  @Test
  @DisplayName("只返回问到的那几个别名，不把整表倒给调用方")
  void returnsOnlyAskedAliases() {
    UUID other = UUID.fromString("33333333-3333-4333-8333-333333333333");
    when(jdbc.queryForList(anyString(), eq(kbId)))
        .thenReturn(
            List.of(
                Map.of("alias_name", "财务部", "entity_id", canonical),
                Map.of("alias_name", "信息中心", "entity_id", other)));

    assertThat(repository.canonicalIds(kbId, List.of("财务部")))
        .containsOnlyKeys("财务部")
        .containsEntry("财务部", canonical);
  }

  @Test
  @DisplayName("空输入不查库")
  void emptyInputSkipsQuery() {
    assertThat(repository.canonicalIds(kbId, List.of())).isEmpty();
    assertThat(repository.canonicalIds(kbId, null)).isEmpty();
    assertThat(repository.canonicalIds(null, List.of("财务部"))).isEmpty();

    // 抽取是逐段调用的：没有别名的段不该产生一次数据库往返
    verify(jdbc, never()).queryForList(anyString(), eq(kbId));
  }

  @Test
  @DisplayName("别名为空表时不返回 null")
  void emptyTableIsNotEmptyNull() {
    when(jdbc.queryForList(anyString(), eq(kbId))).thenReturn(List.of());

    assertThat(repository.canonicalIds(kbId, List.of("财务部"))).isNotNull().isEmpty();
  }
}
