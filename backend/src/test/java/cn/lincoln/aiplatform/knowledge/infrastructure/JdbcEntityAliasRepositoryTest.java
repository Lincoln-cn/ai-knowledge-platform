package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.EntityAliasRepository;
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

  @Test
  @DisplayName("写入别名返回真正新增的行数")
  void insertAllReturnsInsertedCount() {
    when(jdbc.update(anyString(), any(), eq(kbId), any(), any(), any())).thenReturn(1, 1);

    int rows =
        repository.insertAll(
            kbId,
            List.of(
                new EntityAliasRepository.Alias(canonical, "财务部", "MANUAL", 1.0),
                new EntityAliasRepository.Alias(canonical, "财务资产部", "RULE", 0.9)));

    assertThat(rows).isEqualTo(2);
  }

  @Test
  @DisplayName("重复别名被唯一约束挡下，不计入返回值")
  void duplicateAliasIsNotCounted() {
    // on conflict do nothing ⇒ 第二条返回 0；返回值小于入参长度，调用方能看出有一条没写进去
    when(jdbc.update(anyString(), any(), eq(kbId), any(), any(), any())).thenReturn(1, 0);

    int rows =
        repository.insertAll(
            kbId,
            List.of(
                new EntityAliasRepository.Alias(canonical, "财务部", "MANUAL", 1.0),
                new EntityAliasRepository.Alias(canonical, "财务部", "RULE", 0.8)));

    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("空输入不写库")
  void insertAllWithEmptyInputSkipsWrite() {
    assertThat(repository.insertAll(kbId, List.of())).isZero();
    assertThat(repository.insertAll(kbId, null)).isZero();
    assertThat(
            repository.insertAll(
                null, List.of(new EntityAliasRepository.Alias(canonical, "x", "MANUAL", 1.0))))
        .isZero();

    verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
  }
}
