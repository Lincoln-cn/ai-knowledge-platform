package cn.lincoln.aiplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.identity.domain.Role;
import cn.lincoln.aiplatform.identity.domain.User;
import cn.lincoln.aiplatform.identity.domain.UserStatus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 令牌的签发与验签（{@code ROADMAP §4.7.3} 第 2 周测试项 ②）。
 *
 * <p>★ 这是<strong>纯单测</strong>：不启动 Spring、不连数据库。令牌是纯计算， 而"哪个字段进去了、哪个字段能取出来"这类问题，用真 encoder/decoder
 * 走一遍就够了 —— 启动一个上下文只会让这个测试从 50 毫秒变成 20 秒。
 *
 * <p>★ 断言的重点在<strong>契约与安全两侧</strong>：权限点必须能被解出来（否则 {@code @PreAuthorize} 会全拒），篡改必须被拒（否则整个方案没有意义）。
 */
class JwtTokenServiceTest {

  private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1rZXktMzJieXRlcy1sb25nISE=";

  private JwtTokenService service;
  private JwtDecoder decoder;

  @BeforeEach
  void setUp() {
    SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256");
    JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(key).build();
    decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    service =
        new JwtTokenService(
            encoder,
            new JwtProperties(
                "http://test-issuer.local", Duration.ofMinutes(30), Duration.ofDays(7), SECRET));
  }

  @Test
  void 签发的令牌可被同一密钥验签并解出主体与角色() {
    User user = user();
    Role role = new Role(UUID.randomUUID(), "管理员", "ADMIN");

    Jwt jwt = decoder.decode(service.issueAccessToken(user, role));

    assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
    assertThat(jwt.getIssuer().toString()).isEqualTo("http://test-issuer.local");
    assertThat(jwt.getClaimAsString("username")).isEqualTo("admin");
    assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
  }

  /**
   * ★ 权限点必须原样出现在令牌里 —— {@code SecurityConfig} 的转换器读的就是这个 claim。 它一旦丢了，{@code @PreAuthorize}
   * 会变成"永远拒绝"，而报错信息不会告诉你是这里的问题。
   */
  @Test
  void 权限点随令牌下发且与角色一致() {
    User user = user();
    Role admin = new Role(UUID.randomUUID(), "管理员", "ADMIN");
    Role normal = new Role(UUID.randomUUID(), "普通用户", "USER");

    assertThat(
            decoder
                .decode(service.issueAccessToken(user, admin))
                .getClaimAsStringList("authorities"))
        .hasSize(16);
    assertThat(
            decoder
                .decode(service.issueAccessToken(user, normal))
                .getClaimAsStringList("authorities"))
        .containsExactlyInAnyOrder(
            "knowledge:view", "assistant:view", "conversation:view", "conversation:share");
  }

  /** 未知角色代码 ⇒ 空权限点，而不是抛异常（登录不该因为一个配置问题 500）。 */
  @Test
  void 未知角色代码给出空权限点而不是异常() {
    Role weird = new Role(UUID.randomUUID(), "从哪来的", "NOT_A_REAL_ROLE");

    Jwt jwt = decoder.decode(service.issueAccessToken(user(), weird));

    assertThat(jwt.getClaimAsStringList("authorities")).isEmpty();
  }

  /** ★ 篡改必须被拒 —— 这是整套令牌方案的地基。 */
  @Test
  void 被篡改的令牌验签失败() {
    String token = service.issueAccessToken(user(), new Role(UUID.randomUUID(), "管理员", "ADMIN"));
    String[] parts = token.split("\\.");
    // 改一位 payload，签名就不再匹配
    String tamperedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"sub\":\"attacker\"}".getBytes(StandardCharsets.UTF_8));
    String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

    assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
  }

  /** 换一个密钥签的令牌，在验签方看来就是无效的（对称密钥下也是最基本的要求）。 */
  @Test
  void 别的密钥签出的令牌验签失败() {
    SecretKey other =
        new SecretKeySpec(
            Base64.getDecoder().decode("b3RoZXItc2VjcmV0LWtleS0zMmJ5dGVzLWxvbmchISEh"),
            "HmacSHA256");
    JwtEncoder otherEncoder = NimbusJwtEncoder.withSecretKey(other).build();
    JwtTokenService otherService =
        new JwtTokenService(
            otherEncoder,
            new JwtProperties(
                "http://test-issuer.local", Duration.ofMinutes(30), Duration.ofDays(7), SECRET));

    String foreign =
        otherService.issueAccessToken(user(), new Role(UUID.randomUUID(), "管理员", "ADMIN"));

    assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
  }

  /** 过期令牌必须被拒：这条是"短有效期 + 轮换"这套设计能成立的前提。 */
  @Test
  void 过期令牌被拒绝() {
    SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256");
    JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(key).build();

    // ★ 手工构造一个"签发于过去、且已过期"的令牌。
    //   不能靠给 service 传负的 TTL —— JwtClaimsSet 会先拒绝"expiresAt 早于 issuedAt"，
    //   那是**构建期**的参数校验，测不到"验签方是否检查过期"这件真正想验的事。
    Instant past = Instant.now().minusSeconds(3600);
    JwtClaimsSet expired =
        JwtClaimsSet.builder()
            .issuer("http://test-issuer.local")
            .subject(user().id().toString())
            .issuedAt(past)
            .expiresAt(past.plusSeconds(60))
            .build();

    String token = encoder.encode(JwtEncoderParameters.from(expired)).getTokenValue();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
  }

  private static User user() {
    return new User(
        UUID.fromString("10000000-0000-0000-0000-000000000001"),
        "admin",
        "管理员",
        "$2a$10$notarealhash",
        UUID.randomUUID(),
        UserStatus.正常,
        false);
  }
}
