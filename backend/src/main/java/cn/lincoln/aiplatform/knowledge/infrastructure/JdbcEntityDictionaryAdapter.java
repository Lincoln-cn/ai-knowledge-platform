package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.application.EntityExtractor;
import cn.lincoln.aiplatform.knowledge.domain.EntityDictionaryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 {@code kb_entity_dict} 读字典，**叠在内置默认字典之上**。
 *
 * <p>一次查询取全部行，在内存里按 {@code kind} 分组 —— 一个库的字典是几十到几百行， 分三次查（后缀 / 具名 / 标记词）只是多两次往返。
 *
 * <p>★ 读不出来（SQL 异常）时**不吞**：字典是抽取的前提，静默返回空字典会让"库里没配" 与"读失败了"看起来一样，而后者该是一次可见的失败。
 *
 * <p>★★ 为什么要有内置默认字典（{@link #builtin()}）：没有它，新建的库在管理员 逐条录入之前**一条关系都抽不出来** —— 关系标记词全来自字典，字典空则 {@code
 * relations()} 直接返回空列表，而"没配"与"抽不出来"读数完全一样，关系表 永远 0 行也不会有人发现。机构后缀同理：空表时抽取器会因为 "后缀表为空"抛错。
 *
 * <p>合并规则：**库里的行覆盖同名的内置项**。内置项是"能跑起来的下限"，不是"推荐的 全部"—— 管理员写了 {@code 归口} → {@code
 * RESPONSIBLE_FOR}，就不该再被内置的默认 映射改回去。
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
      return builtin();
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList("select kind, value, relation from kb_entity_dict where kb_id = ?", kbId);

    EntityExtractor.Dictionary base = builtin();
    LinkedHashSet<String> orgSuffixes = new LinkedHashSet<>(base.orgSuffixes());
    List<String> personTitles = new ArrayList<>(base.personTitles());
    Map<String, String> relationMarks = new LinkedHashMap<>(base.relationMarks());

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
    return new EntityExtractor.Dictionary(List.copyOf(orgSuffixes), personTitles, relationMarks);
  }

  /**
   * 内置默认字典：让一个刚建的库**不配也能抽出东西**。
   *
   * <p>不是"推荐配置"，是"能跑起来的下限"—— 关系标记词只取最常见、歧义最小的几组， 后缀只取"几乎一定是机构"的那些。宁可少抽，不可把普通名词当机构。
   */
  static EntityExtractor.Dictionary builtin() {
    List<String> orgSuffixes = List.of("部", "处", "科", "室", "中心", "公司", "集团", "局", "委员会", "领导小组");
    List<String> personTitles = List.of();
    Map<String, String> relationMarks = new LinkedHashMap<>();
    relationMarks.put("隶属", "BELONGS_TO");
    relationMarks.put("下属", "BELONGS_TO");
    relationMarks.put("归口", "BELONGS_TO");
    relationMarks.put("下设", "CONTAINS");
    relationMarks.put("包括", "CONTAINS");
    relationMarks.put("负责", "RESPONSIBLE_FOR");
    relationMarks.put("主管", "RESPONSIBLE_FOR");
    relationMarks.put("牵头", "RESPONSIBLE_FOR");
    relationMarks.put("是指", "DEFINES");
    relationMarks.put("简称", "DEFINES");
    return new EntityExtractor.Dictionary(orgSuffixes, personTitles, relationMarks);
  }
}
