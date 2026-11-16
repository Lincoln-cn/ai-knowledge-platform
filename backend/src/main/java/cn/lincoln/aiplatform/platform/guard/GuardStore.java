package cn.lincoln.aiplatform.platform.guard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 安全围栏的读写（表 {@code pf_guard_category} / {@code pf_guard_rule} / {@code pf_guard_settings}，迁移 {@code
 * V102}）。
 *
 * <h3>★ 与 {@code RetrievalEngineSettingsStore} 同一形状，但失败方向相反</h3>
 *
 * <p>那边读不到库 ⇒ **回落到 yml**（检索照常工作）。这边读不到库 ⇒ <strong>沿用上次成功的快照；一次都没成功过就抛</strong>。 理由：围栏是安全件，
 * 失败方向必须朝「更严」。回落到 yml 在本项目里等于**偷偷放宽** —— 环境变量那份词通常比库里少， 甚至为空（默认为空），"读不到库就退回空词表"会让围栏静默消失。
 *
 * <p>★ 但**缓存过期后重试失败**时不能抛：那会让已经在跑的会话在第 30 秒突然 500。所以过期重试用旧快照 + WARN， 只有"从未成功加载"才抛（= 启动期，此时启动失败是对的）。
 *
 * <h3>★ 多副本的边界（写下来，不假装没有）</h3>
 *
 * <p>快照是进程内的：多副本部署时，A 副本保存后 B 副本最多等一个 TTL（{@link #TTL_MILLIS}）才看到新规则。 对"加一条词"是可接受的；对"立刻生效的封禁"不够 ——
 * 那种需求要做跨副本失效广播，不进本版。
 */
@Component
public class GuardStore {

  private static final Logger log = LoggerFactory.getLogger(GuardStore.class);

  /**
   * 快照有效期。
   *
   * <p>★ 为什么是 30 秒而不是"只靠写后失效"：写后失效只覆盖**本副本**的写。运营在 A 副本加的词， B 副本永远等不到那个失效事件 —— 一个 TTL 是"最终一致"的兜底。30
   * 秒的取法是"人能接受的生效延迟" 与"读库压力"折中：一次问答两条 SQL，30 秒一次对任何库都不算什么。
   */
  static final long TTL_MILLIS = 30_000L;

  /** 单行表的主键恒为 true。 */
  private static final boolean SINGLETON = true;

  /** 环境变量播种的落点分类（{@code V102} 里的内置分类）。 */
  private static final UUID UNCLASSIFIED_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000000");

  private final JdbcTemplate jdbc;

  /** 进程内快照。{@code null} = 还没成功读过。 */
  private volatile GuardSnapshot snapshot;

  private volatile long loadedAtMillis;

  /**
   * 懒加载而不是 {@code @PostConstruct}：Flyway 的初始化与 bean 构造没有强制顺序， 构造期查表会撞上"表还没建"（同 {@code
   * RetrievalEngineSettingsStore} 的理由）。
   */
  public GuardStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 当前快照（零查询，除非过期或从未加载）。
   *
   * @return 快照
   * @throws IllegalStateException 从未成功加载过（此时应当启动失败，而不是放行一切）
   */
  public GuardSnapshot current() {
    GuardSnapshot current = snapshot;
    if (current != null && System.currentTimeMillis() - loadedAtMillis < TTL_MILLIS) {
      return current;
    }
    return reload();
  }

  /**
   * 强制从库重读。
   *
   * @return 新快照
   * @throws IllegalStateException 从未成功加载过，且这次也失败
   */
  public synchronized GuardSnapshot reload() {
    GuardSnapshot previous = snapshot;
    try {
      GuardSnapshot loaded = load();
      snapshot = loaded;
      loadedAtMillis = System.currentTimeMillis();
      return loaded;
    } catch (RuntimeException unreadable) {
      if (previous != null) {
        // ★ 推进时间戳：否则每一次请求都会再来打一次库，把一次读库故障放大成 N 次
        loadedAtMillis = System.currentTimeMillis();
        log.warn(
            "读安全围栏配置失败（{}）—— 继续沿用上一次成功加载的快照（{} 条分类 / {} 条规则）。" + "★ 不会放宽：失败方向必须朝更严",
            unreadable.getMessage(),
            previous.categories().size(),
            previous.rules().size());
        return previous;
      }
      throw new IllegalStateException(
          "安全围栏配置从未成功加载过，且这次也失败（"
              + unreadable.getMessage()
              + "）—— "
              + "拒绝以「无围栏」状态启动。若这是首次部署，请确认迁移 V102 已执行",
          unreadable);
    }
  }

  /**
   * 从 {@code CHAT_GUARD_SENSITIVE_WORDS} 播种（{@code ROADMAP §11.53.5} 第一条）。
   *
   * <p>★ 只在规则表**完全为空**时播种。表里已有规则 ⇒ 环境变量**不再生效**并打 WARN —— 否则两条真源 会同时存在，而界面上只显示库里那份，"为什么删了还在拦"就无从回答。
   *
   * @param words 环境变量解析出的词（可空）
   * @return 实际播种条数（0 = 没播种）
   */
  public int seedFromEnvironment(List<String> words) {
    if (words == null || words.isEmpty()) {
      return 0;
    }
    Integer existing = jdbc.queryForObject("select count(*) from pf_guard_rule", Integer.class);
    if (existing != null && existing > 0) {
      log.warn(
          "环境变量 CHAT_GUARD_SENSITIVE_WORDS 配了 {} 条，但围栏规则表已有 {} 条 ⇒ 环境变量不再生效。"
              + "请在「模型与检索 → 安全围栏」页维护规则",
          words.size(),
          existing);
      return 0;
    }
    int seeded = 0;
    for (String word : words) {
      String normalized = GuardText.normalize(word, GuardRuleKind.KEYWORD);
      if (normalized.isEmpty()) {
        continue;
      }
      try {
        jdbc.update(
            "insert into pf_guard_rule (id, category_id, kind, content, normalized, enabled, note,"
                + " created_by, updated_at) values (?, ?, 'KEYWORD', ?, ?, true, ?, null, now())"
                + " on conflict (category_id, kind, normalized) do nothing",
            UUID.randomUUID(),
            UNCLASSIFIED_ID,
            word,
            normalized,
            "由环境变量 CHAT_GUARD_SENSITIVE_WORDS 播种");
        seeded++;
      } catch (RuntimeException failed) {
        log.warn("播种敏感词「{}」失败：{}", word, failed.getMessage());
      }
    }
    if (seeded > 0) {
      log.info("已从 CHAT_GUARD_SENSITIVE_WORDS 播种 {} 条围栏规则（分类：未分类）", seeded);
      reload();
    }
    return seeded;
  }

  /**
   * 整行覆盖写运行开关，并把快照换成新值（即时生效）。
   *
   * @param settings 已校验的新值
   * @param operator 操作人
   * @return 落库后的值
   * @throws IllegalStateException 库里没有那一行（= 迁移没跑）
   */
  public GuardSettings saveSettings(GuardSettings settings, UUID operator) {
    GuardSettings checked = settings.checked();
    int affected =
        jdbc.update(
            "update pf_guard_settings set input_enabled = ?, default_refusal_text = ?,"
                + " advisors_enabled = ?, category_refusal_enabled = ?, updated_at = now(),"
                + " updated_by = ? where id = ?",
            checked.inputEnabled(),
            checked.defaultRefusalText(),
            checked.advisorsEnabled(),
            checked.categoryRefusalEnabled(),
            operator,
            SINGLETON);
    if (affected == 0) {
      throw new IllegalStateException(
          "pf_guard_settings 没有那一行，无法更新 —— 请先跑迁移 V102（★ 不要在这里 insert，否则「单行」这条约束会变成两行）");
    }
    GuardSnapshot reloaded = reload();
    log.info(
        "安全围栏配置已更新：输入检查={} advisor 链={} 分类话术={}",
        reloaded.settings().inputEnabled(),
        reloaded.settings().advisorsEnabled(),
        reloaded.settings().categoryRefusalEnabled());
    return reloaded.settings();
  }

  /**
   * 新增或修改一个分类（{@code id} 为空 = 新增）。
   *
   * @param id 主键；{@code null} 表示新增
   * @param code 机器码（新增时必填且唯一；修改时**不可改**）
   * @param name 展示名
   * @param sortOrder 排序
   * @param enabled 是否启用
   * @param refusalText 分类专属话术（可空）
   * @return 落库后的分类
   */
  public GuardCategory saveCategory(
      UUID id, String code, String name, int sortOrder, boolean enabled, String refusalText) {
    if (id == null) {
      UUID newId = UUID.randomUUID();
      jdbc.update(
          "insert into pf_guard_category (id, code, name, sort_order, enabled, builtin,"
              + " refusal_text) values (?, ?, ?, ?, ?, false, ?)",
          newId,
          code,
          name,
          sortOrder,
          enabled,
          refusalText);
      id = newId;
    } else {
      // ★ 只改 name / sort_order / enabled / refusal_text：code 已被历史审计引用，改了就对不上
      int affected =
          jdbc.update(
              "update pf_guard_category set name = ?, sort_order = ?, enabled = ?, refusal_text = ?"
                  + " where id = ?",
              name,
              sortOrder,
              enabled,
              refusalText,
              id);
      if (affected == 0) {
        throw new IllegalArgumentException("分类不存在：" + id);
      }
    }
    reload();
    UUID target = id;
    return snapshot.categories().stream()
        .filter(category -> category.id().equals(target))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("刚写入的分类读不回来：" + target));
  }

  /**
   * 停用一个分类（内置分类不许删）。★ 停用而非删除：历史审计里那个 {@code code} 还要有显示名可对照。
   *
   * @param id 分类主键
   * @return 停用后的分类
   */
  public GuardCategory disableCategory(UUID id) {
    GuardCategory existing =
        current().categories().stream()
            .filter(category -> category.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("分类不存在：" + id));
    // ★ 带着原话术一起写回：停用是"这一类不参与匹配"，不是"把配置清空"。
    //   传 null 的话，重新启用之后专属话术已经没了 —— 而它洽洽是最难回忆的那一格。
    return saveCategory(
        existing.id(),
        existing.code(),
        existing.name(),
        existing.sortOrder(),
        false,
        existing.refusalText());
  }

  /**
   * 新增或修改一条规则（{@code id} 为空 = 新增）。
   *
   * @param id 主键；{@code null} 表示新增
   * @param categoryId 分类
   * @param kind 类型
   * @param content 原始写法
   * @param enabled 是否启用
   * @param note 备注
   * @param operator 操作人
   * @return 落库后的规则
   */
  public GuardRule saveRule(
      UUID id,
      UUID categoryId,
      GuardRuleKind kind,
      String content,
      boolean enabled,
      String note,
      UUID operator) {
    String normalized = GuardText.normalize(content, kind);
    if (normalized.isEmpty()) {
      // ★ 空规则必须挡住：`contains("")` 恒真，一条空规则会把全平台的问答都拦掉
      throw new IllegalArgumentException("规则内容不能为空（归一化后为空也算空）");
    }
    if (id == null) {
      UUID newId = UUID.randomUUID();
      jdbc.update(
          "insert into pf_guard_rule (id, category_id, kind, content, normalized, enabled, note,"
              + " created_by, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, now())",
          newId,
          categoryId,
          kind.name(),
          content,
          normalized,
          enabled,
          note,
          operator);
      id = newId;
    } else {
      int affected =
          jdbc.update(
              "update pf_guard_rule set category_id = ?, kind = ?, content = ?, normalized = ?,"
                  + " enabled = ?, note = ?, updated_at = now() where id = ?",
              categoryId,
              kind.name(),
              content,
              normalized,
              enabled,
              note,
              id);
      if (affected == 0) {
        throw new IllegalArgumentException("规则不存在：" + id);
      }
    }
    reload();
    UUID target = id;
    return snapshot.rules().stream()
        .filter(rule -> rule.id().equals(target))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("刚写入的规则读不回来：" + target));
  }

  /**
   * 删除一条规则。
   *
   * <p>★ 与字典不同，这里**允许真删**：一条错加的词留在表里即使停用，也会让"这条词存在过" 变成永久记录（而围栏的词表本身就是敏感内容）。留痕走审计。
   *
   * @param id 规则主键
   * @return 是否删到了
   */
  public boolean deleteRule(UUID id) {
    int affected = jdbc.update("delete from pf_guard_rule where id = ?", id);
    if (affected > 0) {
      reload();
    }
    return affected > 0;
  }

  /**
   * 按归一化口径重算全表（{@code GuardText} 的算法升级后用）。
   *
   * @return 重算条数
   */
  public int renormalizeAll() {
    List<Object[]> rows =
        jdbc.query(
            "select id, kind, content from pf_guard_rule",
            (rs, rowNum) ->
                new Object[] {
                  rs.getObject("id", UUID.class),
                  GuardRuleKind.valueOf(rs.getString("kind")),
                  rs.getString("content")
                });
    for (Object[] row : rows) {
      UUID id = (UUID) row[0];
      GuardRuleKind kind = (GuardRuleKind) row[1];
      String content = (String) row[2];
      jdbc.update(
          "update pf_guard_rule set normalized = ? where id = ?",
          GuardText.normalize(content, kind),
          id);
    }
    reload();
    return rows.size();
  }

  /**
   * 最近若干天内被拦的次数，按分类聚合。
   *
   * <p>★ 真源是审计表（{@code pf_audit_log}），**不另建一张计数表**：两张表迟早不一致， 而"今天拦了几次"这个问题只有一个可核对的答案 —— 审计里那几行。★
   * 只回计数，不回落库的词面。
   *
   * @param days 窗口天数
   * @return 按次数降序
   */
  public List<GuardHitCount> hitCounts(int days) {
    int window = Math.max(1, Math.min(days, 90));
    return jdbc.query(
        "select coalesce(detail ->> 'category', 'UNKNOWN') as category, count(*) as hits from"
            + " pf_audit_log where action = ? and created_at >= now() - make_interval(days => ?)"
            + " group by 1 order by 2 desc",
        (rs, rowNum) -> new GuardHitCount(rs.getString("category"), rs.getLong("hits")),
        cn.lincoln.aiplatform.platform.domain.AuditAction.GUARD_BLOCK.code(),
        window);
  }

  private GuardSnapshot load() {
    GuardSettings settings =
        jdbc
            .query(
                "select input_enabled, default_refusal_text, advisors_enabled,"
                    + " category_refusal_enabled, updated_at from pf_guard_settings where id = ?",
                (rs, rowNum) ->
                    new GuardSettings(
                        rs.getBoolean("input_enabled"),
                        rs.getString("default_refusal_text"),
                        rs.getBoolean("advisors_enabled"),
                        rs.getBoolean("category_refusal_enabled"),
                        rs.getObject("updated_at", java.time.OffsetDateTime.class) == null
                            ? null
                            : rs.getObject("updated_at", java.time.OffsetDateTime.class)
                                .toInstant()),
                SINGLETON)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("pf_guard_settings 没有那一行（迁移 V102 没跑？）"));

    List<GuardCategory> categories =
        jdbc.query(
            "select id, code, name, sort_order, enabled, builtin, refusal_text"
                + " from pf_guard_category order by sort_order, code",
            (rs, rowNum) ->
                new GuardCategory(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("builtin"),
                    rs.getString("refusal_text")));

    // ★ 分类话术同时装进 GuardCategory：界面编辑分类时要回填（`saveCategory` 整列覆盖，
    //   前端拿不到原值就会把专属话术静默清掉）。下面这份 map 是匹配路径用的，两者同源不同用途。
    //   这里单独查一份映射，避免为了一个可空字段把分类记录撑胖。
    java.util.Map<UUID, String> categoryRefusal = new java.util.HashMap<>();
    jdbc.query(
        "select id, refusal_text from pf_guard_category where refusal_text is not null",
        rs -> {
          categoryRefusal.put(rs.getObject("id", UUID.class), rs.getString("refusal_text"));
        });

    List<GuardRule> rules =
        jdbc.query(
            "select r.id, r.category_id, c.code as category_code, c.name as category_name, r.kind,"
                + " r.content, r.enabled, r.note, r.normalized, c.enabled as category_enabled from"
                + " pf_guard_rule r join pf_guard_category c on c.id = r.category_id order by"
                + " c.sort_order, r.kind, r.content",
            (rs, rowNum) ->
                new GuardRule(
                    rs.getObject("id", UUID.class),
                    rs.getObject("category_id", UUID.class),
                    rs.getString("category_code"),
                    rs.getString("category_name"),
                    GuardRuleKind.valueOf(rs.getString("kind")),
                    rs.getString("content"),
                    rs.getBoolean("enabled"),
                    rs.getString("note")));

    List<GuardSnapshot.Pattern> patterns = new ArrayList<>();
    if (settings.inputEnabled() && settings.advisorsEnabled()) {
      jdbc.query(
          "select r.id, r.category_id, c.code as category_code, c.name as category_name, r.kind,"
              + " r.normalized from pf_guard_rule r join pf_guard_category c"
              + " on c.id = r.category_id where r.enabled = true and c.enabled = true",
          rs -> {
            UUID categoryId = rs.getObject("category_id", UUID.class);
            String normalized = rs.getString("normalized");
            patterns.add(
                new GuardSnapshot.Pattern(
                    rs.getObject("id", UUID.class),
                    categoryId,
                    rs.getString("category_code"),
                    rs.getString("category_name"),
                    GuardRuleKind.valueOf(rs.getString("kind")),
                    normalized,
                    GuardText.hashPrefix(normalized),
                    categoryRefusal.get(categoryId)));
          });
    }

    return new GuardSnapshot(
        settings, List.copyOf(categories), List.copyOf(rules), List.copyOf(patterns));
  }

  /** 最近一次成功加载的时刻（诊断用；{@code null} 表示从未成功）。 */
  public Instant loadedAt() {
    return loadedAtMillis == 0 ? null : Instant.ofEpochMilli(loadedAtMillis);
  }
}
