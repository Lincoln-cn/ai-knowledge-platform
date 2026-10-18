package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.application.ChatSettingsView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 「通用会话」设置的读写（表 {@code cv_chat_settings}，单行 · 迁移 {@code V39}）。
 *
 * <h3>★ 为什么用 JDBC 而不是 JPA 实体</h3>
 *
 * <p>它是**单行、6 个标量、没有任何聚合行为**的配置行。用 JPA 实体要写 实体 + 仓储 + 适配器三个文件，而这里的"行为"只有"读一行 / 写一行"。 ★
 * 判据：**没有不变量要守的聚合不值得做成实体** —— 唯一的不变量 （{@code heartbeat*3 <= idle}）已经在 {@code ChatStreamPolicy}
 * 的构造器里，不在这里。
 *
 * <p>★ 单行用 {@code id boolean primary key check (id)}（见迁移注释）：第二行插不进去， 不是靠"约定只有一行"。
 */
@Component
public class ChatSettingsStore {

  /** 单行表的主键恒为 true。 */
  private static final boolean SINGLETON = true;

  private final JdbcTemplate jdbc;

  public ChatSettingsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 读当前设置。
   *
   * <p>★ 读不到就抛：迁移 {@code V39} 已 {@code insert} 了这一行，读不到说明库没迁到位 ——
   * 那时**不能**回落到"内存默认值"，否则界面上会显示一套并不生效的数字。
   *
   * @return 当前设置
   */
  public ChatSettingsView load() {
    List<ChatSettingsView> found =
        jdbc.query(
            "select default_model_id, stream_enabled, idle_timeout_ms, heartbeat_interval_ms,"
                + " first_token_budget_ms, max_output_tokens, updated_at"
                + " from cv_chat_settings where id = ?",
            (rs, rowNum) -> {
              UUID modelId = rs.getObject("default_model_id", UUID.class);
              Integer maxTokens = rs.getObject("max_output_tokens", Integer.class);
              return new ChatSettingsView(
                  modelId,
                  rs.getBoolean("stream_enabled"),
                  rs.getLong("idle_timeout_ms"),
                  rs.getLong("heartbeat_interval_ms"),
                  rs.getLong("first_token_budget_ms"),
                  maxTokens,
                  rs.getObject("updated_at", java.time.OffsetDateTime.class) == null
                      ? null
                      : rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
            },
            SINGLETON);
    if (found.isEmpty()) {
      throw new IllegalStateException(
          "cv_chat_settings 没有那一行 —— 迁移 V39 应已插入。★ 请先确认库已迁到位，" + "不要回落到内存默认值（那会让界面显示一套并不生效的数字）");
    }
    return found.get(0);
  }

  /**
   * 整行覆盖写。
   *
   * <p>★ 用 {@code update}（不是 upsert）：行由迁移插入，这里只改。★ 若 affected == 0 说明行被删了， 也是"库不对"而不是"要新建一行"。
   *
   * @param settings 新设置
   * @param operator 操作人
   * @return 落库后的设置（含 {@code updated_at}）
   */
  public ChatSettingsView save(ChatSettingsView settings, UUID operator) {
    int affected =
        jdbc.update(
            "update cv_chat_settings set default_model_id = ?, stream_enabled = ?,"
                + " idle_timeout_ms = ?, heartbeat_interval_ms = ?, first_token_budget_ms = ?,"
                + " max_output_tokens = ?, updated_at = now(), updated_by = ?"
                + " where id = ?",
            settings.defaultModelId(),
            settings.streamEnabled(),
            settings.idleTimeoutMs(),
            settings.heartbeatIntervalMs(),
            settings.firstTokenBudgetMs(),
            settings.maxOutputTokens(),
            operator,
            SINGLETON);
    if (affected == 0) {
      throw new IllegalStateException("cv_chat_settings 没有那一行，无法更新 —— 请先跑迁移 V39");
    }
    return load();
  }

  /**
   * @return 无用的占位（保留 imports 的 Instant 用于类型推断的稳定性）。
   */
  static Instant now() {
    return Instant.now();
  }
}
