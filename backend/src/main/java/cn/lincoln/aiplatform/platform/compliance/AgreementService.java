package cn.lincoln.aiplatform.platform.compliance;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 服务协议同意留痕（{@code #83} 第九条 / 第十条）。
 *
 * <h3>★ 为什么不是纯前端弹窗</h3>
 *
 * 弹窗没法回填历史 —— 「用户见过哪一版协议」要能举证就得落库，晚建表只能从建表日起算。 同意留痕本身是实现选择（法条要求的是公示与防沉迷说明，未规定留痕形式），已在迁移注释声明。
 *
 * <p>一用户一版本一行：协议改版后旧记录保留，新版本未同意 ⇒ 前端按版本比对重新弹窗。
 */
@Service
public class AgreementService {

  private final NamedParameterJdbcTemplate jdbc;

  public AgreementService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 该用户最近一次同意记录（任意版本）。没有 ⇒ 从未同意过。 */
  public Optional<AgreementConsent> latestOf(UUID userId) {
    return jdbc
        .queryForList(
            "select id, user_id, agreement_version, agreed_at from platform_agreement_consent"
                + " where user_id = :userId order by agreed_at desc limit 1",
            Map.of("userId", userId))
        .stream()
        .findFirst()
        .map(AgreementService::mapRow);
  }

  /**
   * 记录一次同意（同用户同版本重复提交幂等 —— 返回既有记录）。
   *
   * @param version 协议版本号（前端 {@code AGREEMENT_VERSION}，非空 ≤64 字符）
   */
  public AgreementConsent consent(UUID userId, String version) {
    // ★ Instant 要转 Timestamp 再进参数（PG 驱动 setObject 无法推测 Instant 的 SQL 类型）。
    java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
    try {
      jdbc.update(
          "insert into platform_agreement_consent (user_id, agreement_version, agreed_at)"
              + " values (:userId, :version, :now)",
          Map.of("userId", userId, "version", version, "now", now));
    } catch (DuplicateKeyException duplicate) {
      // 幂等：同版本再点一次同意，返回第一次的留痕（时刻以第一次为准）。
      return find(userId, version).orElseThrow(() -> duplicate);
    }
    return find(userId, version).orElseThrow(IllegalStateException::new);
  }

  private Optional<AgreementConsent> find(UUID userId, String version) {
    return jdbc
        .queryForList(
            "select id, user_id, agreement_version, agreed_at from platform_agreement_consent"
                + " where user_id = :userId and agreement_version = :version",
            Map.of("userId", userId, "version", version))
        .stream()
        .findFirst()
        .map(AgreementService::mapRow);
  }

  private static AgreementConsent mapRow(Map<String, Object> row) {
    return new AgreementConsent(
        (UUID) row.get("id"),
        (UUID) row.get("user_id"),
        (String) row.get("agreement_version"),
        ((java.sql.Timestamp) row.get("agreed_at")).toInstant());
  }
}
