package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.application.MemorySettingsView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 记忆设定的读写（{@code #296}，表 {@code cv_memory_setting}，单行 · 迁移 {@code V101}）。
 *
 * <h3>★ 为什么"读不到就回落默认值 + WARN"，而 {@code ChatSettingsStore} 是"读不到就抛"</h3>
 *
 * <p>两者的失败代价不同：
 *
 * <ul>
 *   <li>{@code cv_chat_settings} 读不到 ⇒ 会话本身的行为不可知，界面会显示一套并不生效的数字 ⇒ <strong>必须抛</strong>；
 *   <li>{@code cv_memory_setting} 读不到 ⇒ 记忆这个<strong>增强项</strong>没配置好，但对话本身完全能跑 ⇒
 *       <strong>回落默认值并说清"现在用的是默认值"</strong>，比让整个对话失败更合理（{@code 15 §6.5}）。
 * </ul>
 *
 * <p>★ 混合写 {@code true} 而不是 {@code "true"}：{@code id boolean primary key check (id)} 让第二行插不进去，
 * "单行"不是靠约定。
 */
@Component
public class MemorySettingsStore {

  private static final Logger LOG = LoggerFactory.getLogger(MemorySettingsStore.class);

  /** 单行表的主键恒为 true。 */
  private static final boolean SINGLETON = true;

  /**
   * 设定的缓存时长。
   *
   * <p>★ 5 秒是"一个人改了设定、整机马上按新的跑"与"每轮对话省一次 SQL"的折中： {@link #load()}
   * 在<strong>每一轮</strong>对话的召回与写入路径上都会被调用， 而设定是那种"一天改一次"的东西 —— 不缓存等于每轮白跑一次查询。
   *
   * <p>★ 取 5 秒而不是 30 秒：管理员改完设定会立刻刷新页面看数字变没变， 等半分钟才生效看起来像"没保存上"。
   */
  private static final java.time.Duration CACHE_TTL = java.time.Duration.ofSeconds(5);

  /** 缓存里的一条（设定 + 它是什么时候读到的）。 */
  private record Cached(MemorySettingsView view, Instant loadedAt) {}

  /** 缓存的当前值（{@code null} = 还没读过）。 */
  private volatile Cached cached;

  /** 落不下配置时的默认值（与迁移里的 insert 逐字一致）。 */
  private static final MemorySettingsView DEFAULTS =
      new MemorySettingsView(true, 3, 5, true, "0 40 3 * * *", 90, null, null, null, null);

  private final JdbcTemplate jdbc;

  public MemorySettingsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 读当前设定（带 {@link #CACHE_TTL} 缓存）。
   *
   * <p>★ 读不到（迁移没跑 / 行被删）时回落 {@link #DEFAULTS} 并打 WARN，<strong>不抛</strong>：见类注释。
   *
   * @return 当前设定
   */
  public MemorySettingsView load() {
    Cached snapshot = cached;
    if (snapshot != null && Instant.now().isBefore(snapshot.loadedAt().plus(CACHE_TTL))) {
      return snapshot.view();
    }
    MemorySettingsView fresh = readFromDb();
    cached = new Cached(fresh, Instant.now());
    return fresh;
  }

  /**
   * 真正查库。
   *
   * @return 设定
   */
  private MemorySettingsView readFromDb() {
    List<MemorySettingsView> found =
        jdbc.query(
            "select enabled, min_importance, recall_limit, inject_profile, dream_cron,"
                + " forget_after_days, last_dream_at, last_dream_note, updated_at, updated_by"
                + " from cv_memory_setting where id = ?",
            (rs, rowNum) -> {
              OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
              OffsetDateTime dream = rs.getObject("last_dream_at", OffsetDateTime.class);
              return new MemorySettingsView(
                  rs.getBoolean("enabled"),
                  rs.getInt("min_importance"),
                  rs.getInt("recall_limit"),
                  rs.getBoolean("inject_profile"),
                  rs.getString("dream_cron"),
                  rs.getInt("forget_after_days"),
                  dream == null ? null : dream.toInstant(),
                  rs.getString("last_dream_note"),
                  at == null ? null : at.toInstant(),
                  rs.getObject("updated_by", UUID.class));
            },
            SINGLETON);
    if (found.isEmpty()) {
      LOG.warn("cv_memory_setting 没有那一行（迁移 V101 可能没跑）—— 记忆功能现在用代码默认值，" + "界面上的设定不会生效。请确认库已迁到位。");
      return DEFAULTS;
    }
    return found.get(0);
  }

  /**
   * 整行覆盖写（只写七个业务字段，<strong>不动</strong> {@code last_dream_*}）。
   *
   * <p>★ 用 {@code update} 而不是 upsert：行由迁移插入；{@code affected == 0} 说明库不对， 这里补一行会让"单行"约束变成两行。
   *
   * @param settings 新设定
   * @param operator 操作人
   * @return 落库后的设定
   * @throws IllegalStateException 行不存在
   */
  public MemorySettingsView save(MemorySettingsView settings, UUID operator) {
    MemorySettingsView checked = settings.checked();
    int affected =
        jdbc.update(
            "update cv_memory_setting set enabled = ?, min_importance = ?, recall_limit = ?,"
                + " inject_profile = ?, dream_cron = ?, forget_after_days = ?,"
                + " updated_at = now(), updated_by = ?"
                + " where id = ?",
            checked.enabled(),
            checked.minImportance(),
            checked.recallLimit(),
            checked.injectProfile(),
            checked.dreamCron(),
            checked.forgetAfterDays(),
            operator,
            SINGLETON);
    if (affected == 0) {
      throw new IllegalStateException(
          "cv_memory_setting 没有那一行，无法更新 —— 请先跑迁移 V101" + "（★ 不要在这里 insert，否则「单行」这条约束会变成两行）");
    }
    /* ★ 保存后立刻失效缓存：否则接下来的 CACHE_TTL 内读到的还是旧值，用户会以为没保存上。 */
    cached = null;
    return load();
  }

  /**
   * 记一次做梦的结果（界面上"上次做梦"读它）。
   *
   * <p>★ 单独一个方法而不是塞进 {@link #save}：做梦任务与用户改设定是两件事， 混在一处会让"用户点保存"顺手覆盖掉做梦时间。
   *
   * @param at 时刻
   * @param note 一句话摘要（超 200 字截断，与列宽一致）
   */
  public void recordDream(Instant at, String note) {
    String clipped =
        note == null ? null : (note.length() <= 200 ? note : note.substring(0, 199) + "…");
    jdbc.update(
        "update cv_memory_setting set last_dream_at = ?, last_dream_note = ? where id = ?",
        at == null ? null : OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC),
        clipped,
        SINGLETON);
    /* ★ 同上：上一行改的是"上次做梦"，界面读它；不清缓存的话点开记忆页会看到旧时间。 */
    cached = null;
  }

  /**
   * 默认值（未落库时用于展示与回落）。
   *
   * @return 默认设定
   */
  public static MemorySettingsView defaults() {
    return DEFAULTS;
  }
}
