package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 {@code kb_entity_dict} 读字典。
 *
 * <p>一次查询取全部行，在内存里按 {@code kind} 分组 —— 一个库的字典是几十到几百行， 分三次查（后缀 / 具名 / 标记词）只是多两次往返。
 *
 * <p>★ 读不出来（SQL 异常）时**不吞**：字典是抽取的前提，静默返回空字典会让"库里没配" 与"读失败了"看起来一样，而后者该是一次可见的失败。
 */
@Component
public class JdbcEntityDictionaryAdapter implements EntityDictionaryPort {

  private final JdbcTemplate jdbc;

  public JdbcEntityDictionaryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public EntityExtractor.Dictionary dictionaryOf(UUID kbId) {
    if (kbId == null) {
      return empty();
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList("select kind, value, relation from kb_entity_dict where kb_id = ?", kbId);

    List<String> orgSuffixes = new ArrayList<>();
    List<String> personTitles = new ArrayList<>();
    Map<String, String> relationMarks = new LinkedHashMap<>();

    for (Map<String, Object> row : rows) {
      String kind = (String) row.get("kind");
      String value = (String) row.get("value");
      if (kind == null || value == null) {
        continue;
      }
      switch (kind) {
        // 机构后缀与具名机构都喂给抽取器的 orgSuffixes：抽取器的正则只认"以某后缀结尾"，
        // 而具名机构（财务资产部）本身就是"以部结尾"的，能自然通过。把它们分开会让
        // 抽取器多一条判定路径，收益不抵复杂度。
        case "ORG_SUFFIX", "ORG_NAME" -> orgSuffixes.add(value);
        case "RELATION_MARK" -> {
          String relation = (String) row.get("relation");
          // 约束保证 RELATION_MARK 一定带 relation，这里再判一次是防御：
          // 如果约束被绕过，宁可漏一条边，也不要把 null 当关系名写进图里。
          if (relation != null && !relation.isBlank()) {
            relationMarks.put(value, relation);
          }
        }
        // TERM 这一版抽取器不用（术语靠引号抽取），静默跳过是**登记过**的行为，
        // 不是遗漏：等术语要按字典命中再回来加。
        default -> {
          // 无操作
        }
      }
    }
    return new EntityExtractor.Dictionary(orgSuffixes, personTitles, relationMarks);
  }

  private static EntityExtractor.Dictionary empty() {
    return new EntityExtractor.Dictionary(List.of(), List.of(), Map.of());
  }
}
