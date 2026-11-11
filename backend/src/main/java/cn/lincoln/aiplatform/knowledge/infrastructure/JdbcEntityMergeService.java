package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.application.EntityMergeService;
import cn.lincoln.aiplatform.knowledge.domain.EntityAliasRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@link EntityMergeService} 的 JDBC 实现。
 *
 * <p>★ 第一档为什么做成「写入前问一句」而不是「扫描后清理」：{@code kb_entity} 上有 {@code unique(kb_id, code) where code is not
 * null}，库里<b>不可能</b>存在两行同 code。 事后扫描在健康的库上永远扫不到东西 —— 那不是一个可以空转的功能，是一个被约束堵死的状态。
 * 唯一约束是对的，合并的位置应该提前到插入之前。
 */
@Service
public class JdbcEntityMergeService implements EntityMergeService {

  private static final Logger log = LoggerFactory.getLogger(JdbcEntityMergeService.class);

  private final JdbcTemplate jdbc;
  private final EntityAliasRepository aliasRepository;

  public JdbcEntityMergeService(JdbcTemplate jdbc, EntityAliasRepository aliasRepository) {
    this.jdbc = jdbc;
    this.aliasRepository = aliasRepository;
  }

  @Override
  public Decision resolve(UUID kbId, String code) {
    if (kbId == null || code == null || code.isBlank()) {
      return Decision.insert();
    }
    List<UUID> found =
        jdbc.query(
            """
            select id from kb_entity
             where kb_id = ? and code = ? and deleted_at is null
             limit 1
            """,
            (rs, i) -> rs.getObject(1, UUID.class),
            kbId,
            code);
    if (found.isEmpty()) {
      return Decision.insert();
    }
    UUID existing = found.get(0);
    log.info("#实体归并 强键 {} 已在库里（{}），本次不插新行、只记别名（kb={}）", code, existing, kbId);
    return Decision.mergeInto(existing, code);
  }

  @Override
  public int recordAliases(UUID kbId, UUID entityId, List<String> aliases) {
    if (kbId == null || entityId == null || aliases == null || aliases.isEmpty()) {
      return 0;
    }
    List<EntityAliasRepository.Alias> rows = new ArrayList<>();
    for (String a : aliases) {
      if (a == null || a.isBlank()) {
        continue;
      }
      // ★ 置信度 1.0：强键相同是确定性判断，不是启发式。
      rows.add(new EntityAliasRepository.Alias(entityId, a, "IMPORT", 1.0));
    }
    if (rows.isEmpty()) {
      return 0;
    }
    return aliasRepository.insertAll(kbId, rows);
  }

  @Override
  public List<Candidate> candidatesByAttributes(UUID kbId) {
    if (kbId == null) {
      return List.of();
    }
    // ★ 这一档只生成候选，**不写任何东西**。
    //   判据：两个实体的属性里至少有一对同名的键，且值完全相同。
    //   不给分数 —— 让确认的人看到"共同点是什么"，比给一个 0.87 更有用。
    List<Attr> rows =
        jdbc.query(
            """
            select id, name, attributes
              from kb_entity
             where kb_id = ?
               and deleted_at is null
               and merged_into is null
               and attributes is not null
             order by created_at, id
            """,
            (rs, i) ->
                new Attr(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("attributes")),
            kbId);
    List<Candidate> out = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      for (int j = i + 1; j < rows.size(); j++) {
        String shared = sharedAttribute(rows.get(i).attributes(), rows.get(j).attributes());
        if (shared != null) {
          out.add(
              new Candidate(
                  rows.get(i).id(),
                  rows.get(i).name(),
                  rows.get(j).id(),
                  rows.get(j).name(),
                  "共同属性 " + shared));
        }
      }
    }
    return out;
  }

  /**
   * 一对实体的共同属性；没有人可读的返回 {@code null}。
   *
   * <p>★ 不用 Jackson 反序列化再比：{@code attributes} 是自由形状的 jsonb，转成 Map 再比会有类型差异 （数字 vs
   * 字符串）。这里只做<b>最保守</b>的判定 —— 描出 {@code "key": "value"} 这种字符串形态、且键值都相同。 判不准的那部分不报，比报错一堆让人跳过整份清单要好。
   */
  static String sharedAttribute(String a, String b) {
    if (a == null || b == null || a.isBlank() || b.isBlank()) {
      return null;
    }
    for (Map.Entry<String, String> ea : pairs(a).entrySet()) {
      String vb = pairs(b).get(ea.getKey());
      if (vb != null && vb.equals(ea.getValue())) {
        return ea.getKey() + "=" + ea.getValue();
      }
    }
    return null;
  }

  /** 从 jsonb 文本里描出所有 {@code "key": "value"} 对（只认双引号字符串值）。 */
  private static Map<String, String> pairs(String json) {
    Map<String, String> out = new LinkedHashMap<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    while (m.find()) {
      out.put(m.group(1), m.group(2));
    }
    return out;
  }

  private record Attr(UUID id, String name, String attributes) {}
}
