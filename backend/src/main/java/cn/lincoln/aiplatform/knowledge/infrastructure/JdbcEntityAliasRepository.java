package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.EntityAliasRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 别名表的 JDBC 实现（0.6.3 读口 / 0.6.6 写口）。
 *
 * <p>★ 一次查完再在内存里挑：别名表按库分区，一个库的别名通常几十条；逐条 {@code in} 反而要拼 SQL 参数。
 *
 * <p>★ 读写都在这里：0.6.3 只加了 {@code canonicalIds}（读），0.6.6 补了 {@code insertAll}（写，合并策略用）。
 * 写口等到有写入方（{@code JdbcEntityMergeService}）才加，口径同 {@code WorkflowRecordRepository}。
 *
 * <p>★★ 2026-11-11 补 {@code @Repository}：此前这个类**没有注解**，Spring 里根本没有它的 bean。
 * 后果不是编译错也不是启动错（没人注入它），而是「别名功能在任何生产路径上都不存在」—— 与 {@code kb_relation} / {@code kb_entity_dict}
 * 同一个形状：读数为 0 与功能没接长得一样。 这次是给合并服务注入 {@code EntityAliasRepository} 时抛 {@code
 * NoSuchBeanDefinitionException} 才暴露的。
 */
@Repository
public class JdbcEntityAliasRepository implements EntityAliasRepository {

  private final JdbcTemplate jdbc;

  public JdbcEntityAliasRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<String, UUID> canonicalIds(UUID kbId, List<String> aliases) {
    if (kbId == null || aliases == null || aliases.isEmpty()) {
      // ★ 空输入直接返回空表，不去查库：抽取是逐段调用的，一篇 70 段的文档会调 70 次。
      return Map.of();
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select alias_name, entity_id from kb_entity_alias where kb_id = ?", kbId);
    if (rows.isEmpty()) {
      return Map.of();
    }
    Map<String, UUID> all = new HashMap<>();
    for (Map<String, Object> row : rows) {
      Object name = row.get("alias_name");
      Object id = row.get("entity_id");
      if (name instanceof String n && id instanceof UUID u) {
        all.put(n, u);
      }
    }
    if (all.isEmpty()) {
      return Map.of();
    }
    // 只返回问到的那些：调用方拿到的表要能直接当"问题里的每个名字解析成了谁"用，
    // 混进无关别名会让调用方以为它们出现在问题里。
    Map<String, UUID> picked = new HashMap<>();
    for (String alias : aliases) {
      UUID canonical = all.get(alias);
      if (canonical != null) {
        picked.put(alias, canonical);
      }
    }
    return picked;
  }

  /**
   * {@inheritDoc}
   *
   * <p>★ 用 {@code on conflict do nothing} 而不是先查再插：唯一约束已经在表上，让数据库判定比在应用里查一次再插可靠 ——
   * 两个合并任务同时跑时会同时查到「不存在」。与 {@code JdbcEntityRelationRepository} 同一个选择。
   *
   * <p>★ 重复别名不算错，但**也不静默**：被挡下的行仍然会让返回值小于入参长度，调用方能知道「有几条没写进去」。如果这里吞掉、返回值仍然等于入参长度，管理员改一次合并策略却看不出没生效。
   */
  @Override
  public int insertAll(UUID kbId, List<Alias> aliases) {
    if (kbId == null || aliases == null || aliases.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    for (Alias alias : aliases) {
      int rows =
          jdbc.update(
              "insert into kb_entity_alias (entity_id, kb_id, alias_name, source, confidence)"
                  + " values (?, ?, ?, ?, ?) on conflict (kb_id, alias_name) do nothing",
              alias.entityId(),
              kbId,
              alias.aliasName(),
              alias.source(),
              alias.confidence());
      inserted += rows;
    }
    return inserted;
  }
}
