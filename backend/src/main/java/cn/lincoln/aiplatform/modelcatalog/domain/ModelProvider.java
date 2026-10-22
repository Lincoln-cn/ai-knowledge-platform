package cn.lincoln.aiplatform.modelcatalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * 模型服务商（{@code ai_model_provider}）—— 一套“连接 + 凭据 + 出网边界”的配置。
 *
 * <h3>★★ 为什么它是独立聚合，而不是 {@code ai_model} 上的几个字段</h3>
 *
 * <p>同一厂家的同一把 key 会被多个模型共用；把 key 放在每个模型上，换 key 就要改 N 行，且“同一把 key 有两份密文” 迟早漂。服务商实例让 key 只有一份，模型只引
 * {@code provider_id}。
 *
 * <p>★ 但“同一厂家多套 key（生产/测试/轮换）”仍然支持：建两个服务商实例即可，不在一条记录里塞多把 key。
 *
 * <h3>★ 密钥纪律</h3>
 *
 * <p>与 {@link Model} 同一条：只存 {@code apiKeyCipher + apiKeyIv}，明文不落库、不进视图、不进日志。
 */
@Entity
@Table(name = "ai_model_provider")
public class ModelProvider {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private ProviderKind kind;

  @Column(name = "api_base", nullable = false, length = 512)
  private String apiBase;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_style", nullable = false, length = 24)
  private AuthStyle authStyle;

  @Enumerated(EnumType.STRING)
  @Column(name = "egress", nullable = false, length = 16)
  private ProviderEgress egress;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "api_key_cipher")
  private String apiKeyCipher;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(name = "api_key_iv")
  private String apiKeyIv;

  @Column(name = "enabled", nullable = false)
  private boolean enabled = true;

  @Column(name = "remark", length = 512)
  private String remark;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "created_by")
  private UUID createdBy;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected ModelProvider() {}

  public ModelProvider(
      UUID id,
      String name,
      ProviderKind kind,
      String apiBase,
      AuthStyle authStyle,
      ProviderEgress egress,
      UUID createdBy) {
    this.id = id;
    this.name = requireName(name);
    this.kind = kind;
    this.apiBase = requireApiBase(apiBase);
    this.authStyle = authStyle == null ? kind.defaultAuthStyle() : authStyle;
    this.egress = egress == null ? kind.defaultEgress() : egress;
    this.createdBy = createdBy;
    this.enabled = true;
  }

  /** 改基础配置（名称 / 地址 / 认证 / 出网 / 备注）；类型创建后不可改。 */
  public void update(
      String name,
      String apiBase,
      AuthStyle authStyle,
      ProviderEgress egress,
      String remark,
      UUID updatedBy) {
    this.name = requireName(name);
    this.apiBase = requireApiBase(apiBase);
    this.authStyle = authStyle == null ? kind.defaultAuthStyle() : authStyle;
    this.egress = egress == null ? kind.defaultEgress() : egress;
    this.remark = remark;
    this.updatedBy = updatedBy;
  }

  /** 设置 / 轮换密钥。★ 明文永远不进这个方法 —— 调用方先加密。 */
  public void apiKey(String cipher, String iv) {
    this.apiKeyCipher = cipher;
    this.apiKeyIv = iv;
  }

  /** 清除密钥（本地网关 / 无认证端点）。 */
  public void clearApiKey() {
    this.apiKeyCipher = null;
    this.apiKeyIv = null;
  }

  public void enabled(boolean enabled, UUID updatedBy) {
    this.enabled = enabled;
    this.updatedBy = updatedBy;
  }

  public boolean hasApiKey() {
    return apiKeyCipher != null && !apiKeyCipher.isBlank();
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("服务商名称必填");
    }
    String trimmed = name.trim();
    if (trimmed.length() > 64) {
      throw new IllegalArgumentException("服务商名称不能超过 64 字");
    }
    return trimmed;
  }

  private static String requireApiBase(String apiBase) {
    if (apiBase == null || apiBase.isBlank()) {
      throw new IllegalArgumentException("服务地址必填 —— 即使是默认端口也要显式写出来，便于核对");
    }
    String trimmed = apiBase.trim();
    if (trimmed.length() > 512) {
      throw new IllegalArgumentException("服务地址不能超过 512 字");
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
      throw new IllegalArgumentException("服务地址必须以 http:// 或 https:// 开头，收到：" + apiBase);
    }
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  // ────────────────────────── 访问器 ──────────────────────────

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public ProviderKind kind() {
    return kind;
  }

  public String apiBase() {
    return apiBase;
  }

  public AuthStyle authStyle() {
    return authStyle;
  }

  public ProviderEgress egress() {
    return egress;
  }

  public String apiKeyCipher() {
    return apiKeyCipher;
  }

  public String apiKeyIv() {
    return apiKeyIv;
  }

  public boolean enabled() {
    return enabled;
  }

  public String remark() {
    return remark;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public UUID updatedBy() {
    return updatedBy;
  }
}
